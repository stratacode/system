package sc.db;

public abstract class VersionedOperation extends TxOperation {
   long version;
   public VersionedOperation(DBTransaction tx, DBObject dbObject) {
      super(tx, dbObject);
      DBTypeDescriptor dbTypeDesc = dbObject.dbTypeDesc;
      DBPropertyDescriptor versProp = dbTypeDesc.versionProperty;
      if (versProp != null) {
         Number res = (Number) versProp.getPropertyMapper().getPropertyValue(dbObject.getInst(), false, false);
         if (res == null)
            version = 0;
         else
            version = res.longValue();
      }
   }
}
