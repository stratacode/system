package sc.db;

/** Set on a property inside of a class with DBTypeSettings to control how or whether the property is mapped to a column in the DB  */
public @interface DBPropertySettings {
   /** Set to false to turn off persistence of this property */
   boolean persist() default true;

   /** Override the default column name to use for this property. The default converts from camelCase to underscore_names */
   String columnName() default "";
   /** A string to define the complete type to use for the type. It should be parseable by the SQLDataType parselet */
   String columnType() default "";
   /** True if this column has the not-null constraint */
   boolean required() default false;
   /** True if this column should have a unique constraint */
   boolean unique() default false;
   /**
    * For associations, onDemand=false will eagerly load the referenced value the first time it's accessed. For single-valued associations
    * an eager join can load the property value in the first query.
    */
   boolean onDemand() default true;
   /**
    * True if this property should have a basic index. For more complex indexes, use the @SchemaSQL annotation to specify
    * the create index statement, or switch to the schema-first approach to defining the schema altogether.
    */
   boolean indexed() default true;
   /** Set to true for properties to be stored in a JSON object stored in the db_dyn_props column */
   boolean dynColumn() default false;
   /**
    * Set to control when this property is queries from the DB. By default, the select group is equal to the tableName of the
    * column for this property. Set this to the name of a different table, or a new name to create a new group of properties
    * to be fetched in a batch the first time any one of them is accessed.
    */
   String selectGroup() default "";
   /**
    * Set to the name of a table for the column of this property. If not set, the property is placed into the default table name
    * of the class.
    */
   String tableName() default "";
   /** Set to provide the data source to find this property. Otherwise, it defaults to the default data source */
   String dataSourceName() default "";
   /** For association properties, provide the name of a corresponding reverse property in the associated type */
   String reverseProperty() default "";
   /** The database expression to use for the default value for this column in the schema */
   String dbDefault() default "";

   // TODO: need to add this and set DBPropertyDescriptor properties
   // String orderBy()
}
