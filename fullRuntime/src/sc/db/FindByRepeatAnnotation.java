/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.db;

/** This is required for defining a Repeatable annotation - not part of the api to use */
public @interface FindByRepeatAnnotation {
   FindBy[] value();
}
