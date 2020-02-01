package sc.db;

import sc.type.IBeanMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

class DBFetchGroupQuery extends DBQuery {
   DBTypeDescriptor dbTypeDesc;
   String fetchGroup;
   List<FetchTableDesc> fetchTables = new ArrayList<FetchTableDesc>();
   Map<String,FetchTableDesc> fetchTablesIndex = new TreeMap<String,FetchTableDesc>();

   static class FetchTableDesc {
      TableDescriptor table;
      List<DBPropertyDescriptor> props;
   }

   public void addProperty(DBPropertyDescriptor prop) {
      TableDescriptor table = dbTypeDesc.getTableForProp(prop);

      String tableName = table.tableName;
      FetchTableDesc ftd = fetchTablesIndex.get(tableName);
      if (ftd == null) {
         ftd = new FetchTableDesc();
         ftd.table = table;
         ftd.props = new ArrayList<DBPropertyDescriptor>();

         fetchTables.add(ftd);
         fetchTablesIndex.put(tableName, ftd);
      }
      ftd.props.add(prop);
   }

   public void fetchProperties(DBTransaction transaction, DBObject dbObj) {
      TableDescriptor mainTable = fetchTables.get(0).table;
      Connection conn = transaction.getConnection(mainTable.getDataSourceName());
      StringBuilder qsb = buildTableFetchQuery(mainTable);
      ResultSet rs = null;
      List<IdPropertyDescriptor> idColumns = mainTable.getIdColumns();
      try {
         PreparedStatement st = conn.prepareStatement(qsb.toString());
         ArrayList<Object> idVals = new ArrayList<Object>();
         Object inst = dbObj.getInst();
         for (int i = 0; i < idColumns.size(); i++) {
            DBPropertyDescriptor propDesc = idColumns.get(i);
            IBeanMapper propMapper = propDesc.getPropertyMapper();
            Object colVal = propMapper.getPropertyValue(inst, false, false);
            DBUtil.setStatementValue(st, i+1, propMapper, colVal);
            idVals.add(colVal);
         }

         rs = st.executeQuery();

         if (!rs.next()) {
            System.err.println("*** fetchProperties - failed to find persistent object of type: " + dbObj.dbTypeDesc + " with id: " + idVals);
         }

         int rix = 1;
         for (FetchTableDesc ftd:fetchTables) {
            for (DBPropertyDescriptor propDesc:ftd.props) {
               Object val = rs.getObject(rix++);
               IBeanMapper propMapper = propDesc.getPropertyMapper();
               propMapper.setPropertyValue(inst, val);
            }
         }

      }
      catch (SQLException exc) {
         throw new IllegalArgumentException("*** fetchProperties failed with SQL error: " + exc);
      }
      finally {
         if (rs != null)
            DBUtil.close(rs);
      }
   }

   private StringBuilder buildTableFetchQuery(TableDescriptor mainTable) {
      StringBuilder qsb = buildTableQueryBase(mainTable);
      qsb.append(" WHERE ");
      List<IdPropertyDescriptor> idCols = mainTable.getIdColumns();
      int sz = idCols.size();
      for (int i = 0; i < sz; i++) {
         if (i != 0)
            qsb.append(" AND ");
         DBUtil.appendIdent(qsb, mainTable.tableName);
         qsb.append(".");
         DBUtil.appendIdent(qsb, idCols.get(i).columnName);
         qsb.append(" = ?");
      }
      return qsb;
   }

   private StringBuilder buildTableQueryBase(TableDescriptor mainTable) {
      StringBuilder res = new StringBuilder();
      boolean hasAuxTables = fetchTables.size() > 1;
      res.append("SELECT ");
      for (FetchTableDesc fetchTable:fetchTables)
         appendTableSelect(res, fetchTable, hasAuxTables);
      res.append(" FROM ");
      DBUtil.appendIdent(res, mainTable.tableName);
      for (int i = 1; i < fetchTables.size(); i++) {
         res.append(" LEFT OUTER JOIN ");
         TableDescriptor joinTable = fetchTables.get(i).table;
         DBUtil.appendIdent(res, joinTable.tableName);
         res.append(" ON ");
         appendJoinTable(res, mainTable, joinTable);
      }
      return res;
   }

   private void appendJoinTable(StringBuilder queryStr, TableDescriptor mainTable, TableDescriptor joinTable) {
      List<IdPropertyDescriptor> idCols = mainTable.getIdColumns();
      int sz = idCols.size();
      for (int i = 0; i < sz; i++) {
         if (i != 0)
            queryStr.append(" AND ");
         DBUtil.appendIdent(queryStr, mainTable.tableName);
         queryStr.append(".");
         DBUtil.appendIdent(queryStr, idCols.get(i).columnName);
         queryStr.append(" = ");
         DBUtil.appendIdent(queryStr, joinTable.tableName);
         queryStr.append(".");
         DBUtil.appendIdent(queryStr, idCols.get(i).columnName);
      }
   }

   private void appendTableSelect(StringBuilder queryStr, FetchTableDesc fetchTable, boolean addTablePrefix) {
      String prefix = addTablePrefix ? fetchTable.table.tableName + "." : null;
      boolean first = true;
      for (DBPropertyDescriptor prop:fetchTable.props) {
         if (first)
            first = false;
         else
            queryStr.append(", ");
         if (prefix != null)
            queryStr.append(prefix);
         queryStr.append(prop.columnName);
      }
   }
}
