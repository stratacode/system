package sc.db;

public class TxInsert extends TxOperation {
   public TxInsert(DBTransaction tx, DBObject inst) {
      super(tx, inst);
   }

   public int apply() {
      if (applied)
         throw new IllegalArgumentException("Already applied insert!");
      applied = true;
      return doInsert(dbObject.dbTypeDesc.primaryTable);
   }
}
