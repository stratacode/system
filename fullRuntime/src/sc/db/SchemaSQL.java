/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.db;

/**
 * Add SQL commands to a type, to be inserted into the DDL for that type. Use it to
 * add functions, extra indexes, sequences etc. Specify either the value for
 * defining the SQL command inline, or specify file="myClassProcs.ddl" to store
 * the commands in a separate DDL file that's stored in the layer path accessible
 * from the layer for the type.
 */
public @interface SchemaSQL {
   /** Specifies SQL commands inline. Each should be terminated with a ; just like in SQL. */
   String value() default "";
   /** 
     * Specifies the relative path to a file name of a .ddl file. Usually placed next to the referencing source file but looked up
     * as a layer resource file.
     */
   String file() default"";
}
