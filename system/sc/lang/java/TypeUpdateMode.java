/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

/** We can call updateType in three cases: 1) we are replacing the same type in the same layer 2) we are adding a new type that modifies this type and 3) we are removing a type  */
public enum TypeUpdateMode {
   Replace, Remove, Add
}
