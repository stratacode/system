package sc.db;

/**
 * Set on one or more identity properties, used to define the identity for this object. The Id properties will need
 * to be defined for each data source. When definedByDB is true, the insert statement will return the id property values
 */
public @interface IdSettings {
   boolean definedByDB() default true;
   boolean generated() default false;
   String columnName() default "";
   String columnType() default "";
}
