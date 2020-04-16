package sc.db;

public @interface DBTypeSettings {
   String versionProp() default "";
   /** The primary table name for this type, or for a sub-type of another DB type the name of the default table for all that type's properties */
   String tableName() default "";
   String auxTables() default "";
   String dataSourceName() default "";
   boolean persist() default true;
   boolean cacheEnabled() default true;
   // boolean versionCheck() - to enable version checking to keep cache valid?
   long expireTimeMillis() default -1;
   /** Must be set any subclasses used in a type hierarchy. Used to identify instances of this type in the database */
   int typeId() default -1;

   /** Set to false on a type, or a base-type so that properties in the base-type are not persisted */
   boolean inheritProperties() default true;
}
