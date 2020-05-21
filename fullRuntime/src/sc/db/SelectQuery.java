package sc.db;

import sc.dyn.DynUtil;
import sc.type.IBeanMapper;
import sc.util.JSON;
import sc.util.ResultWrapper;
import sc.util.StringUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/** Corresponds to a single database query - either for a single row or multi-row but not both */
public class SelectQuery implements Cloneable {
   DBTypeDescriptor dbTypeDesc;
   public String dataSourceName;
   public boolean multiRow;
   /** Does this query include the primary table - i.e. if the row does not exist, does it mean the parent item does not exist */
   public boolean includesPrimary;

   List<SelectTableDesc> selectTables = new ArrayList<SelectTableDesc>();
   /**
    * The list of references merged into this one query (onDemand=false) for 1-1. Eventually each property here turns into an extra
    * SelectTableDesc in the selectTables list.
    */
   List<DBPropertyDescriptor> refProps = null;

   List<DBPropertyDescriptor> orderByProps = null;
   List<Boolean> orderByDirs = null;

   /**
    * For queries that have a custom where clause, these properties provide the extra info required to make the query
    */
   public StringBuilder whereSB;
   public StringBuilder logSB;

   public StringBuilder orderBySB;

   public List<String> propNames;
   public int numWhereColumns = 0;
   public List<Object> paramValues;
   public List<DBColumnType> paramTypes;

   public int startIndex = 0;
   public int maxResults = 0; // unlimited

   private boolean activated = false;

   public SelectQuery(String dataSourceName, DBTypeDescriptor dbTypeDesc, boolean multiRow) {
      this.dataSourceName = dataSourceName;
      this.dbTypeDesc = dbTypeDesc;
      this.multiRow = multiRow;
   }

