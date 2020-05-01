package sc.db;

/**
 * Set on a class to configure how that class is stored in the database. During code processing, this annotation will
 * augment the class with the sc.db.IDBObject interface and generate a DBTypeDescriptor that's initialized along with
 * the type.
 */
public @interface DBTypeSettings {
   /** The primary table name for this type, or for a sub-type of another DB type the name of the default table for all that type's properties */
   String tableName() default "";

   // TODO: do we need this? Right now we also just create an aux-table the first time a property refers to it
   String auxTables() default "";
   /**
    * Set to the DBDataSource to use for this type's persistence. DBDataSources can be defined in layer definition files or
    * created programmatically.
    */
   String dataSourceName() default "";
   /** Set to false to turn off persistence of this type in the database */
   boolean persist() default true;
   // TODO: need more work to make cache policies configurable - the goal being that they are configured as part of the framework
   // so that application code without awareness of how caching is performed.
   boolean cacheEnabled() default true;
   // TODO: not yet implemented
   long expireTimeMillis() default -1;
   // TODO: add a way to enable checking of the versionProperty each time the cache is accessed, or periodically to automatically refresh
   // the cache more efficiently
   // boolean versionCheck() - to enable version checking to keep cache valid?

   /**
    * Set to the name of a version property to enable optimistic concurrency for this type. The version property should be an
    * integer that's incremented on each update. For client/server apps, if the read and write to properties happens in separate
    * transactions, the version prop can be sent to the client along with the other values, and set again before the update to
    * be sure it has not changed since it was read.
    */
   String versionProp() default "";

   /** Must be set any subclasses used in a type hierarchy. Used to identify instances of this type in the database */
   int typeId() default -1;

   /** Set to false on a type, or a base-type so that properties in the base-type are not persisted */
   boolean inheritProperties() default true;

   /** Set to false so we store this type in it's own primary table even if the base-type is stored in a table. */
   boolean storeInExtendsTable() default true;
   /**
    * Set to true to make all properties in this type part of the dynamic part of the schema - i.e. add/remove properties
    * without schema changes. If the type has no dyn columns yet, setting this to true will on the type will allow it
    * to have dynamic columns in the future without a schema change.
    */
   boolean defaultDynColumn() default false;
}
