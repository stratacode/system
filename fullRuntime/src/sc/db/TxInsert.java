package sc.db;

import java.util.List;

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
      List<TableDescriptor> tables = dbTypeDesc.auxTables;
      int ct = doInsert(dbTypeDesc.primaryTable);
      if (ct > 0) {
         if (tables != null) {
            for (TableDescriptor table:tables)
               ct += doInsert(table);
         }
         tables = dbTypeDesc.multiTables;
         if (tables != null) {
            for (TableDescriptor table:tables)
               ct += doMultiInsert(table, null, true);
         }
      }
      insertTransientRefs(false);
      return ct;
   }
}
