/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.db;

import sc.bind.BindingContext;
import sc.bind.IListener;
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
      StringBuilder logSB = DBUtil.verbose ? new StringBuilder() : null;
      querySB.append("SELECT * FROM ");
      querySB.append(dbQueryName);
      querySB.append("(");

      if (logSB != null)
         logSB.append(querySB);
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
         boolean origDBChanges = transaction.applyingDBChanges;
         BindingContext oldBindCtx = null;
         BindingContext ctx = null;
         try {
            conn = transaction.getConnection(dbTypeDesc.getDataSource().jndiName);
            st = conn.prepareStatement(querySB.toString());
            for (int aix = 0; aix < paramValues.length; aix++) {
               Object paramType = paramTypes.get(aix);
               DBColumnType colType = DBColumnType.fromJavaType(paramType);
               Object paramVal = paramValues[aix];
               DBUtil.setStatementValue(st, col++, colType, paramVal, paramType);
               if (logSB != null) {
                  DBUtil.appendVal(logSB, paramVal, colType, null);
               }
            }
            if (logSB != null) {
               logSB.append(") -> ");
               if (multiRow)
                  logSB.append("\n");
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
            ctx = new BindingContext(IListener.SyncType.QUEUE_VALIDATE_EVENTS);
            oldBindCtx = BindingContext.getBindingContext();
            BindingContext.setBindingContext(ctx);

            while (rs.next()) {
               if (!multiRow && rowCt > 0)
                  throw new IllegalArgumentException("More than one result set for single valued query");

               if (logSB != null && multiRow) {
                  logSB.append("   [");
                  logSB.append(rowCt);
                  logSB.append("]: ");
               }

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
                  // In Postgres, when a function query returns a single row, it returns an empty row rather than no rows
                  if (idVal == null)
                     break;

                  DBPropertyDescriptor typeIdProp = dbTypeDesc.getTypeIdProperty();
                  int typeId = -1;
                  if (typeIdProp != null) {
                     typeId = (int) DBUtil.getResultSetByName(rs, typeIdProp.columnName, typeIdProp);
                  }

                  IDBObject rowInst = resType.lookupInstById(idVal, typeId, true, false);
                  DBObject rowDBObj = (DBObject) rowInst.getDBObject();
                  rowDBObj.clearPrototype();

                  rowVal = rowInst;

                  if (logSB != null) {
                     logSB.append(rowDBObj);
                     logSB.append("(");
                  }

                  boolean first = true;
                  for (int cix = 0; cix < colCt; cix++) {
                     String colName = md.getColumnName(cix+1);

                     DBPropertyDescriptor resProp = resType.getPropertyForColumn(colName);
                     if (resProp instanceof IdPropertyDescriptor || resProp.typeIdProperty)
                        continue;

                     if (logSB != null && !first)
                        logSB.append(", ");

                     Object propVal = resProp.getValueFromResultSetByName(rs, colName);

                     transaction.updateSelectState = false;
                     resProp.updateReferenceForPropValue(rowInst, propVal);
                     transaction.updateSelectState = true;

                     resProp.getPropertyMapper().setPropertyValue(rowInst, propVal);

                     // Clear out the refId property once we have the value itself because otherwise we'll make an extra lookupId each time (since
                     // there's no null check in dbGetPropertyWithRefId and we would need to keep it in sync if we change them value.
                     if (resProp.getNeedsRefId() && propVal != null)
                        resProp.setRefIdProperty(rowInst, null);

                     if (logSB != null) {
                        logSB.append(colName);
                        logSB.append("=");
                        DBUtil.appendVal(logSB, propVal, resProp.getDBColumnType(), resProp.refDBTypeDesc);
                     }
                     first = false;
                  }
                  if (logSB != null) {
                     logSB.append(")");
                  }
               }
               else {
                  if (colCt > 1) {
                     // TODO: not sure entirely about this case - do we need to know the data types being returned?
                     HashMap<String,Object> rowMap = new HashMap<String,Object>();
                     for (int cix = 0; cix < colCt; cix++) {
                        String colName = md.getColumnName(cix);
                        rowMap.put(colName, rs.getObject(cix));
                     }
                     rowVal = rowMap;
                  }
                  else {
                     rowVal = DBUtil.getResultSetByIndex(rs, 1, returnType, DBColumnType.fromJavaType(returnType), null);
                  }

                  if (logSB != null)
                     DBUtil.appendVal(logSB, rowVal, DBColumnType.fromJavaType(returnType), null);
               }

               if (multiRow) {
                  listRes.add(rowVal);
               }

               rowCt++;
            }
            if (logSB != null)
               DBUtil.verbose(logSB);

            if (multiRow)
               return listRes;
            return rowVal;
         }
         catch (SQLException exc) {
            DBUtil.error("SQLException running named query: " + queryName + ": " + exc);
            throw new IllegalArgumentException("Invalid query: " + exc);
         }
         finally {
            transaction.applyingDBChanges = origDBChanges;
            if (!origDBChanges)
               transaction.doFetchLater();
            if (oldBindCtx != null) {
               BindingContext.setBindingContext(oldBindCtx);
               ctx.dispatchEvents(null);
            }
            DBUtil.close(null, st, rs);
         }
      }
      else {
         DBUtil.error("Named query: " + queryName + " not supported with dbDisabled=true");
         return null;
      }
   }
}
