package sc.db;

public @interface DBPropertySettings {
   String columnName() default "";
   String columnType() default "";
   boolean required() default false;
   boolean unique() default false;
   boolean onDemand() default false;
   boolean indexed() default true;
   String selectGroup() default "";
   String tableName() default "";
   String dataSourceName() default "";
   String reverseProperty() default "";
   String dbDefault() default "";
}
