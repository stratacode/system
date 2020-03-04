package sc.db;

import sc.type.IBeanMapper;
import sc.util.StringUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/** Corresponds to a single database query - either for a single row or multi-row but not both */
public class FetchTablesQuery {
   public String dataSourceName;
   public boolean multiRow;
   /** Does this query include the primary table - i.e. if the row does not exist, does it mean the parent item does not exist */
   public boolean includesPrimary;

   List<FetchTableDesc> fetchTables = new ArrayList<FetchTableDesc>();
   Map<String, FetchTableDesc> fetchTablesIndex = new TreeMap<String, FetchTableDesc>();

   /**
    * For queries that have a custom where clause, these properties provide the extra info required to make the query
    */
   public StringBuilder whereSB;
   public StringBuilder logSB;

   public int numWhereColumns = 0;
   public List<Object> paramValues;
   public List<DBColumnType> paramTypes;

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

      // For the one-to-many case, the table is just the reverse table
      if (table.reverseProperty != null) {
         if (prop != table.reverseProperty)
            System.err.println("*** Unrecognized case in setting up one-to-many multi case");
         ftd.revColumns = table.columns;
         ftd.revProps = new ArrayList<DBPropertyDescriptor>();
         for (int i = 0; i < table.columns.size(); i++) {
            ftd.revProps.add(prop);
         }
      }
      else {
         if (ftd.props.contains(prop))
            return;
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
   }

   public boolean containsProperty(DBPropertyDescriptor pdesc) {
      for (FetchTableDesc ftd:fetchTables) {
         if (ftd.containsProperty(pdesc))
            return true;
      }
      return false;
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
            DBColumnType colType = propDesc.getDBColumnType();
            DBUtil.setStatementValue(st, i+1, colType, colVal);
            if (logStr != null)
               logStr = DBUtil.replaceNextParam(logStr, colVal, colType);
         }

         rs = st.executeQuery();

         StringBuilder logSB = logStr != null ? new StringBuilder(logStr) : null;

         if (logSB != null)
            logSB.append(" -> ");

         transaction.applyingDBChanges = true;

         boolean res;
         if (!multiRow) // populate properties of dbObj from the tables in this query
            res = processOneRowQueryResults(dbObj, inst, rs, logSB);
         else // fetch a multi-valued property
            res = processMultiResults(null, dbObj, inst, rs, logSB);
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

