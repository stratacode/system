/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

/** 
 * An annotation you can use on an inner object to control the order of the children in the child
 * list.  Specify the name of the object you want to place this object before. 
 */
public @interface AddBefore {
   String value();
}
