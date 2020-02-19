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
         for (TableDescriptor table:dbTypeDesc.auxTables)
            ct += doDelete(table);
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
