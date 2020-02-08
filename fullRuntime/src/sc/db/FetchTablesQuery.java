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

/** Corresponds to a single property fetch query against a single data source - either for a single row or multi-row */
public class FetchTablesQuery {
   public String dataSourceName;
   public boolean multiRow;
   /** Does this query include the primary table - i.e. if the row does not exist, does it mean the parent item does not exist */
   public boolean includesPrimary;

   List<FetchTableDesc> fetchTables = new ArrayList<FetchTableDesc>();
   Map<String, FetchTableDesc> fetchTablesIndex = new TreeMap<String, FetchTableDesc>();

   public FetchTablesQuery(String dataSourceName, boolean multiRow) {
      this.dataSourceName = dataSourceName;
      this.multiRow = multiRow;
   }

   public void addProperty(TableDescriptor table, DBPropertyDescriptor prop) {
      String tableName = table.tableName;
      FetchTableDesc ftd;
      ftd = fetchTablesIndex.get(tableName);

      if (ftd == null) {
         ftd = new FetchTableDesc();
         ftd.table = table;
         ftd.props = new ArrayList<DBPropertyDescriptor>();

         fetchTables.add(ftd);
         fetchTablesIndex.put(tableName, ftd);

         if (table.primary)
            includesPrimary = true;
      }
      ftd.props.add(prop);

      // When the reference is not onDemand, add the primary+aux tables from the referenced type to the query for this property
      // so that we do one query to fetch the list of instances - rather than the 1 + N queries if we did them one-by-one
      DBTypeDescriptor refType = prop.refDBTypeDesc;
      if (!prop.onDemand && refType != null) {
         DBFetchGroupQuery defaultRefQuery = refType.getFetchQueryForProperty(refType.defaultFetchGroup);
         for (FetchTablesQuery defQuery:defaultRefQuery.queries) {
            if (!defQuery.multiRow) {
               for (FetchTableDesc defQueryFetch:defQuery.fetchTables) {
                  FetchTableDesc refQueryFetch = defQueryFetch.copyForRef(prop);
                  fetchTables.add(refQueryFetch);
                  fetchTablesIndex.put(refQueryFetch.table.tableName, refQueryFetch);
               }
            }
         }
      }
   }

   public boolean fetchProperties(DBTransaction transaction, DBObject dbObj) {
      TableDescriptor mainTable = fetchTables.get(0).table;
      Connection conn = transaction.getConnection(mainTable.getDataSourceName());
      StringBuilder qsb = buildTableFetchQuery(fetchTables);
      ResultSet rs = null;
      List<IdPropertyDescriptor> idColumns = mainTable.getIdColumns();
      try {
         PreparedStatement st = conn.prepareStatement(qsb.toString());
         Object inst = dbObj.getInst();
         for (int i = 0; i < idColumns.size(); i++) {
            DBPropertyDescriptor propDesc = idColumns.get(i);
            IBeanMapper propMapper = propDesc.getPropertyMapper();
            Object colVal = propMapper.getPropertyValue(inst, false, false);
            DBUtil.setStatementValue(st, i+1, propMapper.getPropertyType(), colVal);
         }

         rs = st.executeQuery();

         if (!multiRow)
            return processOneRowQueryResults(dbObj, inst, rs);
         else
            return processMultiResults(dbObj, inst, rs);
      }
      catch (SQLException exc) {
         throw new IllegalArgumentException("*** fetchProperties failed with SQL error: " + exc);
      }
      finally {
         if (rs != null)
            DBUtil.close(rs);
      }
   }