   public List<IDBObject> query(DBTransaction transaction, DBObject proto) {
      TableDescriptor mainTable = fetchTables.get(0).table;
      Connection conn = transaction.getConnection(mainTable.getDataSourceName());
      StringBuilder qsb = buildTableQueryBase(mainTable, fetchTables);
      ResultSet rs = null;
      StringBuilder logSB = DBUtil.verbose ? new StringBuilder(qsb) : null;
      DBUtil.append(qsb, logSB, " WHERE ");
      qsb.append(whereSB);
      if (logSB != null)
         logSB.append(this.logSB);

      try {
         String queryStr = qsb.toString();
         PreparedStatement st = conn.prepareStatement(queryStr);
         Object inst = proto.getInst();
         for (int i = 0; i < paramValues.size(); i++) {
            Object paramValue = paramValues.get(i);
            DBColumnType propType = paramTypes.get(i);
            DBUtil.setStatementValue(st, i+1, propType, paramValue);
         }

         rs = st.executeQuery();

         if (logSB != null)
            logSB.append(" -> ");

         transaction.applyingDBChanges = true;

         ArrayList<IDBObject> res = new ArrayList<IDBObject>();
         if (!multiRow) {
            if (!processOneRowQueryResults(proto, inst, rs, logSB))
               return null;
            res.add(proto);
            return res;
         }
         else {
            res = new ArrayList<IDBObject>();
            processMultiResults(res, null, inst, rs, logSB);
         }
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

   public IDBObject queryOne(DBTransaction transaction, DBObject proto) {
      List<IDBObject> res = query(transaction, proto);
      if (res == null)
         return null;
      if (res.size() > 1)
         throw new IllegalArgumentException("Invalid return list for single query: " + res.size());
      return res.size() == 0 ? null : res.get(0);
   }

   boolean processOneRowQueryResults(DBObject dbObj, Object inst, ResultSet rs, StringBuilder logSB) throws SQLException {
      if (!rs.next()) {
         if (dbObj.isPrototype())
            return false;

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
               DBUtil.appendVal(logSB, val);
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
                  DBUtil.appendVal(logSB, val);
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
   boolean processMultiResults(List<IDBObject> resList, DBObject dbObj, Object inst, ResultSet rs, StringBuilder logSB) throws SQLException {
      DBPropertyDescriptor listProp = null;
      int rowCt = 0;

      while (rs.next()) {
         int rix = 1;
         IDBObject currentRowVal = null;

         if (logSB != null) {
            if (rowCt > 0) {
               logSB.append(",\n   ");
            }
            else
               logSB.append("\n   ");
         }

         for (int fi = 0; fi < fetchTables.size(); fi++) {
            FetchTableDesc fetchTable = fetchTables.get(fi);

            boolean rowValSet = false;
            for (DBPropertyDescriptor propDesc:fetchTable.props) {
               IBeanMapper propMapper = propDesc.getPropertyMapper();
               Object val;
               int numCols = propDesc.getNumColumns();
               DBTypeDescriptor colTypeDesc = propDesc.getColTypeDesc();
               if (numCols == 1)  {
                  val = DBUtil.getResultSetByIndex(rs, rix++, propDesc);
                  if (colTypeDesc != null) {
                     val = colTypeDesc.lookupInstById(val, true, false);

                     if (val != null) {
                        IDBObject valObj = (IDBObject) val;
                        DBObject valDBObj = valObj.getDBObject();
                        if (valDBObj.isPrototype()) {
                           // TODO: if this has a reverse property, do we want to update the other side - in this case, add it to the reverseProperty which is a list here
                           //if (propDesc.reversePropDesc != null)  {
                           //}
                           valDBObj.setPrototype(false);
                        }
                     }
                  }
               }
               else {
                  if (colTypeDesc != null) {
                     List<IdPropertyDescriptor> refIdCols = colTypeDesc.primaryTable.getIdColumns();
                     if (numCols != refIdCols.size())
                        throw new UnsupportedOperationException();
                     MultiColIdentity idVals = new MultiColIdentity(numCols);
                     for (int ci = 0; ci < numCols; ci++) {
                        IdPropertyDescriptor refIdCol = refIdCols.get(ci);
                        Object idVal = DBUtil.getResultSetByIndex(rs, rix++, refIdCol);
                        idVals.setVal(idVal, ci);
                     }
                     val = colTypeDesc.lookupInstById(idVals, true, false);

                     if (val != null) {
                        IDBObject valObj = (IDBObject) val;
                        DBObject valDBObj = valObj.getDBObject();
                        if (valDBObj.isPrototype()) {
                           // TODO: update the reverse property (a list at this point)?
                           //if (propDesc.reversePropDesc != null)
                           //   propDesc.reversePropDesc.getPropertyMapper().setPropertyValue(val, inst);
                           valDBObj.setPrototype(false);
                        }
                     }
                  }
                  else {// TODO: is this a useful case? need some way here to create whatever value we have from the list of result set values
                     System.err.println("*** Unsupported case - multiCol property that's not a reference");
                     val = null;
                  }
               }
               if (fi == 0 && !rowValSet) {
                  currentRowVal = (IDBObject) val;
                  rowValSet = true;
                  if (resList == null) {
                     resList = new DBList(10, dbObj, propDesc);
                     listProp = propDesc; // the first time through, the main property for this list
                  }
                  resList.add(currentRowVal);
                  if (logSB != null) {
                     logSB.append("[");
                     logSB.append(rowCt);
                     logSB.append("](");
                     logSB.append(currentRowVal == null ? null : currentRowVal.getDBObject());
                  }
                  rowCt++;
               }
               else {
                  Object propInst = fetchTable.refProp == null || listProp != null ? currentRowVal : fetchTable.refProp.getPropertyMapper().getPropertyValue(currentRowVal, false, false);
                  if (currentRowVal == null)
                     throw new UnsupportedOperationException("Multi value fetch tables - not attached to reference");
                  propMapper.setPropertyValue(propInst, val);

                  if (logSB != null) {
                     logSB.append(", ");
                     logSB.append(propMapper.getPropertyName());
                     logSB.append("=");
                     DBUtil.appendVal(logSB, val);
                  }
               }
            }
            if (fetchTable.revColumns != null) {
               DBPropertyDescriptor revProp = fetchTable.revProps.get(0);
               int numToFetch = fetchTable.revColumns.size();
               for (int rci = 0; rci < numToFetch; rci++) {
                  DBPropertyDescriptor propDesc = fetchTable.revColumns.get(rci);
                  IBeanMapper propMapper = propDesc.getPropertyMapper();
                  Object val;
                  int numCols = propDesc.getNumColumns();

                  // If this is the first fetchTable and the first column in the revProps list is the id of the reverse property instance
                  DBTypeDescriptor refTypeDesc = rci == 0 && fi == 0 ? revProp.refDBTypeDesc : propDesc.refDBTypeDesc;
                  if (numCols == 1)  {
                     val = DBUtil.getResultSetByIndex(rs, rix++, propDesc);

                     if (refTypeDesc != null) {
                        val = refTypeDesc.lookupInstById(val, true, false);

                        if (val != null) {
                           IDBObject valObj = (IDBObject) val;
                           DBObject valDBObj = valObj.getDBObject();
                           if (valDBObj.isPrototype()) {
                              // TODO: do we need to update the reverse property
                              //if (propDesc.reversePropDesc != null)
                              //   propDesc.reversePropDesc.getPropertyMapper().setPropertyValue(val, inst);
                              valDBObj.setPrototype(false);
                           }
                        }
                     }
                  }
                  else {
                     if (refTypeDesc != null) {
                        List<IdPropertyDescriptor> refIdCols = refTypeDesc.primaryTable.getIdColumns();
                        if (numCols != refIdCols.size())
                           throw new UnsupportedOperationException();
                        MultiColIdentity idVals = new MultiColIdentity(numCols);
                        for (int ci = 0; ci < numCols; ci++) {
                           IdPropertyDescriptor refIdCol = refIdCols.get(ci);
                           Object idVal = DBUtil.getResultSetByIndex(rs, rix++, refIdCol);
                           idVals.setVal(idVal, ci);
                        }
                        val = refTypeDesc.lookupInstById(idVals, true, false);

                        if (val != null) {
                           IDBObject valObj = (IDBObject) val;
                           DBObject valDBObj = valObj.getDBObject();
                           if (valDBObj.isPrototype()) {
                              //if (propDesc.reversePropDesc != null)
                              //   propDesc.reversePropDesc.getPropertyMapper().setPropertyValue(val, inst);
                              valDBObj.setPrototype(false);
                           }
                        }
                     }
                     else {// TODO: is this a useful case? need some way here to create whatever value we have from the list of result set values
                        System.err.println("*** Unsupported case - multiCol property that's not a reference");
                        val = null;
                     }
                  }
                  if (rci == 0 && fi == 0) {
                     currentRowVal = (IDBObject) val;
                     if (resList == null) {
                        resList = new DBList(10, dbObj, propDesc);
                        listProp = revProp; // For reverse properties, it's always the reverse prop - waiting for the first element to set the list
                     }
                     resList.add(currentRowVal);
                     if (logSB != null) {
                        logSB.append("[");
                        logSB.append(rowCt);
                        logSB.append("]:");
                        logSB.append(currentRowVal == null ? null : currentRowVal.getDBObject());
                        logSB.append("(");
                     }
                     rowCt++;
                  }
                  else {
                     if (currentRowVal == null)
                        throw new UnsupportedOperationException("Multi value fetch tables - not attached to reference");
                     propMapper.setPropertyValue(currentRowVal, val);

                     if (logSB != null) {
                        if (rci > 1)
                           logSB.append(", ");
                        logSB.append(propMapper.getPropertyName());
                        logSB.append("=");
                        DBUtil.appendVal(logSB, val);
                     }
                  }
               }
            }
            if (logSB != null)
               logSB.append(")");
         }
      }
      if (listProp != null) {
         // TODO: handle arrays, incremental update of existing destination list for incremental 'refresh' when the list is
         //  bound to a UI, handle other concrete classes for the list type and IBeanIndexMapper.
         listProp.getPropertyMapper().setPropertyValue(inst, resList);
         ((DBList) resList).trackingChanges = true;
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

   static List<? extends DBPropertyDescriptor> getJoinColumns(TableDescriptor mainTable, TableDescriptor joinTable) {
      if (mainTable.dbTypeDesc == joinTable.dbTypeDesc) {
         return mainTable.getIdColumns();
      }
      else {
         for (DBPropertyDescriptor col:mainTable.columns) {
            if (col.refDBTypeDesc == joinTable.dbTypeDesc)
               return Collections.singletonList(col);
         }
      }
      throw new UnsupportedOperationException();
   }

   private static void appendJoinTable(StringBuilder queryStr, TableDescriptor mainTable, TableDescriptor joinTable) {
      List<? extends DBPropertyDescriptor> mainJoinCols = getJoinColumns(mainTable, joinTable);
      int sz = mainJoinCols.size();
      for (int i = 0; i < sz; i++) {
         if (i != 0)
            queryStr.append(" AND ");
         DBUtil.appendIdent(queryStr, null, mainTable.tableName);
         queryStr.append(".");
         //DBUtil.appendIdent(queryStr, null, mainIdCols.get(i).columnName);
         //DBUtil.appendIdent(queryStr, null, mainTable.columns.get(i).columnName);
         DBUtil.appendIdent(queryStr, null, mainJoinCols.get(i).columnName);
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
      if (fetchTables != null) {
         for (int i = 0; i < fetchTables.size(); i++) {
            FetchTableDesc fetchTable = fetchTables.get(i);
            if (i != 0)
               sb.append(", ");
            sb.append(fetchTable);
         }
      }
      if (whereSB != null) {
         sb.append(" WHERE ");
         sb.append(whereSB);
      }
      if (paramValues != null) {
         sb.append(" (");
         sb.append(StringUtil.arrayToString(paramValues.toArray()));
         sb.append(" )");
      }
      if (multiRow)
         sb.append(" (multiRow)");
      return sb.toString();
   }

   public FetchTablesQuery clone() {
      FetchTablesQuery res = new FetchTablesQuery(dataSourceName, multiRow);
      res.includesPrimary = includesPrimary;
      for (FetchTableDesc ftd:fetchTables) {
         FetchTableDesc nftd = ftd.clone();
         res.fetchTables.add(nftd);
         res.fetchTablesIndex.put(ftd.table.tableName, ftd);
      }
      return res;
   }

   public void appendWhereColumn(String tableName, String colName) {
      initWhereQuery();
      numWhereColumns++;
      if (fetchTables.size() > 1) {
         whereAppendIdent(tableName);
         DBUtil.append(whereSB, null, ".");
      }
      DBUtil.appendIdent(whereSB, null, colName);
   }

   public void appendJSONWhereColumn(String tableName, String colName, String propPath) {
      initWhereQuery();
      numWhereColumns++;
      if (fetchTables.size() > 1) {
         whereAppendIdent(tableName);
         DBUtil.append(whereSB, null, ".");
      }
      DBUtil.appendIdent(whereSB, null, colName);
      whereSB.append("->>");
      whereSB.append("'");
      whereSB.append(propPath);
      whereSB.append("'");
   }

   public void appendJSONLogWhereColumn(StringBuilder logSB, String tableName, String colName, String propPath) {
      initWhereQuery();
      if (fetchTables.size() > 1) {
         DBUtil.appendIdent(logSB, null, tableName);
         DBUtil.append(logSB, null, ".");
      }
      DBUtil.appendIdent(logSB, null, colName);
      logSB.append("->>");
      logSB.append("'");
      logSB.append(propPath);
      logSB.append("'");
   }

   public void appendLogWhereColumn(StringBuilder logSB, String tableName, String colName) {
      initWhereQuery();
      if (logSB == null)
         return;
      if (fetchTables.size() > 1) {
         DBUtil.appendIdent(logSB, null, tableName);
         logSB.append(".");
      }
      DBUtil.appendIdent(logSB, null, colName);
   }

   private void initWhereQuery() {
      if (whereSB == null) {
         whereSB = new StringBuilder();
         logSB = DBUtil.verbose ? new StringBuilder() : null;
         paramValues = new ArrayList<Object>();
         paramTypes = new ArrayList<DBColumnType>();
      }
   }

   public void whereAppend(String s) {
      initWhereQuery();
      whereSB.append(s);
   }

   public void whereAppendIdent(String ident) {
      initWhereQuery();
      DBUtil.appendIdent(whereSB, null, ident);
   }

   public void insertIdProperty() {
      FetchTableDesc mainTableFetch = fetchTables.get(0);
      List<IdPropertyDescriptor> idCols = mainTableFetch.table.getIdColumns();
      int idSz = idCols.size();
      for (int i = idSz - 1; i >= 0; i--) {
         IdPropertyDescriptor idCol = idCols.get(i);
         if (!mainTableFetch.props.contains(idCol))
            mainTableFetch.props.add(0, idCol);
      }
   }
}
