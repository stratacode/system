package sc.db;

import sc.obj.IObjectId;

import java.util.Map;

public interface IDBObject extends IObjectId {
   void dbInsert();

   void dbDelete();

   int dbUpdate();

   /** Returns Map of propertyName to error message by calling validateX methods for any changed properties. */
   Map<String,String> dbValidate();

   /**
    * Used to either select a 'protototype instance' or refresh all previously selected groups on a persistent instance.
    * Returns false if the prototype instance does not exist, or the persistent instance does not exist
    */
   boolean dbRefresh();

   DBObject getDBObject();

   Object getDBId();
}
