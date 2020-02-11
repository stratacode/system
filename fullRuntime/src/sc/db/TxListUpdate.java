package sc.db;

import java.util.ArrayList;

class TxListUpdate<E> extends TxOperation {
   DBList<E> oldList;
   DBPropertyDescriptor listProp;
   ArrayList<E> newList;

   public TxListUpdate(DBTransaction tx, DBObject dbObject, DBList<E> oldList, boolean emptyList) {
      super(tx, dbObject);
      this.oldList = oldList;
      if (oldList != null) {
         newList = emptyList ? new ArrayList<E>() : new ArrayList<E>(oldList);
      }
      else {
         newList = new ArrayList<E>();
      }
   }

   public int apply() {
      // figure out which rows to add/remove and generate the SQL
      //  cases - mapping table (fromId/toId)
      //  primitive values -
      System.err.println("*** TODO: apply list update");

      return 0;
   }
}
