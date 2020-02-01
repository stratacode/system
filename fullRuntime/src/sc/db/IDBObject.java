package sc.db;

import sc.obj.IObjectId;

public interface IDBObject extends IObjectId {
   public void dbInsert();

   public void dbDelete();

   public int dbUpdate();
}
