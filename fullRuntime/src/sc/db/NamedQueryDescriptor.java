package sc.db;

import sc.dyn.DynUtil;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class NamedQueryDescriptor extends BaseQueryDescriptor {
   public String dbQueryName;
   public List<String> paramNames;
   public List<String> paramDBTypeNames;

   // TODO: do we need the list of columns/tables returned? For now using ResultSet metadata for that info
   public boolean multiRow;
   public String returnTypeName;
   public String returnDBTypeName;

   public DBTypeDescriptor dbTypeDesc;

   public NamedQueryDescriptor(String queryName, String dbQueryName, List<String> paramNames, List<String> paramDBTypeNames, boolean multiRow, String returnTypeName, String returnDBTypeName) {
      this.queryName = queryName;
      this.dbQueryName = dbQueryName;
      this.paramNames = paramNames;
      this.paramDBTypeNames = paramDBTypeNames;
      this.paramTypeNames = new ArrayList<String>(paramDBTypeNames.size());
      for (String paramDBTypeName:paramDBTypeNames)
         this.paramTypeNames.add(DBUtil.convertSQLToJavaTypeName(paramDBTypeName));
      this.multiRow = multiRow;
      this.returnTypeName = returnTypeName;
      this.returnDBTypeName = returnDBTypeName;
   }

   public List<String> paramTypeNames;
   public List<Object> paramTypes;
   public Object returnType;

   public boolean typesInited() {
      return paramTypes != null;
   }

   public void initTypes(Object typeDecl) {
      paramTypeNames = new ArrayList<String>();
      paramTypes = new ArrayList<Object>();
      for (int i = 0; i < paramDBTypeNames.size(); i++) {
         paramTypeNames.add(DBUtil.convertSQLToJavaTypeName(paramDBTypeNames.get(i)));
         String paramTypeName = paramTypeNames.get(i);
         Object type = DynUtil.findType(paramTypeName);
         if (type == null && paramTypeName.equals("String"))
            type = String.class;
         paramTypes.add(type);
      }
      if (returnDBTypeName != null) {
         returnTypeName = DBUtil.convertSQLToJavaTypeName(returnDBTypeName);
         Object type = DynUtil.findType(returnTypeName);
         if (type == null && returnTypeName.equals("String"))
            type = String.class;
         returnType = type;
      }
   }

   public Object execute(DBTransaction transaction, Object...paramValues) {
      StringBuilder querySB = new StringBuilder();
      querySB.append("SELECT * FROM ");
      querySB.append(dbQueryName);
      querySB.append("(");
      for (int aix = 0; aix < paramValues.length; aix++) {
         if (aix != 0)
            querySB.append(", ");
         querySB.append("?");
      }
      querySB.append(")");

      if (!dbTypeDesc.dbDisabled) {
         Connection conn = null;
         ResultSet rs = null;
         PreparedStatement st = null;

         int col = 1;
         try {
            conn = transaction.getConnection(dbTypeDesc.getDataSource().jndiName);
            st = conn.prepareStatement(querySB.toString());
            for (int aix = 0; aix < paramValues.length; aix++) {
               Object paramType = paramTypes.get(aix);
               DBUtil.setStatementValue(st, col++, DBColumnType.fromJavaType(paramType), paramValues[aix]);
            }

            rs = st.executeQuery();

            ResultSetMetaData md = rs.getMetaData();
            int colCt = md.getColumnCount();
            /*
            ArrayList<String> colNames = new ArrayList<String>();
            for (int cc = 0; cc < colCt; cc++) {
               colNames.add(md.getColumnName(cc+1));
            }
            */

            int rowCt = 0;

            Object rowVal = null;

            List<Object> listRes = multiRow ? new ArrayList<Object>() : null;

            transaction.applyingDBChanges = true;

            while (rs.next()) {
               if (!multiRow && rowCt > 0)
                  throw new IllegalArgumentException("More than one result set for single valued query");
               if (DynUtil.isAssignableFrom(IDBObject.class, returnType)) {
                  DBTypeDescriptor resType = DBTypeDescriptor.getByType(returnType, true);
                  if (resType == null) {
                     throw new IllegalArgumentException("No DBTypeDescriptor registered for return type: " + DynUtil.getTypeName(returnType, false) + " for query: " + queryName);
                  }

                  List<IdPropertyDescriptor> idCols = resType.primaryTable.getIdColumns();
                  Object idVal;
                  int numCols = idCols.size();
                  if (numCols == 1) {
                     IdPropertyDescriptor idCol = idCols.get(0);
                     idVal = DBUtil.getResultSetByName(rs, idCol.columnName, idCol);
                  }
                  else {
                     MultiColIdentity idVals = new MultiColIdentity(numCols);
                     for (int ix = 0; ix < numCols; ix++) {
                        IdPropertyDescriptor idCol = idCols.get(ix);
                        idVals.setVal(DBUtil.getResultSetByName(rs, idCol.columnName, idCol), ix);
                     }
                     idVal = idVals;
                  }
                  IDBObject rowInst = resType.lookupInstById(idVal, true, false);
                  rowInst.getDBObject().setPrototype(false);

                  rowVal = rowInst;

                  for (int cix = 0; cix < colCt; cix++) {
                     String colName = md.getColumnName(cix+1);

                     DBPropertyDescriptor resProp = resType.getPropertyForColumn(colName);
                     if (resProp instanceof IdPropertyDescriptor)
                        continue;

                     Object propVal = resProp.getValueFromResultSetByName(rs, colName);

                     resProp.updateReferenceForPropValue(rowInst, propVal);

                     resProp.getPropertyMapper().setPropertyValue(rowInst, propVal);
                  }
               }
               else {
                  if (colCt > 1) {
                     // TODO: not sure entirely about this case - do we need to know the data types being returned?
                     HashMap<String,Object> rowMap = new HashMap<String,Object>();
                     for (int cix = 0; cix > colCt; cix++) {
                        String colName = md.getColumnName(cix);
                        rowMap.put(colName, rs.getObject(cix));
                     }
                     rowVal = rowMap;
                  }
                  else {
                     rowVal = DBUtil.getResultSetByIndex(rs, 1, returnType, DBColumnType.fromJavaType(returnType), null);
                  }
               }
               rowCt++;

               if (multiRow)
                  listRes.add(rowVal);
            }
            if (multiRow)
               return listRes;
            return rowVal;
         }
         catch (SQLException exc) {
            DBUtil.error("SQLException running named query: " + queryName + ": " + exc);
            throw new IllegalArgumentException("Invalid query: " + exc);
         }
         finally {
            transaction.applyingDBChanges = false;
            DBUtil.close(conn, st, rs);
         }
      }
      else {
         DBUtil.error("Named query: " + queryName + " not supported with dbDisabled=true");
         return null;
      }
   }
}
