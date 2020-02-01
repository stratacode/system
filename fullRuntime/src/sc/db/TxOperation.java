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

      ArrayList<DBPropertyDescriptor> columnProps = new ArrayList<DBPropertyDescriptor>();
      ArrayList<Object> columnValues = new ArrayList<Object>();

      ArrayList<IdPropertyDescriptor> dbIdCols = null;

      List<String> nullProps = null;
      Object inst = dbObject.getInst();
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
            columnProps.add(idCol);
         }
      }

      if (nullProps != null)
         throw new IllegalArgumentException("Null id properties for DBObject in insert: " + nullProps);

      int idSize = columnProps.size();
      addColumnsAndValues(dbTypeDesc, insertTable.columns, columnProps, columnValues, false);

      int numCols = columnProps.size();

      // If it's only id columns should we do the insert?
      if (!insertTable.insertWithNullValues && numCols == idSize)
         return 0;

      StringBuilder sb = new StringBuilder();
      sb.append("INSERT INTO ");
      DBUtil.appendIdent(sb, insertTable.tableName);
      sb.append("(");
      for (int i = 0; i < numCols; i++) {
         if (i != 0) {
            sb.append(", ");
         }
         DBUtil.appendIdent(sb, columnProps.get(i).columnName);
      }
      sb.append(") VALUES (");

      for (int i = 0; i < numCols; i++) {
         if (i != 0) {
            sb.append(", ");
         }
         sb.append("?");
      }
      sb.append(")");

      if (dbIdCols != null) {
         sb.append(" RETURNING ");
         for (int i = 0; i < dbIdCols.size(); i++) {
            if (i != 0)
               sb.append(", ");
            DBUtil.appendIdent(sb, dbIdCols.get(i).columnName);
         }
      }

      try {
         Connection conn = transaction.getConnection(dbTypeDesc.dataSourceName);
         PreparedStatement st = conn.prepareStatement(sb.toString());

         for (int i = 0; i < numCols; i++) {
            DBUtil.setStatementValue(st, i+1, columnProps.get(i).getPropertyMapper(), columnValues.get(i));
         }

         if (dbIdCols != null) {
            ResultSet rs = st.executeQuery();
            if (!rs.next())
               throw new IllegalArgumentException("Missing returned id result for insert with definedByDB ids: " + dbIdCols);
            for (int i = 0; i < dbIdCols.size(); i++) {
               IdPropertyDescriptor dbIdCol = dbIdCols.get(i);
               IBeanMapper mapper = dbIdCol.getPropertyMapper();
               Object id = DBUtil.getResultSetByIndex(rs, i+1, mapper);
               mapper.setPropertyValue(inst, id);
            }
         }
         else {
            st.executeUpdate();
         }

         if (isPrimary)
            dbObject.setTransient(false);
      }
      catch (SQLException exc) {
         throw new IllegalArgumentException("*** Insert: " + dbTypeDesc + " with dbIdCols: " + dbIdCols + " returned failed: " + exc);
      }
      return 1;
   }

   private void addColumnsAndValues(DBTypeDescriptor dbTypeDesc, List<? extends DBPropertyDescriptor> cols, ArrayList<DBPropertyDescriptor> columnProps,
                                    ArrayList<Object> columnValues, boolean required) {
      Object inst = dbObject.getInst();
      for (DBPropertyDescriptor col:cols) {
         IBeanMapper mapper = col.getPropertyMapper();
         Object val = mapper.getPropertyValue(inst, false, false);
         if (val != null) {
            columnProps.add(col);
            columnValues.add(val);
         }
         else if (required) {
            throw new IllegalArgumentException("Missing id column value: " + col + " for insert of: " + dbTypeDesc);
         }
      }
   }
}

