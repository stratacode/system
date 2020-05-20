package sc.db;

import java.util.ArrayList;
import java.util.Map;

class TxListUpdate<E extends IDBObject> extends TxOperation {
   DBList<E> oldList;
   ArrayList<E> newList;

   PropUpdate propUpdate = null;

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

   public PropUpdate getPropUpdate() {
      if (propUpdate == null) {
         propUpdate = new PropUpdate(oldList.listProp);
         propUpdate.value = newList;
      }
      return propUpdate;
   }

   public int apply() {
      // figure out which rows to add/remove and generate the SQL
      //  cases - mapping table (fromId/toId)
      //  primitive values -
      ArrayList<IDBObject> toRemove = new ArrayList<IDBObject>();
      ArrayList<IDBObject> toInsert = new ArrayList<IDBObject>();
      if (oldList != null) {
         for (int i = 0; i < oldList.size(); i++) {
            IDBObject oldElem = oldList.get(i);
            if (newList == null || !newList.contains(oldElem))
               toRemove.add(oldElem);
         }
      }
      if (newList != null) {
         for (int i = 0; i < newList.size(); i++) {
            IDBObject newElem = newList.get(i);
            if (oldList == null || !oldList.contains(newElem))
               toInsert.add(newElem);
         }
      }
      if (toRemove.size() > 0) {
         int ct = doMultiDelete(oldList.listProp.getTable(), toRemove, false);
         if (ct != toRemove.size())
            DBUtil.error("Failed to remove all of the rows in a list update");
      }
      if (toInsert.size() > 0) {
         int ct = doMultiInsert(oldList.listProp.getTable(), toInsert, false);
         if (ct != toInsert.size())
            DBUtil.error("Failed to insert all of the rows in a list update");
      }
      oldList.updateToList(newList);
      return 0;
   }

   public Map<String,String> validate() {
      return null;
   }
}
