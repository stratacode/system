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
      ArrayList<Object> columnTypes = new ArrayList<Object>();
      ArrayList<Object> columnValues = new ArrayList<Object>();

      ArrayList<IdPropertyDescriptor> dbIdCols = null;

      List<String> nullProps = null;
      IDBObject inst = dbObject.getInst();
      for (int i = 0; i < idCols.size(); i++) {
         IdPropertyDescriptor idCol = idCols.get(i);
         if (isPrimary && idCol.definedByDB) {
            if (dbIdCols == null)
               dbIdCols = new ArrayList<IdPropertyDescriptor>();
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
            columnTypes.add(idCol.getPropertyMapper().getPropertyType());
         }
      }

      if (nullProps != null)
         throw new IllegalArgumentException("Null id properties for DBObject in insert: " + nullProps);

      int idSize = columnNames.size();
      addColumnsAndValues(dbTypeDesc, insertTable.columns, columnNames, columnTypes, columnValues);

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
            logSB.append(DBUtil.formatValue(columnValues.get(i)));
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

      try {
         Connection conn = transaction.getConnection(dbTypeDesc.dataSourceName);
         String statementStr = sb.toString();
         PreparedStatement st = conn.prepareStatement(statementStr);

         for (int i = 0; i < numCols; i++) {
            DBUtil.setStatementValue(st, i+1, columnTypes.get(i), columnValues.get(i));
         }

         if (dbIdCols != null) {
            ResultSet rs = st.executeQuery();
            if (!rs.next())
               throw new IllegalArgumentException("Missing returned id result for insert with definedByDB ids: " + dbIdCols);
            for (int i = 0; i < dbIdCols.size(); i++) {
               IdPropertyDescriptor dbIdCol = dbIdCols.get(i);
               IBeanMapper mapper = dbIdCol.getPropertyMapper();
               Object id = DBUtil.getResultSetByIndex(rs, i+1, dbIdCol);
               mapper.setPropertyValue(inst, id);

               if (logSB != null) {
                  if (i == 0) {
                     logSB.append(" -> (");
                  }
                  else
                     logSB.append(", ");
                  logSB.append(id);
               }
            }

            if (logSB != null) {
               logSB.append(")");
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

         if (isPrimary) {
            dbTypeDesc.registerInstance(inst);
            dbObject.setTransient(false);
            // Mark all of the property queries in this type as fetched so we don't re-query them until the cache is invalidated
            int numFetchQueries = dbTypeDesc.getNumFetchPropQueries();
            for (int f = 0; f < numFetchQueries; f++) {
               dbObject.fstate |= DBObject.FETCHED << (f*2);
            }
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
      System.err.println("*** doMultiDelete not yet implemented");
      return 0;
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

      DBPropertyDescriptor multiValueProp = insertTable.columns.get(0);
      IBeanMapper multiValueMapper = multiValueProp.getPropertyMapper();
      if (useCurrent)
         propList = (List<IDBObject>) multiValueMapper.getPropertyValue(parentInst, false, false);
      int numInsts;
      if (propList == null || (numInsts = propList.size()) == 0)
         return 0;

      List<IdPropertyDescriptor> idCols = insertTable.getIdColumns();

      ArrayList<String> columnNames = new ArrayList<String>();
      ArrayList<Object> columnTypes = new ArrayList<Object>();
      ArrayList<Object> columnValues = new ArrayList<Object>();

      ArrayList<Object> idVals = new ArrayList<Object>();

      ArrayList<IdPropertyDescriptor> dbIdCols = null;

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
               columnTypes.add(idCol.getPropertyMapper().getPropertyType());
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
               columnTypes.add(idCol.getPropertyMapper().getPropertyType());
               columnValues.add(idVals.get(ci));
            }
            addArrayValues(dbTypeDesc, arrInst, insertTable.columns, null, columnTypes, columnValues);
         }

         if (ix != 0) {
            append(sb, logSB, ", ");
         }

         append(sb, logSB, "(");
         for (int ci = 0; ci < numCols; ci++) {
            if (ci != 0) {
               append(sb, logSB, ",");
            }
            sb.append("?");
            if (logSB != null)
               logSB.append(DBUtil.formatValue(columnValues.get(ci+toInsert*numCols)));
         }
         append(sb, logSB, ")");
         toInsert++;
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

      try {
         Connection conn = transaction.getConnection(dbTypeDesc.dataSourceName);
         String insertStr = sb.toString();
         PreparedStatement st = conn.prepareStatement(insertStr);

         for (int ci = 0; ci < columnValues.size(); ci++) {
            DBUtil.setStatementValue(st, ci+1, columnTypes.get(ci), columnValues.get(ci));
         }
         int numInserted = st.executeUpdate();
         if (numInserted != toInsert)
            DBUtil.error("Insert of: " + toInsert + " rows inserted: " + numInserted + " instead for: " + dbTypeDesc);

         if (logSB != null) {
            logSB.append(" -> inserted ");
            logSB.append(numInserted);
            DBUtil.info(logSB);
         }

      }
      catch (SQLException exc) {
         throw new IllegalArgumentException("*** Insert: " + dbTypeDesc + " with dbIdCols: " + dbIdCols + " returned failed: " + exc);
      }
      return 1;
   }

   static void append(StringBuilder sb, StringBuilder logSB, CharSequence val) {
      sb.append(val);
      if (logSB != null)
         logSB.append(val);
   }

   private void addColumnsAndValues(DBTypeDescriptor dbTypeDesc, List<? extends DBPropertyDescriptor> cols,
                                    ArrayList<String> columnNames, ArrayList<Object> columnTypes, ArrayList<Object> columnValues) {
      Object inst = dbObject.getInst();
      for (DBPropertyDescriptor col:cols) {
         int numCols = col.getNumColumns();
         IBeanMapper mapper = col.getPropertyMapper();
         Object val = mapper.getPropertyValue(inst, false, false);
         if (numCols == 1) {
            if (val != null) {
               if (col.refDBTypeDesc != null) {
                  val = col.refDBTypeDesc.getIdColumnValue(val, 0);
               }
               columnNames.add(col.columnName);
               columnTypes.add(col.getPropertyMapper().getPropertyType());
               columnValues.add(val);
            }
         }
         else {
            if (col.refDBTypeDesc != null) {
               for (int ci = 0; ci < numCols; ci++) {
                  columnNames.add(col.getColumnName(ci));
                  columnTypes.add(col.refDBTypeDesc.getIdColumnType(ci));
                  columnValues.add(col.refDBTypeDesc.getIdColumnValue(val, ci));
               }
            }
            else
               System.err.println("*** Multi column key with no way to map columns");
         }
      }
   }

   private void addArrayValues(DBTypeDescriptor dbTypeDesc, IDBObject arrInst, List<? extends DBPropertyDescriptor> cols,
                                    ArrayList<String> columnNames, ArrayList<Object> columnTypes, ArrayList<Object> columnValues) {
      if (cols.size() != 1)
         throw new IllegalArgumentException("Only one multi-property supported for now!");
      for (DBPropertyDescriptor col:cols) {
         int numCols = col.getNumColumns();
         IBeanMapper mapper = col.getPropertyMapper();
         Object val = arrInst;
         if (numCols == 1) {
            if (arrInst != null) {
               if (col.refDBTypeDesc != null) {
                  val = col.refDBTypeDesc.getIdColumnValue(val, 0);
               }
               if (columnNames != null)
                  columnNames.add(col.columnName);
               columnTypes.add(mapper.getPropertyType());
               columnValues.add(val);
            }
         }
         else {
            if (col.refDBTypeDesc != null) {
               for (int ci = 0; ci < numCols; ci++) {
                  if (columnNames != null)
                     columnNames.add(col.getColumnName(ci));
                  columnTypes.add(col.refDBTypeDesc.getIdColumnType(ci));
                  columnValues.add(col.refDBTypeDesc.getIdColumnValue(val, ci));
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
         IBeanMapper mapper = prop.getPropertyMapper();
         if (prop.refDBTypeDesc != null) {
            if ((prop.readOnly && doPreRefs) || (!prop.readOnly && !doPreRefs))
               continue;
            if (prop.multiRow) {
               List<IDBObject> refList = (List<IDBObject>) mapper.getPropertyValue(inst, false, false);
               int rsz = refList.size();
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
}

