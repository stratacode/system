package sc.db;

public class TxDelete extends TxOperation {
   public TxDelete(DBTransaction tx, DBObject inst) {
      super(tx, inst);
   }

   public int apply() {
      if (applied)
         throw new IllegalArgumentException("Already applied delete!");
      applied = true;

      System.err.println("TXdelete - TBI");
      return 0;
   }
}
