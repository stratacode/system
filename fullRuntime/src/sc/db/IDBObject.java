package sc.db;

import sc.obj.IObjectId;

public interface IDBObject extends IObjectId {
   void dbInsert();

   void dbDelete();

   int dbUpdate();

   /**
    * Used to either fetch a 'protototype instance' or refresh all previously fetched groups on a persistent instance.
    * Returns false if the prototype instance does not exist, or the persistent instance does not exist
    */
   boolean dbRefresh();

   DBObject getDBObject();
}