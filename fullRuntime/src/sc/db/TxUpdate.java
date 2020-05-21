package sc.db;

import sc.type.IBeanMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;

public class TxUpdate extends VersionedOperation {
   public ArrayList<PropUpdate> updateList = new ArrayList<PropUpdate>();
   public TreeMap<String, PropUpdate> updateIndex = new TreeMap<String, PropUpdate>();

   public TxUpdate(DBTransaction tx, DBObject inst) {
      super(tx, inst);
   }

   public int apply() {
      if (applied)
         throw new IllegalArgumentException("Already applied update!");
      applied = true;

      DBTypeDescriptor dbTypeDesc = dbObject.dbTypeDesc;

      int ct = doUpdate(dbTypeDesc.primaryTable);
      List<TableDescriptor> auxTables = dbTypeDesc.auxTables;
      if (auxTables != null) {
         for (TableDescriptor auxTable:auxTables)
            ct += doUpdate(auxTable);
      }

      // TODO: - apply changes to multi tables here

      if (ct == 0)
         System.err.println("*** Warning no properties changed in TxUpdate apply!");

      return ct;
   }

   protected int doUpdate(TableDescriptor updateTable) {
      if (dbObject.isTransient()) {
         throw new IllegalArgumentException("Updating transient object");
      }
      DBTypeDescriptor dbTypeDesc = dbObject.dbTypeDesc;
      TableDescriptor primaryTable = dbTypeDesc.primaryTable;
      boolean isPrimary = primaryTable == updateTable;
      // TODO: if we are only updating a non-aux or multi table in a transaction, do we always join in the primary
      // table just to check and update the version
      DBPropertyDescriptor versProp = isPrimary ? dbTypeDesc.versionProperty : null;
      DBPropertyDescriptor lmtProp = isPrimary ? dbTypeDesc.lastModifiedProperty : null;
      Date lmtValue = null;

      List<IdPropertyDescriptor> idCols = updateTable.getIdColumns();
      List<Object> idVals = new ArrayList<Object>();
      int numIdCols = idCols.size();

      Object inst = dbObject.getInst();

      for (int i = 0; i < numIdCols; i++) {
         IdPropertyDescriptor idCol = idCols.get(i);
         IBeanMapper mapper = idCol.getPropertyMapper();
         Object val = mapper.getPropertyValue(inst, false, false);
         if (val == null) {
            throw new IllegalArgumentException("Null id property: " + idCol.propertyName + " in update");
         }
         idVals.add(val);
      }

      ArrayList<String> columnNames = new ArrayList<String>();
      ArrayList<Object> columnValues = new ArrayList<Object>();
      ArrayList<DBColumnType> columnTypes = new ArrayList<DBColumnType>();
      ArrayList<DBTypeDescriptor> columnRefTypes = new ArrayList<DBTypeDescriptor>();

      // Goes through the updateList property for this update adding the names and values of changed columns
      addUpdatedColumns(updateTable, columnNames, columnTypes, columnValues, columnRefTypes);

      // No changes for this table
      if (columnNames.size() == 0)
         return 0;

      long newVersion = -1;
      if (versProp != null && !columnNames.contains(versProp.columnName)) {
         columnNames.add(versProp.columnName);
         columnTypes.add(versProp.getDBColumnType());
         newVersion = version + 1;
         columnValues.add(newVersion);
         columnRefTypes.add(null);
      }
      if (lmtProp != null && !columnNames.contains(lmtProp.columnName)) {
         columnNames.add(lmtProp.columnName);
         columnTypes.add(lmtProp.getDBColumnType());
         columnValues.add(lmtValue = new Date());
         columnRefTypes.add(null);
      }

      StringBuilder sb = new StringBuilder();
      sb.append("UPDATE ");
      DBUtil.appendIdent(sb, null, updateTable.tableName);
      sb.append(" SET ");
      int numCols = columnNames.size();

      StringBuilder logSB = DBUtil.verbose ? new StringBuilder(sb.toString()) : null;

      for (int i = 0; i < numCols; i++) {
         if (i != 0) {
            DBUtil.append(sb, logSB, ", ");
         }
         DBUtil.appendIdent(sb, logSB, columnNames.get(i));
         DBUtil.append(sb, logSB, " = ");
         sb.append("?");
         if (logSB != null)
            logSB.append(DBUtil.formatValue(columnValues.get(i), columnTypes.get(i), columnRefTypes.get(i)));
      }
      DBUtil.append(sb, logSB, " WHERE ");

      for (int i = 0; i < numIdCols; i++) {
         if (i != 0) {
            DBUtil.append(sb, logSB, " AND ");
         }
         DBUtil.appendIdent(sb, logSB, idCols.get(i).columnName);
         DBUtil.append(sb, logSB, " = ");
         sb.append("?");
         if (logSB != null)
            logSB.append(DBUtil.formatValue(idVals.get(i), DBColumnType.LongId, dbTypeDesc));
      }
      if (versProp != null) {
         long opVersion = version;
         DBUtil.append(sb, logSB, " AND ");
         DBUtil.appendIdent(sb, logSB, versProp.columnName);
         DBUtil.append(sb, logSB, " = ");
         sb.append("?");
         if (logSB != null)
            logSB.append(DBUtil.formatValue(opVersion, versProp.getDBColumnType(), null));
      }

      try {
         if (!dbTypeDesc.dbReadOnly) {
            Connection conn = transaction.getConnection(dbTypeDesc.dataSourceName);
            String updateStr = sb.toString();
            PreparedStatement st = conn.prepareStatement(updateStr);
            int pos = 1;
            for (int i = 0; i < numCols; i++) {
               DBColumnType colType = columnTypes.get(i);
               DBUtil.setStatementValue(st,  pos++, colType, columnValues.get(i));
            }
            for (int i = 0; i < numIdCols; i++) {
               IdPropertyDescriptor idProp = idCols.get(i);
               DBUtil.setStatementValue(st,  pos++, idProp.getDBColumnType(), idVals.get(i));
            }
            if (versProp != null) {
               DBUtil.setStatementValue(st,  pos++, versProp.getDBColumnType(), version);
            }

            int ct = st.executeUpdate();
            // TODO: for auxTables we should track whether we selected the row or not to avoid the extra statement and instead do an 'upsert' - insert with an update on conflict since
            // in the case we are setting properties which were not selected, it's much more likely the row does not exist.
            if (ct == 0) {
               if (versProp != null || isPrimary) {
                  throw new StaleDataException(dbObject, version);
               }
               dbObject.applyUpdates(transaction, updateList, null, 0, null, null);
               doInsert(updateTable);
            }
            else if (ct != 1) {
               throw new UnsupportedOperationException("Invalid return from executeUpdate in doUpdate(): " + ct);
            }
            else {
               dbObject.applyUpdates(transaction, updateList, versProp, newVersion, lmtProp, lmtValue);

               if (logSB != null) {
                  logSB.append(" updated: " + ct);
                  if (versProp != null)
                     logSB.append(" new version: " + newVersion);
                  DBUtil.info(logSB);
               }
            }
         }
         else {
            dbObject.applyUpdates(transaction, updateList, versProp, newVersion, lmtProp, lmtValue);
            if (logSB != null) {
               logSB.append(" updated - dbDisabled ");
               if (versProp != null)
                  logSB.append(" new version: " + newVersion);
               DBUtil.info(logSB);
            }
         }
      }
      catch (SQLException exc) {
         throw new IllegalArgumentException("*** Insert without ids sql error: " + exc);
      }

      return 1;
   }

