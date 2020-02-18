package sc.db;

import sc.type.IBeanMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

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
         // For the read-only side of the reverse relationship, the property's table is the primary table of the other
         // side so add the additional properties to define the other side to the properties we fetch with this table
         if (prop.reversePropDesc != null) {
            TableDescriptor revTable = prop.reversePropDesc.dbTypeDesc.primaryTable;
            if (!revTable.tableName.equals(tableName)) {
               ftd = fetchTablesIndex.get(revTable.tableName);
               if (ftd == null) {
                  ftd = new FetchTableDesc();
                  ftd.table = revTable;
                  ftd.props = Collections.emptyList();
                  ftd.revProps = new ArrayList<DBPropertyDescriptor>();
                  ftd.revColumns = new ArrayList<DBPropertyDescriptor>(revTable.columns);
                  for (int i = 0; i < revTable.columns.size(); i++) {
                     ftd.revProps.add(prop);
                  }
                  fetchTables.add(ftd);
                  fetchTablesIndex.put(tableName, ftd);
               }
               else // TODO: do we add to the column set here?
                  System.err.println("*** Table fetch table of reverse table already exists");
            }
            else {
               for (DBPropertyDescriptor revCol:revTable.columns) {
                  if (table.hasColumn(revCol))
                     continue;
                  if (ftd.revColumns == null) {
                     ftd.revColumns = new ArrayList<DBPropertyDescriptor>();
                     ftd.revProps = new ArrayList<DBPropertyDescriptor>();
                  }
                  ftd.revColumns.add(revCol);
                  ftd.revProps.add(prop);
               }
            }
         }
         else { // TODO: is this right - a completely separate 1-1 query we just tack onto the current one by adding more join tables?
            refType.initFetchGroups();
            DBFetchGroupQuery defaultRefQuery = refType.getDefaultFetchQuery();
            for (FetchTablesQuery defQuery:defaultRefQuery.queries) {
               if (!defQuery.multiRow) {
                  for (FetchTableDesc defQueryFetch:defQuery.fetchTables) {
                     // Skip any references back to the table of the property
                     // TODO: should this be a comparison against the primary table of the type of the property?
                     if (defQueryFetch.table.tableName.equalsIgnoreCase(table.tableName))
                        continue;
                     FetchTableDesc refQueryFetch = defQueryFetch.copyForRef(prop);
                     fetchTables.add(refQueryFetch);
                     fetchTablesIndex.put(refQueryFetch.table.tableName, refQueryFetch);
                  }
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
         String queryStr = qsb.toString();
         PreparedStatement st = conn.prepareStatement(queryStr);
         String logStr = DBUtil.verbose ? queryStr : null;
         Object inst = dbObj.getInst();
         for (int i = 0; i < idColumns.size(); i++) {
            DBPropertyDescriptor propDesc = idColumns.get(i);
            IBeanMapper propMapper = propDesc.getPropertyMapper();
            Object colVal = propMapper.getPropertyValue(inst, false, false);
            DBUtil.setStatementValue(st, i+1, propMapper.getPropertyType(), colVal);
            if (logStr != null)
               logStr = DBUtil.replaceNextParam(logStr, colVal);
         }

         rs = st.executeQuery();

         StringBuilder logSB = logStr != null ? new StringBuilder(logStr) : null;

         if (logSB != null)
            logSB.append(" -> ");

         transaction.applyingDBChanges = true;

         boolean res;
         if (!multiRow)
            res = processOneRowQueryResults(dbObj, inst, rs, logSB);
         else
            res = processMultiResults(dbObj, inst, rs, logSB);
         if (logSB != null) {
            DBUtil.info(logSB.toString());
         }
         return res;
      }
      catch (SQLException exc) {
         throw new IllegalArgumentException("*** fetchProperties failed with SQL error: " + exc);
      }
      finally {
         transaction.applyingDBChanges = false;
         if (rs != null)
            DBUtil.close(rs);
      }
   }

   boolean processOneRowQueryResults(DBObject dbObj, Object inst, ResultSet rs, StringBuilder logSB) throws SQLException {
      if (!rs.next()) {
         dbObj.setTransient(true);
         return false;
      }

      int rix = 1;
      for (FetchTableDesc ftd:fetchTables) {
         Object fetchInst = ftd.refProp == null ? inst : ftd.refProp.getPropertyMapper().getPropertyValue(inst, false, false);
         for (DBPropertyDescriptor propDesc:ftd.props) {
            if (logSB != null && rix != 1)
               logSB.append(", ");
            IBeanMapper propMapper = propDesc.getPropertyMapper();

            if (logSB != null) {
               logSB.append(propMapper.getPropertyName());
               logSB.append("=");
            }

            Object val = propDesc.getValueFromResultSet(rs, rix);
            rix += propDesc.getNumColumns();

            if (propDesc.refDBTypeDesc != null && val != null) {
               if (!(val instanceof IDBObject))
                  throw new IllegalArgumentException("Invalid return from get value for reference");
               DBObject refDBObj = ((IDBObject) val).getDBObject();
               if (refDBObj.isPrototype()) {
                  // Fill in the reverse property
                  if (propDesc.reversePropDesc != null) {
                     propDesc.reversePropDesc.getPropertyMapper().setPropertyValue(val, inst);
                  }
                  // Because we have stored a reference and there's an integrity constraint, we're going to assume the
                  // reference refers to a persistent object.
                  // TODO: should there be an option to validate the reference here - specifically if it's defined in a
                  // different data store?
                  refDBObj.setPrototype(false);
               }
            }
            propMapper.setPropertyValue(fetchInst, val);

            if (logSB != null) {
               logSB.append(val);
            }
         }
         if (ftd.revColumns != null) {
            for (int ri = 0; ri < ftd.revColumns.size(); ri++) {
              DBPropertyDescriptor propDesc = ftd.revColumns.get(ri);
               if (logSB != null && rix != 1)
                  logSB.append(", ");
               IBeanMapper propMapper = propDesc.getPropertyMapper();

               if (logSB != null) {
                  logSB.append(propMapper.getPropertyName());
                  logSB.append("=");
               }

               Object val = propDesc.getValueFromResultSet(rs, rix);
               rix += propDesc.getNumColumns();

               Object revInst = ftd.revProps.get(ri).getPropertyMapper().getPropertyValue(fetchInst, false, false);
               if (revInst != null) {
                  propDesc.getPropertyMapper().setPropertyValue(revInst, val);
               }
               else if (val != null)
                  System.err.println("*** Error - value for reverse reference property where there's no reverse instance");

               if (logSB != null) {
                  logSB.append(val);
               }
            }
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
   boolean processMultiResults(DBObject dbObj, Object inst, ResultSet rs, StringBuilder logSB) throws SQLException {
      DBList<IDBObject> resList = null;
      DBPropertyDescriptor listProp = null;
      int rowCt = 0;

      while (rs.next()) {
         int rix = 1;
         IDBObject currentRowVal = null;

         if (rowCt > 0) {
            logSB.append(",\n   ");
         }

         for (int fi = 0; fi < fetchTables.size(); fi++) {
            FetchTableDesc fetchTable = fetchTables.get(fi);

            for (DBPropertyDescriptor propDesc:fetchTable.props) {
               IBeanMapper propMapper = propDesc.getPropertyMapper();
               Object val;
               int numCols = propDesc.getNumColumns();
               if (numCols == 1)  {
                  val = DBUtil.getResultSetByIndex(rs, rix++, propDesc);
                  if (propDesc.refDBTypeDesc != null)
                     val = propDesc.refDBTypeDesc.lookupInstById(val, true, false);
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
                     val = propDesc.refDBTypeDesc.lookupInstById(idVals, true, false);
                  }
                  else {// TODO: is this a useful case? need some way here to create whatever value we have from the list of result set values
                     System.err.println("*** Unsupported case - multiCol property that's not a reference");
                     val = null;
                  }
               }
               if (fi == 0) {
                  currentRowVal = (IDBObject) val;
                  if (resList == null) {
                     resList = new DBList(10, dbObj, propDesc);
                     listProp = propDesc; // the first time through, the main property for this list
                  }
                  resList.add(currentRowVal);
                  if (logSB != null) {
                     logSB.append("[");
                     logSB.append(rowCt);
                     logSB.append("]: ");
                     logSB.append(currentRowVal);
                  }
                  rowCt++;
               }
               else {
                  if (currentRowVal == null)
                     throw new UnsupportedOperationException("Multi value fetch tables - not attached to reference");
                  propMapper.setPropertyValue(currentRowVal, val);

                  if (logSB != null) {
                     logSB.append(", ");
                     logSB.append(propMapper.getPropertyName());
                     logSB.append("=");
                     logSB.append(val);
                  }
               }
            }
         }
      }
      if (listProp != null) {
         // TODO: handle arrays, incremental update of existing destination list for incremental 'refresh' when the list is
         //  bound to a UI, handle other concrete classes for the list type and IBeanIndexMapper.
         listProp.getPropertyMapper().setPropertyValue(inst, resList);
         resList.trackingChanges = true;
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
         DBUtil.appendIdent(qsb, null, mainTable.tableName);
         qsb.append(".");
         DBUtil.appendIdent(qsb, null, idCols.get(i).columnName);
         qsb.append(" = ?");
      }
      return qsb;
   }

   private static StringBuilder buildTableQueryBase(TableDescriptor mainTable, List<FetchTableDesc> fetchTables) {
      StringBuilder res = new StringBuilder();
      boolean hasAuxTables = fetchTables.size() > 1;
      res.append("SELECT ");
      for (int i = 0; i < fetchTables.size(); i++) {
         if (i != 0)
            res.append(", ");
         FetchTableDesc fetchTable = fetchTables.get(i);
         appendTableSelect(res, fetchTable, hasAuxTables);
      }
      res.append(" FROM ");
      DBUtil.appendIdent(res, null, mainTable.tableName);
      for (int i = 1; i < fetchTables.size(); i++) {
         res.append(" LEFT OUTER JOIN ");
         TableDescriptor joinTable = fetchTables.get(i).table;
         DBUtil.appendIdent(res, null, joinTable.tableName);
         res.append(" ON ");
         appendJoinTable(res, mainTable, joinTable);
      }
      return res;
   }

   private static void appendJoinTable(StringBuilder queryStr, TableDescriptor mainTable, TableDescriptor joinTable) {
      List<IdPropertyDescriptor> mainIdCols = mainTable.getIdColumns();
      int sz = mainIdCols.size();
      for (int i = 0; i < sz; i++) {
         if (i != 0)
            queryStr.append(" AND ");
         DBUtil.appendIdent(queryStr, null, mainTable.tableName);
         queryStr.append(".");
         DBUtil.appendIdent(queryStr, null, mainIdCols.get(i).columnName);
         queryStr.append(" = ");
         DBUtil.appendIdent(queryStr, null, joinTable.tableName);
         queryStr.append(".");
         DBUtil.appendIdent(queryStr, null, joinTable.idColumns.get(i).columnName);
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
      if (fetchTable.revColumns != null) {
         for (DBPropertyDescriptor prop:fetchTable.revColumns) {
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

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("select ");
      if (fetchTables != null)
         sb.append(fetchTables);
      if (multiRow)
         sb.append(" - multi");
      return sb.toString();
   }
}
