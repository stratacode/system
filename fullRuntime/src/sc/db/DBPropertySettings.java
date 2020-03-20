package sc.db;

public @interface DBPropertySettings {
   boolean onDemand() default false;
   String columnName() default "";
   String columnType() default "";
   boolean required() default false;
   boolean unique() default false;
   String fetchGroup() default "";
   String tableName() default "";
   String dataSourceName() default "";
   String reverseProperty() default "";
   String dbDefault() default "";
}
