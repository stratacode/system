package sc.db;

public class TxDelete extends TxOperation {
   public TxDelete(DBTransaction tx, DBObject inst) {
      super(tx, inst);
   }

   public int apply() {
      if (applied)
         throw new IllegalArgumentException("Already applied delete!");
      applied = true;

      //deleteOwnedRefs(true);
      DBTypeDescriptor dbTypeDesc = dbObject.dbTypeDesc;
      int ct = 0;
      if (dbTypeDesc.auxTables != null) {
         for (int i = 0; i < dbTypeDesc.auxTables.size(); i++) {
            TableDescriptor table = dbTypeDesc.auxTables.get(i);
            ct += doDelete(table);
         }
      }
      if (dbTypeDesc.multiTables != null) {
         for (TableDescriptor table:dbTypeDesc.multiTables)
            ct += doMultiDelete(table, null, true);
      }
      ct += doDelete(dbTypeDesc.primaryTable);
      //deleteOwnedRefs(false);
      return ct;
   }
}
