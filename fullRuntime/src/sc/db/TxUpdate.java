package sc.db;

import sc.type.IBeanMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

public class TxUpdate extends TxOperation {
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

      ArrayList<DBPropertyDescriptor> columnProps = new ArrayList<DBPropertyDescriptor>();
      ArrayList<Object> columnValues = new ArrayList<Object>();

      addColumnsAndValues(updateTable, columnProps, columnValues);

      // No changes for this table
      if (columnProps.size() == 0)
         return 0;

      StringBuilder sb = new StringBuilder();
      sb.append("UPDATE ");
      DBUtil.appendIdent(sb, updateTable.tableName);
      sb.append(" SET ");
      int numCols = columnProps.size();
      for (int i = 0; i < numCols; i++) {
         if (i != 0) {
            sb.append(", ");
         }
         DBUtil.appendIdent(sb, columnProps.get(i).columnName);
         sb.append(" = ?");
      }
      sb.append(" WHERE ");
      for (int i = 0; i < numIdCols; i++) {
         if (i != 0) {
            sb.append(", ");
         }
         DBUtil.appendIdent(sb, idCols.get(i).columnName);
         sb.append(" = ?");
      }

      try {
         Connection conn = transaction.getConnection(dbTypeDesc.dataSourceName);
         PreparedStatement st = conn.prepareStatement(sb.toString());
         int pos = 1;
         for (int i = 0; i < numCols; i++) {
            DBPropertyDescriptor colProp = columnProps.get(i);
            DBUtil.setStatementValue(st,  pos++, colProp.getPropertyMapper().getPropertyType(), columnValues.get(i));
         }
         for (int i = 0; i < numIdCols; i++) {
            IdPropertyDescriptor idProp = idCols.get(i);
            DBUtil.setStatementValue(st,  pos++, idProp.getPropertyMapper().getPropertyType(), idVals.get(i));
         }

         int ct = st.executeUpdate();
         // TODO: for auxTables we should track whether we fetched the row or not to avoid the extra statement and instead do an 'upsert' - insert with an update on conflict since
         // in the case we are setting properties which were not fetched, it's much more likely the row does not exist.
         if (ct == 0) {
            if (isPrimary)
               throw new IllegalArgumentException("Attempt to update primary table with non-existent row for type: " + dbTypeDesc + idVals);
            dbObject.applyUpdates(updateList);
            doInsert(updateTable);
         }
         else if (ct != 1) {
            throw new UnsupportedOperationException("Invalid return from executeUpdate in doUpdate(): " + ct);
         }
         else
            dbObject.applyUpdates(updateList);
      }
      catch (SQLException exc) {
         throw new IllegalArgumentException("*** Insert without ids sql error: " + exc);
      }
      return 1;
   }

   private void addColumnsAndValues(TableDescriptor tableDesc, ArrayList<DBPropertyDescriptor> columnProps, ArrayList<Object> columnValues) {
      for (PropUpdate propUpdate:updateList) {
         DBPropertyDescriptor prop = propUpdate.prop;
         if (prop.tableDesc == tableDesc) {
            columnProps.add(prop);
            columnValues.add(propUpdate.value);
         }
      }
   }

   public String toString() {
      return "update: " + dbObject + (updateList == null ? "" : " (" + (updateList.size() + (applied ? " applied" : " pending") + " changes)"));
   }
}
