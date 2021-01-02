package sc.db;

import java.util.Map;

public class TxDelete extends VersionedOperation {
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
            ct += doMultiDelete(table, null, true, true);
      }
      ct += doDelete(dbTypeDesc.primaryTable);
      //deleteOwnedRefs(false);
      return ct;
   }

   public Map<String,String> validate() {
      return null;
   }

   public boolean supportsBatch() {
      return false;
   }
}
