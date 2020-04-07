package sc.db;

/**
 * Attach SQL commands to a type, to be inserted into the DDL for that type. Although tables/columns
 * are assembled via properties, a way to add functions, extra indexes, sequences etc.
 */
public @interface SchemaSQL {
   String value() default "";
}
