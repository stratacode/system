/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

public class GenerateError {
   String errorType;
   int progress;

   public GenerateError() {
   }

   public GenerateError(String t) {
      errorType = t;
   }

   public String toString() {
      return "Generation error: " + errorType;
   }
}
