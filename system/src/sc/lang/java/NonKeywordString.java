/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.parser.IString;
import sc.parser.PString;

/** Sentinel class identifying a constant string which should not be treated as a keyword for grammar purposes, even though it is. */
public class NonKeywordString extends PString {
   public NonKeywordString(String str) {
      super(str);
   }

   public IString substring(int ix) {
      return new NonKeywordString(toString().substring(ix));
   }
}
