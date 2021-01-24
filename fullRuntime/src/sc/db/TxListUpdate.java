/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

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
      DBPropertyDescriptor listProp = oldList.listProp;
      if (oldList != null) {
         for (int i = 0; i < oldList.size(); i++) {
            IDBObject oldElem = oldList.get(i);
            if (newList == null || !newList.contains(oldElem)) {
               if (!((DBObject) oldElem.getDBObject()).isTransient() || !listProp.readOnly)
                  toRemove.add(oldElem);
               // else for a bi-directional one-to-many the item has been inserted if it's not transient
            }
         }
      }
      if (newList != null) {
         for (int i = 0; i < newList.size(); i++) {
            IDBObject newElem = newList.get(i);
            if (oldList == null || !oldList.contains(newElem)) {
               if (((DBObject) newElem.getDBObject()).isTransient() || !listProp.readOnly)
                  toInsert.add(newElem);
               // else - if we are adding to a multi-valued one-to-many list that's owned by another type
               // we only do the insert if the element is transient. This way, we can do the batch inserts
               // of only the new items
            }

         }
      }
      int resCt = 0;
      if (toRemove.size() > 0) {
         int ct = doMultiDelete(oldList.listProp.getTable(), toRemove, false, false);
         if (ct != toRemove.size())
            DBUtil.error("Failed to remove all of the rows in a list update");

         resCt = ct;
      }
      if (toInsert.size() > 0) {
         int ct = doMultiInsert(oldList.listProp.getTable(), toInsert, false, false);
         if (ct != toInsert.size())
            DBUtil.error("Failed to insert all of the rows in a list update");
         resCt += ct;
      }
      oldList.updateToList(newList);
      return resCt;
   }

   public Map<String,String> validate() {
      return null;
   }

   public boolean supportsBatch() {
      return false;
   }
}
