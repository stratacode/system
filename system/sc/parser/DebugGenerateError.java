/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

public class DebugGenerateError extends GenerateError {
   Parselet parselet;
   Object value;

   public DebugGenerateError(GenerateError err, Parselet p, Object v) {
      errorType = err.errorType;
      parselet = p;
      value = v;
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(errorType);
      sb.append(": ");
      sb.append(parselet.toString());
      sb.append(": for value: ");
      sb.append(value);
      return sb.toString();
   }
}
