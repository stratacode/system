package sc.db;

public @interface DBTypeSettings {
   String versionProp() default "";
   String primaryTable() default "";
   String auxTables() default "";
   String dataSourceName() default "";
   boolean persist() default true;
   boolean cacheEnabled() default true;
   // boolean versionCheck() - to enable version checking to keep cache valid?
   long expireTimeMillis() default -1;
}