   boolean processOneRowQueryResults(DBObject dbObj, Object inst, ResultSet rs) throws SQLException {
      if (!rs.next()) {
         dbObj.setTransient(true);
         return false;
      }

      int rix = 1;
      for (FetchTableDesc ftd:fetchTables) {
         Object fetchInst = ftd.refProp == null ? inst : ftd.refProp.getPropertyMapper().getPropertyValue(inst, false, false);
         for (DBPropertyDescriptor propDesc:ftd.props) {
            IBeanMapper propMapper = propDesc.getPropertyMapper();
            Object val;
            int numCols = propDesc.getNumColumns();
            if (numCols == 1)  {
               val = DBUtil.getResultSetByIndex(rs, rix++, propDesc);
               if (propDesc.refDBTypeDesc != null)
                  val = propDesc.refDBTypeDesc.getById(val);
            }
            else {
               if (propDesc.refDBTypeDesc != null) {
                  List<IdPropertyDescriptor> refIdCols = propDesc.refDBTypeDesc.primaryTable.getIdColumns();
                  if (numCols != refIdCols.size())
                     throw new UnsupportedOperationException();
                  MultiColIdentity idVals = new MultiColIdentity(numCols);
                  for (int i = 0; i < numCols; i++) {
                     IdPropertyDescriptor refIdCol = refIdCols.get(i);
                     Object idVal = DBUtil.getResultSetByIndex(rs, rix++, refIdCol);
                     idVals.setVal(idVal, i);
                  }
                  val = propDesc.refDBTypeDesc.getById(idVals);
               }
               else {// TODO: is this a useful case? need some way here to create whatever value we have from the list of result set values
                  System.err.println("*** Unsupported case - multiCol property that's not a reference");
                  val = null;
               }
            }
            propMapper.setPropertyValue(fetchInst, val);
         }
      }
      if (rs.next())
         throw new IllegalArgumentException("Fetch query returns more than one row!");
      return true;
   }

   /**
    * Here the first fetchTable defines the list/array value - fetchTables.get(0).props.get(0).
    * The second and subsequent fetch tables are only there for onDemand=false references in the referenced object
    */
   boolean processMultiResults(DBObject dbObj, Object inst, ResultSet rs) throws SQLException {
      List<Object> resList = null;
      DBPropertyDescriptor listProp = null;
      while (rs.next()) {
         int rix = 1;
         Object currentRowVal = null;
         for (int fi = 0; fi < fetchTables.size(); fi++) {
            FetchTableDesc fetchTable = fetchTables.get(fi);

            for (DBPropertyDescriptor propDesc:fetchTable.props) {
               IBeanMapper propMapper = propDesc.getPropertyMapper();
               Object val;
               int numCols = propDesc.getNumColumns();
               if (numCols == 1)  {
                  val = DBUtil.getResultSetByIndex(rs, rix++, propDesc);
                  if (propDesc.refDBTypeDesc != null)
                     val = propDesc.refDBTypeDesc.getById(val);
               }
               else {
                  if (propDesc.refDBTypeDesc != null) {
                     List<IdPropertyDescriptor> refIdCols = propDesc.refDBTypeDesc.primaryTable.getIdColumns();
                     if (numCols != refIdCols.size())
                        throw new UnsupportedOperationException();
                     MultiColIdentity idVals = new MultiColIdentity(numCols);
                     for (int ci = 0; ci < numCols; ci++) {
                        IdPropertyDescriptor refIdCol = refIdCols.get(ci);
                        Object idVal = DBUtil.getResultSetByIndex(rs, rix++, refIdCol);
                        idVals.setVal(idVal, ci);
                     }
                     val = propDesc.refDBTypeDesc.getById(idVals);
                  }
                  else {// TODO: is this a useful case? need some way here to create whatever value we have from the list of result set values
                     System.err.println("*** Unsupported case - multiCol property that's not a reference");
                     val = null;
                  }
               }
               if (fi == 0) {
                  currentRowVal = val;
                  if (resList == null) {
                     resList = new ArrayList<Object>();
                     listProp = propDesc; // the first time through, the main property for this list
                  }
                  resList.add(currentRowVal);
               }
               else {
                  if (currentRowVal == null)
                     throw new UnsupportedOperationException("Multi value fetch tables - not attached to reference");
                  propMapper.setPropertyValue(currentRowVal, val);
               }
            }
         }
      }
      if (listProp != null) {
         // TODO: handle arrays, incremental update of existing destination list for incremental 'refresh' when the list is
         //  bound to a UI, handle other concrete classes for the list type and IBeanIndexMapper.
         listProp.getPropertyMapper().setPropertyValue(inst, resList);
      }
      return true;
   }

   private static StringBuilder buildTableFetchQuery(List<FetchTableDesc> fetchTables) {
      TableDescriptor mainTable = fetchTables.get(0).table;
      StringBuilder qsb = buildTableQueryBase(mainTable, fetchTables);
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

   private static StringBuilder buildTableQueryBase(TableDescriptor mainTable, List<FetchTableDesc> fetchTables) {
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

   private static void appendJoinTable(StringBuilder queryStr, TableDescriptor mainTable, TableDescriptor joinTable) {
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

   private static void appendTableSelect(StringBuilder queryStr, FetchTableDesc fetchTable, boolean addTablePrefix) {
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
