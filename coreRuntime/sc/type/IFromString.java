/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.type;

/** Converts from the output of toString to the string value of an expression that can be used to set this value */
public interface IFromString {
   String fromString(String value);
}
