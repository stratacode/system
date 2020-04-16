package sc.db;

import sc.type.IBeanMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public abstract class TxOperation {
   DBTransaction transaction;
   public DBObject dbObject;
   public boolean applied = false;

   public TxOperation(DBTransaction tx, DBObject dbObject) {
      this.transaction = tx;
      this.dbObject = dbObject;
   }

   public abstract int apply();

   /** Used by both TxInsert and TxUpdate */
   protected int doInsert(TableDescriptor insertTable) {
      if (insertTable.isReadOnly())
         return 0;

      DBTypeDescriptor dbTypeDesc = dbObject.dbTypeDesc;
      TableDescriptor primaryTable = dbTypeDesc.primaryTable;
      boolean isPrimary = primaryTable == insertTable;

      if (isPrimary) {
         if (!dbObject.isTransient()) {
            throw new IllegalArgumentException("Inserting non-transient object");
         }
      }
      else if (dbObject.isTransient()) {
         throw new IllegalArgumentException("Inserting non-primary table with transient object");
      }

      List<IdPropertyDescriptor> idCols = insertTable.getIdColumns();

      ArrayList<String> columnNames = new ArrayList<String>();
      ArrayList<DBColumnType> columnTypes = new ArrayList<DBColumnType>();
      ArrayList<Object> columnValues = new ArrayList<Object>();

      ArrayList<DBPropertyDescriptor> dbIdCols = null;

      List<String> nullProps = null;
      IDBObject inst = dbObject.getInst();
      for (int i = 0; i < idCols.size(); i++) {
         IdPropertyDescriptor idCol = idCols.get(i);
         if (isPrimary && idCol.definedByDB) {
            if (dbIdCols == null)
               dbIdCols = new ArrayList<DBPropertyDescriptor>();
            dbIdCols.add(idCol);
         }
         else {
            IBeanMapper mapper = idCol.getPropertyMapper();
            Object val = mapper.getPropertyValue(inst, false, false);
            if (val == null) {
               if (nullProps == null)
                  nullProps = new ArrayList<String>();
               nullProps.add(idCol.propertyName);
            }
            columnValues.add(val);
            columnNames.add(idCol.columnName);
            columnTypes.add(idCol.getDBColumnType());
            //columnTypes.add(idCol.getPropertyMapper().getPropertyType());
         }
      }

      if (nullProps != null)
         throw new IllegalArgumentException("Null id properties for DBObject in insert: " + nullProps);

      int idSize = columnNames.size();
      addColumnsAndValues(dbTypeDesc, insertTable.columns, columnNames, columnTypes, columnValues, dbIdCols);

      int numCols = columnNames.size();

      // If it's only id columns should we do the insert?
      if (!insertTable.insertWithNullValues && numCols == idSize)
         return 0;

      StringBuilder sb = new StringBuilder();
      sb.append("INSERT INTO ");
      DBUtil.appendIdent(sb, null, insertTable.tableName);
      sb.append("(");
      for (int i = 0; i < numCols; i++) {
         if (i != 0) {
            sb.append(", ");
         }
         DBUtil.appendIdent(sb, null, columnNames.get(i));
      }
      sb.append(") VALUES (");

      StringBuilder logSB = DBUtil.verbose ? new StringBuilder(sb) : null;

      for (int i = 0; i < numCols; i++) {
         if (i != 0) {
            sb.append(", ");
            if (logSB != null)
               logSB.append(", ");
         }
         sb.append("?");
         if (logSB != null)
            logSB.append(DBUtil.formatValue(columnValues.get(i), columnTypes.get(i)));
      }

      StringBuilder rest = new StringBuilder();
      rest.append(")");

      if (dbIdCols != null) {
         rest.append(" RETURNING ");
         for (int i = 0; i < dbIdCols.size(); i++) {
            if (i != 0)
               rest.append(", ");
            DBUtil.appendIdent(rest, null, dbIdCols.get(i).columnName);
         }
      }
      sb.append(rest);
      if (logSB != null)
         logSB.append(rest);

      DBDataSource ds = dbTypeDesc.getDataSource();
      boolean addToDB = !dbTypeDesc.dbReadOnly;

      try {
         if (addToDB) { // If the db is read-only will store the objects in memory for easier testing, prototyping
            Connection conn = transaction.getConnection(ds.jndiName);
            String statementStr = sb.toString();
            PreparedStatement st = conn.prepareStatement(statementStr);

            for (int i = 0; i < numCols; i++) {
               DBUtil.setStatementValue(st, i+1, columnTypes.get(i), columnValues.get(i));
            }

            if (dbIdCols != null) {
               ResultSet rs = st.executeQuery();
               if (!rs.next())
                  throw new IllegalArgumentException("Missing returned id result for insert with definedByDB ids: " + dbIdCols);
               if (logSB != null)
                  logSB.append(" = ");
               for (int i = 0; i < dbIdCols.size(); i++) {
                  DBPropertyDescriptor dbIdCol = dbIdCols.get(i);
                  IBeanMapper mapper = dbIdCol.getPropertyMapper();
                  Object id = DBUtil.getResultSetByIndex(rs, i+1, dbIdCol);
                  mapper.setPropertyValue(inst, id);

                  if (logSB != null) {
                     if (i != 0)
                        logSB.append(", ");
                     logSB.append(id);
                  }
               }

               if (logSB != null) {
                  DBUtil.info(logSB);
               }
            }
            else {
               int numInserted = st.executeUpdate();
               if (numInserted != 1)
                 DBUtil.error("Insert of one row returns: " + numInserted + " rows inserted");

               if (logSB != null) {
                  if (numInserted == 1)
                     logSB.append(" -> inserted one row");
                  else
                     logSB.append(" -> updated " + numInserted + " rows") ;

                  DBUtil.info(logSB);
               }
            }
         }
         else {
            if (dbIdCols != null) {
               if (logSB != null)
               logSB.append(" (dbDisabled) -> ");
               for (int i = 0; i < dbIdCols.size(); i++) {
                  DBPropertyDescriptor dbIdCol = dbIdCols.get(i);
                  IBeanMapper mapper = dbIdCol.getPropertyMapper();
                  Object id = dbIdCol.getDBDefaultValue();
                  mapper.setPropertyValue(inst, id);

                  if (logSB != null) {
                     if (i != 0)
                        logSB.append(", ");
                     logSB.append(id);
                  }
               }
            }
            else if (logSB != null)
               logSB.append(" (dbDisabled)");

            if (logSB != null) {
               DBUtil.info(logSB);
            }
         }

         if (isPrimary) {
            dbObject.registerNew();
         }
      }
      catch (SQLException exc) {
         throw new IllegalArgumentException("*** Insert: " + dbTypeDesc + " with dbIdCols: " + dbIdCols + " returned failed: " + exc);
      }
      return 1;
   }

   protected int doMultiDelete(TableDescriptor delTable, List<IDBObject> toRemove, boolean removeCurrent) {
      if (delTable.isReadOnly())
         return 0;
      // TODO: any differences we need to add here?
      return doDelete(delTable);
   }

   /** Called with null to insert the current property value, or a list of values to insert */
   protected int doMultiInsert(TableDescriptor insertTable, List<IDBObject> propList, boolean useCurrent) {
      if (insertTable.isReadOnly())
         return 0;

      DBTypeDescriptor dbTypeDesc = dbObject.dbTypeDesc;

      if (dbObject.isTransient()) {
         throw new IllegalArgumentException("Inserting multi table with transient object");
      }

      Object parentInst = dbObject.getInst();

      DBPropertyDescriptor revProp = insertTable.reverseProperty;

      DBPropertyDescriptor multiValueProp = revProp == null ? insertTable.columns.get(0) : revProp;
      IBeanMapper multiValueMapper = multiValueProp.getPropertyMapper();
      if (useCurrent)
         propList = (List<IDBObject>) multiValueMapper.getPropertyValue(parentInst, false, false);
      int numInsts;
      if (propList == null || (numInsts = propList.size()) == 0)
         return 0;

      List<IdPropertyDescriptor> idCols = insertTable.getIdColumns();

      // The list of 'definedByDB' id properties for the referenced type when this is a one-to-many inserting new instances
      // of the referenced type
      List<IdPropertyDescriptor> dbIdCols = null;
      if (revProp != null) {
         for (DBPropertyDescriptor colDesc:insertTable.columns) {
            if (colDesc instanceof IdPropertyDescriptor) {
               IdPropertyDescriptor idDesc = ((IdPropertyDescriptor) colDesc);
               if (idDesc.definedByDB) {
                  if (dbIdCols == null)
                     dbIdCols = new ArrayList<IdPropertyDescriptor>();
                  dbIdCols.add(idDesc);
               }
            }
         }
      }

      ArrayList<String> columnNames = new ArrayList<String>();
      ArrayList<DBColumnType> columnTypes = new ArrayList<DBColumnType>();
      ArrayList<Object> columnValues = new ArrayList<Object>();

      ArrayList<Object> idVals = new ArrayList<Object>();

      List<String> nullProps = null;

      StringBuilder sb = new StringBuilder();
      StringBuilder logSB = null;
      int toInsert = 0;
      int numCols = 0;
      for (int ix = 0; ix < numInsts; ix++) {
         IDBObject arrInst = propList.get(ix);
         int idSize = idCols.size();

         if (toInsert == 0) {
            for (int ci = 0; ci < idSize; ci++) {
               IdPropertyDescriptor idCol = idCols.get(ci);
               IBeanMapper mapper = idCol.getPropertyMapper();
               Object val = mapper.getPropertyValue(parentInst, false, false);
               if (val == null) {
                  if (nullProps == null)
                     nullProps = new ArrayList<String>();
                  nullProps.add(idCol.propertyName);
               }
               idVals.add(val);
               columnValues.add(val);
               columnNames.add(idCol.columnName);
               columnTypes.add(idCol.getDBColumnType());
            }

            if (nullProps != null)
               throw new IllegalArgumentException("Null id properties for DBObject in insert: " + nullProps);

            addArrayValues(dbTypeDesc, arrInst, insertTable.columns, columnNames, columnTypes, columnValues);

            numCols = columnNames.size();

            // If it's only id columns should we do the insert?
            if (numCols == idSize)
               return 0;

            sb.append("INSERT INTO ");
            DBUtil.appendIdent(sb, null, insertTable.tableName);
            sb.append("(");
            for (int i = 0; i < numCols; i++) {
               if (i != 0) {
                  sb.append(", ");
               }
               DBUtil.appendIdent(sb, null, columnNames.get(i));
            }
            sb.append(") VALUES");

            logSB = DBUtil.verbose ? new StringBuilder(sb.toString()) : null;
         }
         else {
            for (int ci = 0; ci < idSize; ci++) {
               IdPropertyDescriptor idCol = idCols.get(ci);
               columnTypes.add(idCol.getDBColumnType());
               columnValues.add(idVals.get(ci));
            }
            addArrayValues(dbTypeDesc, arrInst, insertTable.columns, null, columnTypes, columnValues);
         }

         if (ix != 0) {
            DBUtil.append(sb, logSB, ", ");
         }

         DBUtil.append(sb, logSB, "(");
         for (int ci = 0; ci < numCols; ci++) {
            if (ci != 0) {
               DBUtil.append(sb, logSB, ",");
            }
            sb.append("?");
            if (logSB != null) {
               int colIx = ci+toInsert*numCols;
               logSB.append(DBUtil.formatValue(columnValues.get(colIx), columnTypes.get(colIx)));
            }
         }
         DBUtil.append(sb, logSB, ")");
         toInsert++;
      }
      if (dbIdCols != null) {
         DBUtil.append(sb, logSB, " RETURNING ");
         for (int ri = 0; ri < dbIdCols.size(); ri++)  {
            if (ri != 0)
               DBUtil.append(sb, logSB, ", ");
            IdPropertyDescriptor dbIdCol = dbIdCols.get(ri);
            DBUtil.appendIdent(sb, logSB, dbIdCol.columnName);
         }
      }

      if (useCurrent && !(propList instanceof DBList)) {
         DBList dbList = new DBList(propList, dbObject, multiValueProp);
         transaction.applyingDBChanges = true;
         try {
            multiValueMapper.setPropertyValue(parentInst, dbList);
            dbList.trackingChanges = true;
         }
         finally {
            transaction.applyingDBChanges = false;
         }
      }

      if (toInsert == 0)
         return 0;

      boolean addToDB = !dbTypeDesc.dbReadOnly;
      ResultSet rs = null;
      try {
         if (addToDB) {
            Connection conn = transaction.getConnection(dbTypeDesc.dataSourceName);
            String insertStr = sb.toString();
            PreparedStatement st = conn.prepareStatement(insertStr);

            for (int ci = 0; ci < columnValues.size(); ci++) {
               DBUtil.setStatementValue(st, ci+1, columnTypes.get(ci), columnValues.get(ci));
            }
            int numInserted;

            if (dbIdCols != null) {
               rs = st.executeQuery();
               numInserted = 0;
               // For each instance read back the returned 'definedByDB' id properties and set them
               if (logSB != null) {
                  logSB.append(" = ");
               }
               for (int ix = 0; ix < numInsts; ix++) {
                  if (!rs.next())
                     throw new IllegalArgumentException("Unexpected end of query results: " + sb + " expected to return: " + numInsts + " and did not return: " + ix);
                  if (logSB != null && ix != 0)
                     logSB.append(", ");
                  IDBObject arrInst = propList.get(ix);
                  for (int idx = 0; idx < dbIdCols.size(); idx++) {
                     IdPropertyDescriptor dbIdCol = dbIdCols.get(idx);
                     IBeanMapper mapper = dbIdCol.getPropertyMapper();
                     Object id = DBUtil.getResultSetByIndex(rs, idx+1, dbIdCol);
                     mapper.setPropertyValue(arrInst, id);

                     if (logSB != null) {
                        if (idx != 0)
                           logSB.append(", ");
                        logSB.append(id);
                     }
                  }
                  numInserted++;
               }
            }
            else {
               numInserted = st.executeUpdate();
               if (logSB != null) {
                  logSB.append(" -> inserted ");
                  logSB.append(numInserted);
               }
            }
            if (numInserted != toInsert)
               DBUtil.error("Insert of: " + toInsert + " rows inserted: " + numInserted + " instead for: " + dbTypeDesc);
         }
         else {
            if (logSB != null)
               logSB.append(" (dbDisabled) ");
            if (dbIdCols != null) {
               // For each instance read back the returned 'definedByDB' id properties and set them
               if (logSB != null) {
                  logSB.append(" = ");
               }
               for (int ix = 0; ix < numInsts; ix++) {
                  if (logSB != null && ix != 0)
                     logSB.append(", ");
                  IDBObject arrInst = propList.get(ix);
                  for (int idx = 0; idx < dbIdCols.size(); idx++) {
                     IdPropertyDescriptor dbIdCol = dbIdCols.get(idx);
                     IBeanMapper mapper = dbIdCol.getPropertyMapper();
                     Object id = dbIdCol.allocMemoryId();
                     mapper.setPropertyValue(arrInst, id);

                     if (logSB != null) {
                        if (idx != 0)
                           logSB.append(", ");
                        logSB.append(id);
                     }
                  }
               }
            }
         }

         if (logSB != null) {
            DBUtil.info(logSB);
         }

         for (int ix = 0; ix < numInsts; ix++) {
            IDBObject arrInst = propList.get(ix);
            arrInst.getDBObject().registerNew();
         }
      }
      catch (SQLException exc) {
         throw new IllegalArgumentException("*** Insert: " + dbTypeDesc + " with dbIdCols: " + dbIdCols + " returned failed: " + exc);
      }
      finally {
         DBUtil.close(rs);
      }
      return 1;
   }

   private void addColumnsAndValues(DBTypeDescriptor dbTypeDesc, List<? extends DBPropertyDescriptor> cols,
                                    ArrayList<String> columnNames, ArrayList<DBColumnType> columnTypes, ArrayList<Object> columnValues,
                                    List<DBPropertyDescriptor> dbReturnCols) {
      Object inst = dbObject.getInst();

      for (DBPropertyDescriptor col:cols) {
         int numCols = col.getNumColumns();
         Object val;
         if (col.typeIdProperty) {
            int typeId = dbTypeDesc.typeId;
            val = typeId;
            if (typeId == DBTypeDescriptor.DBAbstractTypeId)
               throw new IllegalArgumentException("Unable to insert instance of abstract type: " + dbTypeDesc);
         }
         else {
            if (col.ownedByOtherType(dbTypeDesc))
               continue;
            IBeanMapper mapper = col.getPropertyMapper();
            val = mapper.getPropertyValue(inst, false, false);
         }
         if (numCols == 1) {
            if (val != null) {
               DBColumnType colType;
               if (col.refDBTypeDesc != null) {
                  val = col.refDBTypeDesc.getIdColumnValue(val, 0);
                  colType = col.refDBTypeDesc.getIdDBColumnType(0);
               }
               else
                  colType = col.getDBColumnType();
               columnNames.add(col.columnName);
               columnTypes.add(colType);
               columnValues.add(val);
            }

            if (col.dbDefault != null && col.dbDefault.length() > 0) {
               dbReturnCols.add(col);
            }
         }
         else {
            if (col.refDBTypeDesc != null) {
               for (int ci = 0; ci < numCols; ci++) {
                  columnNames.add(col.getColumnName(ci));
                  columnTypes.add(col.refDBTypeDesc.getIdDBColumnType(ci));
                  columnValues.add(col.refDBTypeDesc.getIdColumnValue(val, ci));
               }
            }
            else
               System.err.println("*** Multi column key with no way to map columns");
         }
      }
   }

   private void addArrayValues(DBTypeDescriptor dbTypeDesc, IDBObject arrInst, List<? extends DBPropertyDescriptor> cols,
                                    ArrayList<String> columnNames, ArrayList<DBColumnType> columnTypes, ArrayList<Object> columnValues) {
      for (DBPropertyDescriptor col:cols) {
         int numCols = col.getNumColumns();
         IBeanMapper mapper = col.getPropertyMapper();
         DBColumnType dbColumnType = col.getDBColumnType();
         Object val = arrInst;
         boolean reverseItem = col.dbTypeDesc != dbTypeDesc;
         if (numCols == 1) {
            if (arrInst != null) {
               DBColumnType colType;
               if (reverseItem) {
                  if (col instanceof IdPropertyDescriptor) {
                     // The database will provide this one - its not defined in
                     if (((IdPropertyDescriptor) col).definedByDB)
                        continue;
                     val = col.dbTypeDesc.getIdColumnValue(val, 0);
                     colType = col.dbTypeDesc.getIdDBColumnType(0);
                  }
                  else {
                     val = mapper.getPropertyValue(arrInst, false, false);
                     colType = dbColumnType;
                  }
               }
               else if (col.refDBTypeDesc != null) {
                  val = col.refDBTypeDesc.getIdColumnValue(val, 0);
                  colType = col.refDBTypeDesc.getIdDBColumnType(0);
               }
               else
                  colType = dbColumnType;
               if (columnNames != null)
                  columnNames.add(col.columnName);
               columnTypes.add(colType);
               columnValues.add(val);
            }
         }
         else {
            DBTypeDescriptor arrDesc = col.refDBTypeDesc;
            if (reverseItem) {
               if (col instanceof IdPropertyDescriptor) {
                  // The database will provide this one - its not defined in
                  if (((IdPropertyDescriptor) col).definedByDB)
                     continue;
               }
               arrDesc = col.dbTypeDesc;
            }
            if (arrDesc != null) {
               for (int ci = 0; ci < numCols; ci++) {
                  if (columnNames != null)
                     columnNames.add(col.getColumnName(ci));
                  columnTypes.add(arrDesc.getIdDBColumnType(ci));
                  columnValues.add(arrDesc.getIdColumnValue(val, ci));
               }
            }
            else
               System.err.println("*** Multi column key with no way to map columns");
         }
      }
   }

   public void insertTransientRefs(boolean doPreRefs) {
      Object inst = dbObject.getInst();

      List<DBPropertyDescriptor> allProps = dbObject.dbTypeDesc.allDBProps;
      int psz = allProps.size();

      for (int i = 0; i < psz; i++) {
         DBPropertyDescriptor prop = allProps.get(i);
         if (prop.typeIdProperty)
            continue;

         IBeanMapper mapper = prop.getPropertyMapper();
         if (prop.refDBTypeDesc != null) {
            if ((prop.readOnly && doPreRefs) || (!prop.readOnly && !doPreRefs))
               continue;
            if (prop.multiRow) {
               List<IDBObject> refList = (List<IDBObject>) mapper.getPropertyValue(inst, false, false);
               int rsz = refList == null ? 0 : refList.size();
               for (int j = 0; j < rsz; j++) {
                  IDBObject ref = refList.get(j);
                  if (ref == null)
                     continue;
                  DBObject refObj = ref.getDBObject();
                  if (!refObj.isPendingInsert()) {
                     if (refObj.isTransient()) {
                        if (DBUtil.verbose)
                           DBUtil.verbose(" Inserting reference: " + ref.getObjectId() + " from: " + dbObject.dbTypeDesc.getTypeName() + "." + mapper + "[]");
                        ref.dbInsert();
                     }
                     else
                        refObj.dbUpdate();
                  }
               }
            }
            else {
               IDBObject refInst = (IDBObject) mapper.getPropertyValue(inst, false, false);
               if (refInst != null) {
                  DBObject refDBObj = refInst.getDBObject();
                  if (refDBObj.isTransient() && !refDBObj.isPendingInsert()) {
                     if (DBUtil.verbose)
                        DBUtil.verbose(" Inserting reference: " + refInst.getObjectId() + " from: " + dbObject.dbTypeDesc.getTypeName() + "." + mapper);
                     refInst.dbInsert();
                  }
               }
            }
         }
      }
   }

   protected int doDelete(TableDescriptor deleteTable) {
      if (deleteTable.isReadOnly())
         return 0;

      DBTypeDescriptor dbTypeDesc = dbObject.dbTypeDesc;
      boolean isPrimary = deleteTable.primary;
      DBPropertyDescriptor versProp = isPrimary && this instanceof VersionedOperation ? dbTypeDesc.versionProperty : null;
      long version = -1;
      if (versProp != null)
         version = ((VersionedOperation) this).version;

      List<IdPropertyDescriptor> idCols = deleteTable.getIdColumns();

      ArrayList<String> columnNames = new ArrayList<String>();
      ArrayList<DBColumnType> columnTypes = new ArrayList<DBColumnType>();
      ArrayList<Object> columnValues = new ArrayList<Object>();

      List<String> nullProps = null;
      IDBObject inst = dbObject.getInst();
      for (int i = 0; i < idCols.size(); i++) {
         IdPropertyDescriptor idCol = idCols.get(i);
         IBeanMapper mapper = idCol.getPropertyMapper();
         Object val = mapper.getPropertyValue(inst, false, false);
         if (val == null) {
            if (nullProps == null)
               nullProps = new ArrayList<String>();
            nullProps.add(idCol.propertyName);
         }
         columnValues.add(val);
         columnNames.add(idCol.columnName);
         columnTypes.add(idCol.getDBColumnType());
      }

      if (nullProps != null)
         throw new IllegalArgumentException("Null id properties for DBObject in delete: " + nullProps);

      int numCols = columnNames.size();

      StringBuilder sb = new StringBuilder();
      sb.append("DELETE FROM ");
      DBUtil.appendIdent(sb, null, deleteTable.tableName);
      sb.append(" WHERE ");

      StringBuilder logSB = DBUtil.verbose ? new StringBuilder(sb) : null;

      for (int i = 0; i < numCols; i++) {
         if (i != 0) {
            DBUtil.append(sb, logSB, " AND ");
         }
         DBUtil.appendIdent(sb, logSB, columnNames.get(i));
         sb.append(" = ?");
         if (logSB != null) {
            logSB.append(" = ");
            logSB.append(DBUtil.formatValue(columnValues.get(i), columnTypes.get(i)));
         }
      }

      if (versProp != null) {
         DBUtil.append(sb, logSB, " AND ");
         DBUtil.appendIdent(sb, logSB, versProp.columnName);
         sb.append(" = ?");
         if (logSB != null) {
            logSB.append(" = ");
            logSB.append(DBUtil.formatValue(version, versProp.getDBColumnType()));
         }
      }

      try {
         if (!dbTypeDesc.dbReadOnly) {
            Connection conn = transaction.getConnection(dbTypeDesc.dataSourceName);
            String statementStr = sb.toString();
            PreparedStatement st = conn.prepareStatement(statementStr);

            for (int i = 0; i < numCols; i++) {
               DBUtil.setStatementValue(st, i+1, columnTypes.get(i), columnValues.get(i));
            }
            if (versProp != null)
               DBUtil.setStatementValue(st, numCols+1, versProp.getDBColumnType(), version);

            int numDeleted = st.executeUpdate();

            if (deleteTable.primary) {
               if (numDeleted != 1) {
                  if (numDeleted == 0) {
                     if (versProp == null)
                        DBUtil.error("Delete from primary table failed to remove row for: " + dbObject);
                     else
                        DBUtil.info("Delete - StaleDataException for versioned object: " + dbObject);
                     throw new StaleDataException(dbObject, version);
                  }
                  else
                     DBUtil.error("Delete from primary table expected to remove one row actually removed: " + numDeleted + " for: " + dbObject);
               }
            }

            if (logSB != null) {
               if (numDeleted == 1)
                  logSB.append(" -> removed one row");
               else if (!deleteTable.multiRow)
                  logSB.append(" -> ***error - delete removed " + numDeleted + " rows") ;
               else
                  logSB.append(" -> multi table removed: " + numDeleted);
               if (versProp != null)
                  logSB.append("  - version: " + version);
            }
         }
         else {
            if (logSB != null) {
               logSB.append(" (dbDisabled)");
            }
         }

         if (logSB != null)
            DBUtil.info(logSB);

         if (deleteTable.primary) {
            dbTypeDesc.removeInstance(dbObject, true);
         }
      }
      catch (SQLException exc) {
         throw new IllegalArgumentException("*** Delete: " + dbObject + " failed with DB error: " + exc);
      }
      return 1;
   }

}

