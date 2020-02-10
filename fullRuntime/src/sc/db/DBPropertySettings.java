package sc.db;

public @interface DBPropertySettings {
   boolean onDemand() default false;
   String columnName() default "";
   String columnType() default "";
   boolean allowNull() default false;
   String fetchWith() default "";
   String tableName() default "";
   String dataSourceName() default "";
   String reverseProperty() default "";
}