   private void addUpdatedColumns(TableDescriptor tableDesc, ArrayList<String> columnNames, ArrayList<DBColumnType> columnTypes,
                                  ArrayList<Object> columnValues, ArrayList<DBTypeDescriptor> columnRefTypes) {
      Map<String,Object> tableDynProps = null;

      for (PropUpdate propUpdate:updateList) {
         DBPropertyDescriptor prop = propUpdate.prop;
         if (prop.tableDesc == tableDesc) {
            if (prop.dynColumn) {
               if (tableDynProps == null)
                  tableDynProps = new HashMap<String,Object>();
               tableDynProps.put(prop.propertyName, propUpdate.value);
            }
            else {
               columnNames.add(prop.columnName);
               Object value = propUpdate.value;
               if (prop.refDBTypeDesc != null) {
                  columnTypes.add(prop.refDBTypeDesc.getIdDBColumnType(0));
                  if (value != null) {
                     IDBObject refObj = ((IDBObject) value);
                     // If we are asked to update a property that refers to a transient value, insert it first.
                     if (refObj.getDBObject().isTransient() && !prop.readOnly)
                        refObj.dbInsert(false);
                     value = refObj.getDBId();
                  }
               }
               else
                  columnTypes.add(prop.getDBColumnType());
               columnValues.add(value);
               columnRefTypes.add(prop.refDBTypeDesc);
            }
         }
      }
      if (tableDynProps != null) {
         for (int i = 0; i < tableDesc.columns.size(); i++) {
            DBPropertyDescriptor col = tableDesc.columns.get(i);
            if (col.dynColumn && tableDynProps.get(col.propertyName) == null) {
               tableDynProps.put(col.propertyName, dbObject.getProperty(col.propertyName));
            }
         }
         columnNames.add(DBTypeDescriptor.DBDynPropsColumnName);
         columnTypes.add(DBColumnType.Json);
         columnValues.add(tableDynProps);
         columnRefTypes.add(null);
      }
   }

   public String toString() {
      return "update: " + dbObject + (updateList == null ? "" : " (" + (updateList.size() + (applied ? " applied" : " pending") + " changes)"));
   }

   public Map<String,String> validate() {
      Map<String,String> res = null;
      for (PropUpdate propUpdate:updateList) {
         DBPropertyDescriptor propDesc = propUpdate.prop;
         String propError = propDesc.validate(dbObject, propUpdate.value);
         if (propError != null) {
            if (res == null)
               res = new TreeMap<String,String>();
            res.put(propDesc.propertyName, propError);
         }
      }
      return res;
   }
}