   public void addProperty(DBPropertyDescriptor curRefProp, DBPropertyDescriptor prop) {
      TableDescriptor table = prop.getTable();
      String tableName = table.tableName;
      SelectTableDesc ftd;
      ftd = getSelectTableForProperty(curRefProp, prop);

      if (ftd == null) {
         ftd = new SelectTableDesc();
         ftd.table = table;
         ftd.props = new ArrayList<DBPropertyDescriptor>();

         addTableToSelectQuery(ftd, null);

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
         if (prop.dynColumn)
            ftd.selectDynColumn = true;
         ftd.props.add(prop);

         SelectTableDesc revTableDesc = null;
         // When the reference is not onDemand, add the primary+aux tables from the referenced type to the query for this property
         // so that we do one query to select the list of instances - rather than the 1 + N queries if we did them one-by-one
         DBTypeDescriptor refType = prop.refDBTypeDesc;
         if (!prop.onDemand && refType != null) {
            // For the read-only side of the reverse relationship, the property's table is the primary table of the other
            // side so add the additional properties to define the other side to the properties we select with this table
            if (prop.reversePropDesc != null && prop.readOnly) {
               TableDescriptor revTable = prop.reversePropDesc.dbTypeDesc.primaryTable;
               if (!revTable.tableName.equals(tableName)) {
                  revTableDesc = getSelectTableForRevProperty(prop.reversePropDesc);
                  if (revTableDesc == null) {
                     revTableDesc = new SelectTableDesc();
                     revTableDesc.table = revTable;
                     revTableDesc.props = Collections.emptyList();
                     revTableDesc.revProps = new ArrayList<DBPropertyDescriptor>();
                     revTableDesc.revColumns = new ArrayList<DBPropertyDescriptor>(revTable.columns);
                     for (int i = 0; i < revTable.columns.size(); i++) {
                        if (prop.dynColumn)
                           revTableDesc.selectDynColumn = true;
                        revTableDesc.revProps.add(prop);
                     }
                     addTableToSelectQuery(revTableDesc, ftd);
                  }
                  else // TODO: do we add to the column set here?
                     System.err.println("*** Table select table of reverse table already exists");
               }
               else { // TODO: Is this right? Isn't this a case where we need to join in a new instance of this table against itself?
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
            else {
               if (refProps == null)
                  refProps = new ArrayList<DBPropertyDescriptor>();
               refProps.add(prop);
            }
         }
      }
   }

   private SelectTableDesc getSelectTableForRevProperty(DBPropertyDescriptor revProp) {
      for (SelectTableDesc selectTable:selectTables) {
         if (selectTable.revProps != null)
            if (selectTable.revProps.contains(revProp))
               return selectTable;
      }
      return null;
   }

   public void activate() {
      if (activated)
         return;
      activated = true;

      if (refProps == null || refProps.size() == 0)
         return;
      List<DBPropertyDescriptor> curRefProps = refProps;
      refProps = null;

      SelectTableDesc mainTable = selectTables.get(0);

      for (DBPropertyDescriptor refProp:curRefProps) {
         DBTypeDescriptor refType = refProp.refDBTypeDesc;
         // TODO: add a way to configure how we determine what to select for each the relationship - e.g. just get the id/db_type_id, just get the primary table,
         // pull in a sub-graph some number of levels deep via a graph-style query.
         SelectGroupQuery defaultRefQuery = refType.getDefaultFetchQuery();
         List<SelectTableDesc> toAddToThis = null;
         if (defaultRefQuery != null && defaultRefQuery.queries != null) {
            for (SelectQuery defQuery:defaultRefQuery.queries) {
               if (!defQuery.multiRow) {
                  if (getRefQueryForProperty(refProp) == null) {
                     for (SelectTableDesc defQueryFetch:defQuery.selectTables) {
                        if (defQueryFetch.refProp != null)
                           continue;
                        SelectTableDesc refQueryFetch = defQueryFetch.copyForRef(refProp);
                        if (defQuery == this) {
                           if (toAddToThis == null)
                              toAddToThis = new ArrayList<SelectTableDesc>();
                           toAddToThis.add(refQueryFetch);
                        }
                        else
                           addTableToSelectQuery(refQueryFetch, mainTable);
                     }
                  }
                  if (defQuery.refProps != null && !defQuery.activated) {
                     for (DBPropertyDescriptor nestedRefProp:defQuery.refProps) {
                        if (nestedRefProp == refProp)
                           continue;
                        if (getRefQueryForProperty(nestedRefProp) == null) {
                           DBTypeDescriptor nestedRefType = nestedRefProp.refDBTypeDesc;
                           SelectGroupQuery nestedRefGroupQuery = nestedRefType.getDefaultFetchQuery();
                           for (SelectQuery nestedQuery:nestedRefGroupQuery.queries) {
                              for (SelectTableDesc nestedDef:nestedQuery.selectTables) {
                                 SelectTableDesc refQueryFetch = nestedDef.copyForRef(nestedRefProp);
                                 if (nestedQuery == this) {
                                    if (toAddToThis == null)
                                       toAddToThis = new ArrayList<SelectTableDesc>();
                                    toAddToThis.add(refQueryFetch);
                                 }
                                 else
                                    addTableToSelectQuery(refQueryFetch, mainTable);
                              }
                           }
                        }
                     }
                  }
               }
               if (toAddToThis != null) {
                  for (SelectTableDesc nextToAdd:toAddToThis) {
                     if (getRefQueryForProperty(nextToAdd.refProp) == null)
                        addTableToSelectQuery(nextToAdd, mainTable);
                  }
                  toAddToThis = null;
               }
            }
         }
      }
   }

   SelectTableDesc getSelectTableForProperty(DBPropertyDescriptor curRefProp, DBPropertyDescriptor prop) {
      String tableName = prop.getTable().tableName;
      return getSelectTable(curRefProp, tableName);
   }

   SelectTableDesc getSelectTable(DBPropertyDescriptor curRefProp, String tableName) {
      for (SelectTableDesc selectQuery: selectTables) {
         if (selectQuery.refProp == curRefProp && selectQuery.table.tableName.equals(tableName))
            return selectQuery;
      }
      return null;
   }

   SelectTableDesc getRefQueryForProperty(DBPropertyDescriptor prop) {
      for (SelectTableDesc selectQuery: selectTables)
         if (selectQuery.refProp == prop)
            return selectQuery;
      return null;
   }

   void addTableToSelectQuery(SelectTableDesc newDesc, SelectTableDesc joinedFrom) {
      if (newDesc.refProp != null) {
         newDesc.alias = newDesc.table.tableName + "_" + newDesc.refProp.columnName;
      }
      else {
         for (int i = 0; i < selectTables.size(); i++) {
            SelectTableDesc selTable = selectTables.get(i);
            if (selTable.table.tableName.equals(newDesc.table.tableName)) {
               newDesc.alias = newDesc.table.tableName + "_" + i;
               break;
            }
         }
      }
      if (joinedFrom != null) {
         newDesc.joinedFrom = joinedFrom;
         if (joinedFrom.joinedTo == null)
            joinedFrom.joinedTo = new ArrayList<SelectTableDesc>();
         joinedFrom.joinedTo.add(newDesc);
      }
      selectTables.add(newDesc);
   }

   public boolean containsProperty(DBPropertyDescriptor pdesc) {
      for (SelectTableDesc ftd: selectTables) {
         if (ftd.containsProperty(pdesc))
            return true;
      }
      return false;
   }

   public boolean selectProperties(DBTransaction transaction, DBObject dbObj) {
      TableDescriptor mainTable = selectTables.get(0).table;
      StringBuilder qsb = buildTableFetchQuery(selectTables);
      ResultSet rs = null;
      List<IdPropertyDescriptor> idColumns = mainTable.getIdColumns();
      StringBuilder logSB = null;
      try {
         String queryStr = qsb.toString();
         boolean res;
         if (!mainTable.dbTypeDesc.dbDisabled) {
            Connection conn = transaction.getConnection(mainTable.getDataSourceName());
            PreparedStatement st = conn.prepareStatement(queryStr);
            String logStr = DBUtil.verbose ? queryStr : null;
            IDBObject inst = dbObj.getInst();
            for (int i = 0; i < idColumns.size(); i++) {
               DBPropertyDescriptor propDesc = idColumns.get(i);
               Object colVal;
               if (dbObj.unknownType)
                  colVal = dbObj.dbId;
               else {
                  IBeanMapper propMapper = propDesc.getPropertyMapper();
                  colVal = propMapper.getPropertyValue(inst, false, false);
               }
               DBColumnType colType = propDesc.getDBColumnType();
               DBUtil.setStatementValue(st, i+1, colType, colVal);
               if (logStr != null)
                  logStr = DBUtil.replaceNextParam(logStr, colVal, DBColumnType.LongId, mainTable.dbTypeDesc);
            }

            rs = st.executeQuery();

            logSB = logStr != null ? new StringBuilder(logStr) : null;

            if (logSB != null)
               logSB.append(" -> ");

            transaction.applyingDBChanges = true;

            if (!multiRow) {
               // populate properties of dbObj from the tables in this query, or return null or return a sub-type of dbObj
               IDBObject newInst = processOneRowQueryResults(dbObj, inst, rs, logSB);
               res = newInst != null;
               // NOTE: newInst here might not be inst but should be dbObj.wrapper - this happens when inst is a generic DBObject
               // prototype that we create in place of an abstract class that gets refined once we learn the type
            }
            else // select a multi-valued property
               res = processMultiResults(null, dbObj, inst, rs, logSB);

            if (logSB != null) {
               DBUtil.info(logSB.toString());
            }
         }
         else
            res = true;
         return res;
      }
      catch (SQLException exc) {
         if (logSB != null)
            DBUtil.error("FetchProperties for " + dbObj + " failed: " + exc + " with query: " + logSB);
         exc.printStackTrace();
         throw new IllegalArgumentException("*** selectProperties failed with SQL error: " + exc);
      }
      finally {
         transaction.applyingDBChanges = false;
         if (rs != null)
            DBUtil.close(rs);
      }
   }

   public List<IDBObject> matchQuery(DBTransaction transaction, DBObject proto) {
      SelectTableDesc mainTableDesc = selectTables.get(0);
      TableDescriptor mainTable = mainTableDesc.table;
      StringBuilder qsb = buildTableQueryBase(mainTableDesc);
      ResultSet rs = null;
      StringBuilder logSB = DBUtil.verbose ? new StringBuilder(qsb) : null;
      if (whereSB != null) {
         DBUtil.append(qsb, logSB, " WHERE ");
         qsb.append(whereSB);
      }
      if (logSB != null)
         logSB.append(this.logSB);
      if (orderByProps != null && orderBySB == null)
         setOrderByProps(orderByProps, orderByDirs);
      if (orderBySB != null) {
         qsb.append(orderBySB);
         if (logSB != null)
            logSB.append(this.orderBySB);
      }
      if (maxResults > 0) {
         qsb.append(" LIMIT ");
         qsb.append(maxResults);
         if (logSB != null) {
            logSB.append(" LIMIT ");
            logSB.append(maxResults);
         }
      }
      if (startIndex > 0) {
         qsb.append(" OFFSET ");
         qsb.append(startIndex);
         if (logSB != null) {
            logSB.append(" OFFSET ");
            logSB.append(startIndex);
         }
      }

      try {
         String queryStr = qsb.toString();
         DBList<IDBObject> res = new DBList<IDBObject>();
         DBTypeDescriptor dbTypeDesc = mainTable.dbTypeDesc;

         if (!dbTypeDesc.dbDisabled) {
            Connection conn = transaction.getConnection(dbTypeDesc.getDataSource().jndiName);
            PreparedStatement st = conn.prepareStatement(queryStr);
            IDBObject inst = proto.getInst();
            int numParams = paramValues == null ? 0 : paramValues.size();
            for (int i = 0; i < numParams; i++) {
               Object paramValue = paramValues.get(i);
               DBColumnType propType = paramTypes.get(i);
               DBUtil.setStatementValue(st, i+1, propType, paramValue);
            }

            rs = st.executeQuery();

            if (logSB != null)
               logSB.append(" -> ");

            transaction.applyingDBChanges = true;

            if (!multiRow) {
               IDBObject resInst = processOneRowQueryResults(proto, inst, rs, logSB);
               if (resInst == null)
                  return null;
               res.add(resInst);
               return res;
            }
            else {
               res = new DBList<IDBObject>();
               processMultiResults(res, null, inst, rs, logSB);
            }
         }
         // Just logging the SQL we could do for diagnostic purposes - results for memory queries are merged in later
         else if (logSB != null) {
            logSB.append(" (dbDisabled) ");
         }
         List<IDBObject> cacheRes = null;
         if (dbTypeDesc.dbReadOnly) {
            cacheRes = dbTypeDesc.queryCache(proto, propNames, null);
            if (cacheRes != null && logSB != null) {
               logSB.append("   cached results: [");
               for (int ci = 0; ci < cacheRes.size(); ci++) {
                  IDBObject cacheObj = cacheRes.get(ci);
                  if (ci != 0)
                     logSB.append(", ");
                  DBUtil.appendVal(logSB, cacheObj, null, null);
               }
               logSB.append("]\n");
            }
         }
         if (logSB != null) {
            DBUtil.info(logSB.toString());
         }
         if (cacheRes == null)
            return res;
         if (res.size() == 0)
            return cacheRes;
         return dbTypeDesc.mergeResultLists(cacheRes, res);
      }
      catch (SQLException exc) {
         throw new IllegalArgumentException("*** selectProperties failed with SQL error: " + exc);
      }
      finally {
         transaction.applyingDBChanges = false;
         if (rs != null)
            DBUtil.close(rs);
      }
   }

   public IDBObject matchOne(DBTransaction transaction, DBObject proto) {
      List<IDBObject> res = matchQuery(transaction, proto);
      if (res == null)
         return null;
      if (res.size() > 1)
         throw new IllegalArgumentException("Invalid return list for single query: " + res.size());
      return res.size() == 0 ? null : res.get(0);
   }

   IDBObject processOneRowQueryResults(DBObject dbObj, IDBObject inst, ResultSet rs, StringBuilder logSB) throws SQLException {
      if (!rs.next()) {
         if (dbObj.isPrototype())
            return null;

         if (includesPrimary) {
            dbObj.setTransient(true);
            return null;
         }
         else
            return dbObj;
      }

      boolean first = true;

      int rix = 1;
      for (SelectTableDesc ftd: selectTables) {
         // If this table is actually defined in another type, and represents a reference an instance of that type (a 1-1 relationship),
         // this row sets properties of that referenced instance. Otherwise, its a normal table setting properties on the main instance.
         DBPropertyDescriptor refProp = ftd.refProp;
         Object selectInst = refProp == null ? inst : refProp.getPropertyMapper().getPropertyValue(inst, false, false);
         IDBObject selectObj = selectInst instanceof IDBObject ? (IDBObject) selectInst : null;

         Map<String,Object> tableDynProps = null;

         if (ftd.selectDynColumn) {
             tableDynProps = (Map<String,Object>) DBUtil.getResultSetByIndex(rs, rix,null, DBColumnType.Json, null);
             rix++;
         }

         for (int pix = 0; pix < ftd.props.size(); pix++) {
            DBPropertyDescriptor propDesc = ftd.props.get(pix);
            // If the id was the previous property, we already read in the typeId as part of that. If we have an existing id we are looking
            // up then we need to get the type id to possibly specialize the return type
            if (propDesc.typeIdProperty && pix > 0)
               continue;

            ResultWrapper logResult = logSB == null ? null : new ResultWrapper();
            String logPropName = propDesc.propertyName;
            Object logPropVal = null;

            Object val;
            if (propDesc.dynColumn) {
               Object jsonVal = tableDynProps == null ? null : tableDynProps.get(propDesc.propertyName);
               val = JSON.convertTo(propDesc.getPropertyType(), jsonVal);
               logPropVal = val;
            }
            else {
               val = propDesc.getValueFromResultSet(rs, rix, ftd, selectObj, logResult);
               rix += propDesc.getNumResultSetColumns(ftd);

               logPropVal = val;
               if (logResult != null && logResult.result != null) {
                  logPropVal = logResult.result;
                  logPropName = logPropName + DBPropertyDescriptor.RefIdPropertySuffix;
               }
            }

            if (!propDesc.typeIdProperty) {
               if (selectObj != null && propDesc.ownedByOtherType(selectObj.getDBObject().dbTypeDesc))
                  continue;
               IBeanMapper propMapper = propDesc.getPropertyMapper();
               if (propMapper == null) {
                  System.out.println("*** Error - no mapper for property");
                  continue;
               }
               // A null single-valued reference - just skip it
               if (selectInst == null && val == null && refProp != null)
                  continue;
               propDesc.updateReferenceForPropValue(selectInst, val);
               propMapper.setPropertyValue(selectInst, val);
            }
            else {
               if (val == null)
                  continue;

               int typeId = (int) val;
               DBTypeDescriptor newType = dbObj.dbTypeDesc.getSubTypeByTypeId(typeId);
               if (newType == null) {
                  DBUtil.error("No sub-type of: " + dbObj.dbTypeDesc + " with typeId: " + typeId);
               }
               else if (refProp == null) {
                  if (dbObj.unknownType || newType != dbObj.dbTypeDesc) {
                     IDBObject newInst = newType.createInstance(dbObj);
                     newType.updateTypeDescriptor(newInst, dbObj);
                     dbObj = newInst.getDBObject();
                     selectInst = newInst;
                     selectObj = selectInst instanceof IDBObject ? (IDBObject) selectInst : null;
                     inst = newInst;
                  }
               }
               else {
                  if (newType != refProp.refDBTypeDesc) {
                     System.out.println("*** Warning - polymorphic join result");
                  }
                  if (selectInst == null) {
                     System.out.println("*** Warning - no instance for polymorphic join");
                  }
                  else if (!DynUtil.instanceOf(selectInst, refProp.refDBTypeDesc.typeDecl))
                     System.out.println("*** Error - need to update the instance type here?");
               }
            }

            if (logSB != null && !first)
               logSB.append(", ");
            if (logSB != null) {
               first = false;
               logSB.append(logPropName);
               logSB.append("=");
               DBUtil.appendVal(logSB, logPropVal, propDesc.getDBColumnType(), propDesc.refDBTypeDesc);
            }
         }
         ResultWrapper logValResult = null;
         if (ftd.revColumns != null) {
            for (int ri = 0; ri < ftd.revColumns.size(); ri++) {
              DBPropertyDescriptor propDesc = ftd.revColumns.get(ri);
               if (logSB != null && rix != 1)
                  logSB.append(", ");
               IBeanMapper propMapper = propDesc.getPropertyMapper();

               String logPropName = propMapper.getPropertyName();
               if (logSB != null) {
                  logValResult = new ResultWrapper();
               }

               Object revInst = ftd.revProps.get(ri).getPropertyMapper().getPropertyValue(selectInst, false, false);
               Object val = propDesc.getValueFromResultSet(rs, rix, ftd, (IDBObject) revInst, logValResult);
               rix += propDesc.getNumResultSetColumns(ftd);

               Object logPropVal = val;
               if (val == null && logValResult != null && logValResult.result != null) {
                  logPropName = logPropName + DBPropertyDescriptor.RefIdPropertySuffix;
                  logPropVal = logValResult.result;
               }

               if (revInst != null) {
                  propDesc.getPropertyMapper().setPropertyValue(revInst, val);
               }
               else if (val != null)
                  System.err.println("*** Error - value for reverse reference property where there's no reverse instance");

               if (logSB != null) {
                  logSB.append(logPropName);
                  logSB.append("=");
                  DBUtil.appendVal(logSB, val, propDesc.getDBColumnType(), propDesc.refDBTypeDesc);
               }
            }
         }
      }
      if (rs.next())
         throw new IllegalArgumentException("Fetch query returns more than one row!");
      return inst;
   }

   /**
    * Here the first selectTable defines the list/array value - selectTables.get(0).props.get(0).
    * The second and subsequent select tables are only there for onDemand=false references in the referenced object
    */
   boolean processMultiResults(DBList<IDBObject> resList, DBObject dbObj, Object inst, ResultSet rs, StringBuilder logSB) throws SQLException {
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

         for (int fi = 0; fi < selectTables.size(); fi++) {
            SelectTableDesc selectTable = selectTables.get(fi);
            Object refId = null;

            DBPropertyDescriptor refProp = selectTable.refProp;
            Map<String,Object> tableDynProps = null;

            if (selectTable.selectDynColumn) {
               tableDynProps = (Map<String,Object>) DBUtil.getResultSetByIndex(rs, rix,null, DBColumnType.Json, null);
               rix++;
            }

            boolean rowValSet = false;
            int numProps = selectTable.props.size();
            for (int pix = 0; pix < numProps; pix++) {
               DBPropertyDescriptor propDesc = selectTable.props.get(pix);
               if (propDesc.typeIdProperty && pix > 0)
                  continue;
               Object val;
               refId = null;

               if (propDesc.dynColumn) {
                  Object jsonVal = tableDynProps == null ? null : tableDynProps.get(propDesc.propertyName);
                  val = JSON.convertTo(propDesc.getPropertyType(), jsonVal);
               }
               else {
                  int numCols = propDesc.getNumColumns();
                  DBTypeDescriptor colTypeDesc = propDesc.getRefColTypeDesc();
                  if (numCols == 1)  {
                     val = DBUtil.getResultSetByIndex(rs, rix++, propDesc);
                     if (colTypeDesc != null) {
                        DBPropertyDescriptor colTypeIdProp = colTypeDesc.getTypeIdProperty();
                        int typeId = -1;
                        // If we are joining in a 1-1 relationship eagerly, we ensure the db_type_id is right after the id so we can create the object of the right type
                        if (colTypeIdProp != null && !propDesc.onDemand && selectTable.hasJoinTableForRef(propDesc)) {
                           Object typeIdRes = DBUtil.getResultSetByIndex(rs, rix++, colTypeIdProp);
                           if (typeIdRes != null)
                              typeId = (int) typeIdRes;
                        }
                        Object idVal = val;
                        val = val == null ? null : colTypeDesc.lookupInstById(val, typeId, true, false);

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
                        else if (idVal != null) {
                           refId = idVal;
                        }
                     }
                     else if (propDesc.typeIdProperty) {
                        if (val == null)
                           continue;

                        int typeId = (int) val;

                        continue; // TODO: do we need this to help define the type?  Or should we eliminate it from the select list?
                        /*
                        DBTypeDescriptor newType = dbObj.dbTypeDesc.subTypesById.get(typeId);
                        if (newType == null) {
                           DBUtil.error("No sub-type of: " + dbObj.dbTypeDesc + " with typeId: " + typeId);
                        }
                        else if (refProp == null) {
                           System.out.println("***");
                           if (newType != dbObj.dbTypeDesc) {
                              IDBObject newInst = dbObj.dbTypeDesc.createInstance();
                              newInst.getDBObject().setDBId(((IDBObject) selectInst).getDBId());
                              selectInst = newInst;
                              selectObj = selectInst instanceof IDBObject ? (IDBObject) selectInst : null;
                           }
                        }
                        else {
                           System.out.println("***");
                           if (newType != refProp.refDBTypeDesc) {
                              System.out.println("*** Warning - polymorphic join result");
                           }
                        }
                           */
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
                        DBPropertyDescriptor colTypeIdProp = colTypeDesc.getTypeIdProperty();
                        int typeId = -1;
                        if (colTypeIdProp != null) {
                           Object typeIdRes = DBUtil.getResultSetByIndex(rs, rix++, colTypeIdProp);
                           if (typeIdRes != null)
                              typeId = (int) typeIdRes;
                        }
                        val = colTypeDesc.lookupInstById(idVals, typeId, true, false);

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
               }

               if (fi == 0 && !rowValSet) {
                  currentRowVal = (IDBObject) val;
                  rowValSet = true;
                  if (resList == null) {
                     resList = new DBList(10, dbObj, propDesc);
                     listProp = propDesc; // the first time through, the main property for this list
                  }
                  resList.add(currentRowVal);
                  if (currentRowVal == null && refId != null)
                     resList.setRefId(resList.size()-1, refId);
                  if (logSB != null) {
                     logSB.append("[");
                     logSB.append(rowCt);
                     logSB.append("](");
                     logSB.append(currentRowVal == null ? (refId == null ? null : "refId:" + refId) : currentRowVal.getDBObject());
                  }
                  rowCt++;
               }
               else {
                  Object propInst = selectTable.refProp == null || listProp != null ? currentRowVal : selectTable.refProp.getPropertyMapper().getPropertyValue(currentRowVal, false, false);
                  if (currentRowVal == null)
                     throw new UnsupportedOperationException("Multi value select tables - not attached to reference");

                  IDBObject propDBObj = propInst instanceof IDBObject ? (IDBObject) propInst : null;
                  if (propDBObj != null && !propDesc.ownedByOtherType(propDBObj.getDBObject().dbTypeDesc)) {
                     Object logVal;
                     String logName;
                     DBColumnType logColType;
                     if (val == null && refId != null && propDesc.getNeedsRefId()) {
                        propDesc.setRefIdProperty(propDBObj, refId);
                        logVal = refId;
                        logName = propDesc.propertyName + DBPropertyDescriptor.RefIdPropertySuffix;
                        logColType = DBColumnType.LongId;
                     }
                     else {
                        IBeanMapper propMapper = propDesc.getPropertyMapper();
                        propMapper.setPropertyValue(propInst, val);
                        logVal = val;
                        logName = propDesc.propertyName;
                        logColType = propDesc.getDBColumnType();
                     }

                     if (logSB != null) {
                        logSB.append(", ");
                        logSB.append(logName);
                        logSB.append("=");
                        DBUtil.appendVal(logSB, logVal, logColType, propDesc.refDBTypeDesc);
                     }
                  }
                  else {
                     if (logSB != null) {
                        logSB.append(", ");
                        logSB.append(propDesc.propertyName);
                        if (val == null)
                           logSB.append(" = n/a");
                        else {
                           logSB.append(" skipped value = ");
                           DBUtil.appendVal(logSB, val, propDesc.getDBColumnType(), propDesc.refDBTypeDesc);
                        }
                     }
                  }
               }
            }
            if (selectTable.revColumns != null) {
               DBPropertyDescriptor revProp = selectTable.revProps.get(0);
               int numToFetch = selectTable.revColumns.size();
               for (int rci = 0; rci < numToFetch; rci++) {
                  DBPropertyDescriptor propDesc = selectTable.revColumns.get(rci);
                  // should be preceded by the id which reads it
                  if (propDesc.typeIdProperty)
                     continue;
                  Object val;
                  int numCols = propDesc.getNumColumns();

                  // If this is the first selectTable and the first column in the revProps list is the id of the reverse property instance
                  DBPropertyDescriptor readProp = rci == 0 && fi == 0 ? revProp : propDesc;
                  DBTypeDescriptor refTypeDesc = readProp.refDBTypeDesc;
                  if (numCols == 1)  {
                     val = DBUtil.getResultSetByIndex(rs, rix++, propDesc);

                     if (refTypeDesc != null) {
                        int typeId = -1;
                        DBPropertyDescriptor refTypeIdProp = refTypeDesc.getTypeIdProperty();
                        if (readProp.getNeedsRefId()) {
                           readProp.setRefIdProperty(currentRowVal, val);
                        }
                        if (refTypeIdProp != null && (rci == 0 || readProp.eagerJoinForTypeId(selectTable)))
                           typeId = (int) DBUtil.getResultSetByIndex(rs, rix++, refTypeIdProp);
                        val = val == null ? null : refTypeDesc.lookupInstById(val, typeId, true, false);

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
                        boolean allNull = true;
                        for (int ci = 0; ci < numCols; ci++) {
                           IdPropertyDescriptor refIdCol = refIdCols.get(ci);
                           Object idVal = DBUtil.getResultSetByIndex(rs, rix++, refIdCol);
                           if (idVal != null)
                              allNull = false;
                           idVals.setVal(idVal, ci);
                        }
                        int typeId = -1;
                        DBPropertyDescriptor refTypeIdProp = refTypeDesc.getTypeIdProperty();
                        if (refTypeIdProp != null && propDesc.eagerJoinForTypeId(selectTable)) {
                           Object typeIdRes = DBUtil.getResultSetByIndex(rs, rix++, refTypeIdProp);
                           if (typeIdRes != null)
                              typeId = (int) typeIdRes;
                        }
                        val = allNull ? null : refTypeDesc.lookupInstById(idVals, typeId, true, false);

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
                        throw new UnsupportedOperationException("Multi value select tables - not attached to reference");

                     boolean skipped = false;
                     if (!propDesc.ownedByOtherType(currentRowVal.getDBObject().dbTypeDesc)) {
                        IBeanMapper propMapper = propDesc.getPropertyMapper();
                        propMapper.setPropertyValue(currentRowVal, val);
                     }
                     else
                        skipped = true;

                     if (logSB != null) {
                        if (rci > 1)
                           logSB.append(", ");
                        logSB.append(propDesc.propertyName);
                        logSB.append("=");
                        if (skipped) {
                           if (val == null)
                              logSB.append("n/a");
                           else {
                              logSB.append("skipped: ");
                              DBUtil.appendVal(logSB, val, propDesc.getDBColumnType(), propDesc.refDBTypeDesc);
                           }
                        }
                        else
                           DBUtil.appendVal(logSB, val, propDesc.getDBColumnType(), propDesc.refDBTypeDesc);
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

   private StringBuilder buildTableFetchQuery(List<SelectTableDesc> selectTables) {
      SelectTableDesc mainTableDesc = selectTables.get(0);
      TableDescriptor mainTable = mainTableDesc.table;
      StringBuilder qsb = buildTableQueryBase(mainTableDesc);
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

   private StringBuilder buildTableQueryBase(SelectTableDesc mainTableDesc) {
      List<SelectTableDesc> selectTables = this.selectTables;
      TableDescriptor mainTable = mainTableDesc.table;
      StringBuilder res = new StringBuilder();
      res.append("SELECT ");
      for (int i = 0; i < selectTables.size(); i++) {
         if (i != 0)
            res.append(", ");
         SelectTableDesc selectTable = selectTables.get(i);
         appendTableSelect(res, selectTable);
      }
      res.append(" FROM ");
      DBUtil.appendIdent(res, null, mainTable.tableName);
      for (int i = 1; i < selectTables.size(); i++) {
         res.append(" LEFT OUTER JOIN ");
         SelectTableDesc joinTableDesc = selectTables.get(i);
         DBUtil.appendIdent(res, null, joinTableDesc.getTableDecl());
         res.append(" ON ");
         appendJoinTable(res, mainTableDesc, joinTableDesc);
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

   private static void appendJoinTable(StringBuilder queryStr, SelectTableDesc mainTable, SelectTableDesc joinTable) {
      List<? extends DBPropertyDescriptor> mainJoinCols;

      if (joinTable.refProp == null) {
         if (joinTable.revProps != null) {
            DBPropertyDescriptor joinProp = joinTable.revProps.get(0);
            mainJoinCols = Collections.singletonList(joinProp);
         }
         else {
            if (joinTable.table.tableName.equals(mainTable.table.tableName))
               mainJoinCols = joinTable.table.getIdColumns();
            else {
               System.err.println("*** Unrecognized join table pattern");
               mainJoinCols = joinTable.table.getIdColumns();
            }
         }
      }
      else {
         if (joinTable.refProp.tableName.equals(mainTable.table.tableName)) {
            mainJoinCols = Collections.singletonList(joinTable.refProp);
         }
         else {
            System.err.println("*** Unrecognized join table for ref");
            return;
         }
      }

      int sz = mainJoinCols.size();
      for (int i = 0; i < sz; i++) {
         if (i != 0)
            queryStr.append(" AND ");
         DBUtil.appendIdent(queryStr, null, mainTable.getTableAlias());
         queryStr.append(".");
         //DBUtil.appendIdent(queryStr, null, mainIdCols.get(i).columnName);
         //DBUtil.appendIdent(queryStr, null, mainTable.columns.get(i).columnName);
         DBUtil.appendIdent(queryStr, null, mainJoinCols.get(i).columnName);
         queryStr.append(" = ");
         DBUtil.appendIdent(queryStr, null, joinTable.getTableAlias());
         queryStr.append(".");
         DBUtil.appendIdent(queryStr, null, joinTable.table.idColumns.get(i).columnName);
      }
   }

   private void appendTableSelect(StringBuilder queryStr, SelectTableDesc selectTable) {
      boolean first = true;
      if (selectTable.selectDynColumn) {
         first = false;
         appendColumnSelect(queryStr, selectTable, DBTypeDescriptor.DBDynPropsColumnName);
      }
      for (DBPropertyDescriptor prop:selectTable.props) {
         if (prop.dynColumn)
            continue;
         if (first)
            first = false;
         else
            queryStr.append(", ");
         appendColumnName(queryStr, selectTable, prop);
      }
      if (selectTable.revColumns != null) {
         for (DBPropertyDescriptor prop:selectTable.revColumns) {
            if (prop.dynColumn)
               continue;
            if (first)
               first = false;
            else
               queryStr.append(", ");
            appendColumnName(queryStr, selectTable, prop);
         }
      }
   }

   public String getSelectTableAliasForRefProp(DBPropertyDescriptor refProp) {
      if (selectTables.size() == 1)
         return null; // No prefix needed
      for (SelectTableDesc desc: selectTables) {
         if (desc.refProp == refProp)
            return desc.alias;
      }
      return null;
   }

   public void appendColumnSelect(StringBuilder queryStr, SelectTableDesc tableDesc, String colName) {
      String alias = selectTables.size() == 1 ? null : tableDesc.getTableAlias();
      if (alias != null) {
         queryStr.append(alias);
         queryStr.append(".");
      }
      queryStr.append(colName);
   }

   public void appendColumnName(StringBuilder queryStr, SelectTableDesc tableDesc, DBPropertyDescriptor prop) {
      appendColumnSelect(queryStr, tableDesc, prop.columnName);

      DBTypeDescriptor refTypeDesc = prop.refDBTypeDesc;
      if (refTypeDesc != null && !prop.onDemand && tableDesc.hasJoinTableForRef(prop)) {
         DBPropertyDescriptor typeIdProp = refTypeDesc.getTypeIdProperty();
         if (typeIdProp != null) {
            queryStr.append(", ");
            String refTableAlias = getSelectTableAliasForRefProp(prop);
            if (refTableAlias != null) {
               queryStr.append(refTableAlias);
               queryStr.append(".");
            }
            queryStr.append(typeIdProp.columnName);
         }
      }
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("select-query: ");
      if (selectTables != null) {
         for (int i = 0; i < selectTables.size(); i++) {
            SelectTableDesc selectTable = selectTables.get(i);
            if (i != 0)
               sb.append(", ");
            sb.append(selectTable);
         }
      }
      if (whereSB != null) {
         sb.append(" WHERE ");
         sb.append(whereSB);
      }
      if (orderBySB != null) {
         sb.append(orderBySB);
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

   public SelectQuery cloneForSubType(DBTypeDescriptor subType) {
      SelectQuery res = new SelectQuery(dataSourceName, subType == null ? dbTypeDesc : subType, multiRow);
      res.includesPrimary = includesPrimary;
      res.propNames = propNames;
      if (refProps != null)
         res.refProps = new ArrayList<DBPropertyDescriptor>(refProps);
      for (SelectTableDesc ftd: selectTables) {
         SelectTableDesc nftd = ftd.clone();
         // Don't copy tables for reference properties defined for a parallel sub-type - i.e. if this is a base type extended by a
         // sub-type that doesn't have this property, don't do that copy for it.
         if (nftd.refProp != null && subType != null && nftd.refProp.ownedByOtherType(subType))
            continue;
         res.selectTables.add(nftd);
      }
      return res;
   }

   public void appendWhereColumn(String parentProp, DBPropertyDescriptor prop) {
      initWhereQuery();
      numWhereColumns++;
      SelectTableDesc selectTable = getSelectTableForProperty(parentProp == null ? null : dbTypeDesc.getPropertyDescriptor(parentProp), prop);
      if (selectTable == null) {
         DBUtil.error("No selectTable for " + (parentProp == null ? "" : parentProp + "." + prop));
         return;
      }
      if (selectTables.size() > 1) {
         whereAppendIdent(selectTable.getTableAlias());
         DBUtil.append(whereSB, null, ".");
      }
      if (!prop.dynColumn) {
         DBUtil.appendIdent(whereSB, null, prop.columnName);
      }
      else {
         String castType = DBUtil.getJSONCastType(prop.getDBColumnType());
         if (castType != null)
            whereSB.append("(");
         DBUtil.appendIdent(whereSB, null, DBTypeDescriptor.DBDynPropsColumnName);
         whereSB.append(" ->> '");
         whereSB.append(prop.propertyName);
         whereSB.append("'");
         if (castType != null) {
            whereSB.append(")::");
            whereSB.append(castType);
         }
      }
   }

   public void appendDynWhereColumn(String tableName, DBPropertyDescriptor prop, String subPropPath) {
      initWhereQuery();
      numWhereColumns++;
      if (selectTables.size() > 1) {
         whereAppendIdent(tableName);
         DBUtil.append(whereSB, null, ".");
      }
      DBUtil.appendIdent(whereSB, null, DBTypeDescriptor.DBDynPropsColumnName);
      if (subPropPath == null)
         whereSB.append(" ->> '");
      else
         whereSB.append(" -> '");
      whereSB.append(prop.propertyName);
      whereSB.append("'");
      if (subPropPath != null) {
         if (subPropPath.indexOf('.') != -1)
            System.err.println("*** TODO: Need to add -> for the intermediate paths");
         whereSB.append(" ->> '");
         whereSB.append(subPropPath);
         whereSB.append("'");
      }
   }

   public void appendDynLogWhereColumn(StringBuilder logSB, String tableName, DBPropertyDescriptor prop, String subPropPath) {
      logSB.append("TODO: dynColumn property");
   }

   public void appendJSONWhereColumn(String tableName, String colName, String propPath) {
      initWhereQuery();
      numWhereColumns++;
      if (selectTables.size() > 1) {
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
      if (selectTables.size() > 1) {
         DBUtil.appendIdent(logSB, null, tableName);
         DBUtil.append(logSB, null, ".");
      }
      DBUtil.appendIdent(logSB, null, colName);
      logSB.append("->>");
      logSB.append("'");
      logSB.append(propPath);
      logSB.append("'");
   }

   public void appendLogWhereColumn(StringBuilder logSB, String parentProp, DBPropertyDescriptor prop) {
      initWhereQuery();
      if (logSB == null)
         return;
      SelectTableDesc tableDesc = getSelectTableForProperty(parentProp == null ? null : dbTypeDesc.getPropertyDescriptor(parentProp), prop);
      if (tableDesc == null) {
         System.err.println("*** No table for property: " + (parentProp == null ? "" : parentProp + "." + prop));
         return;
      }
      if (selectTables.size() > 1) {
         DBUtil.appendIdent(logSB, null, tableDesc.getTableAlias());
         logSB.append(".");
      }
      DBUtil.appendIdent(logSB, null, prop.columnName);
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

   public void setOrderByProps(List<DBPropertyDescriptor> props, List<Boolean> orderByDirs) {
      orderBySB = new StringBuilder();
      orderBySB.append(" ORDER BY ");
      for (int i = 0; i < props.size(); i++) {
         if (i != 0)
            orderBySB.append(", ");
         DBPropertyDescriptor prop = props.get(i);
         Boolean dir = orderByDirs.get(i);
         orderBySB.append(prop.columnName);
         if (dir)
            orderBySB.append(" DESC");
      }
   }

   public void insertIdProperty() {
      SelectTableDesc mainTableFetch = selectTables.get(0);
      TableDescriptor mainTable = mainTableFetch.table;
      List<DBPropertyDescriptor> selectProps = mainTableFetch.props;
      List<IdPropertyDescriptor> idCols = mainTable.getIdColumns();
      int idSz = idCols.size();
      for (int i = idSz - 1; i >= 0; i--) {
         IdPropertyDescriptor idCol = idCols.get(i);
         if (!selectProps.contains(idCol))
            selectProps.add(0, idCol);
      }
      // Add the typeId right after the id properties in the select list
      DBPropertyDescriptor typeIdProp = mainTable.getTypeIdProperty();
      if (typeIdProp != null) {
         if (!mainTableFetch.props.contains(typeIdProp)) {
            if (selectProps.size() == idSz)
               mainTableFetch.props.add(typeIdProp);
            else
               mainTableFetch.props.add(idSz, typeIdProp);
         }
      }
   }
}
