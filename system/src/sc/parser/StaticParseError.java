/*
 * Copyright (c) 2016. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

public class StaticParseError extends ParseError {

   public StaticParseError(String errDesc) {
      super(errDesc, null, -1, -1);
   }

   public ParseError propagatePartialValue(Object pv) {
      ParseError newError = this.clone();
      newError.partialValue = pv;
      return newError;
   }
}
