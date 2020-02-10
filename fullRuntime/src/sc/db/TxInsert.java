package sc.db;

public class TxInsert extends TxOperation {
   public TxInsert(DBTransaction tx, DBObject inst) {
      super(tx, inst);
   }

   public int apply() {
      if (applied)
         throw new IllegalArgumentException("Already applied insert!");
      applied = true;
      insertTransientRefs(true);
      DBTypeDescriptor dbTypeDesc = dbObject.dbTypeDesc;
      int ct = doInsert(dbTypeDesc.primaryTable);
      if (ct > 0) {
         if (dbTypeDesc.auxTables != null) {
            for (TableDescriptor table:dbTypeDesc.auxTables)
               ct += doInsert(table);
         }
         if (dbTypeDesc.multiTables != null) {
            for (TableDescriptor table:dbTypeDesc.multiTables)
               ct += doMultiInsert(table);
         }
      }
      insertTransientRefs(false);
      return ct;
   }
}
