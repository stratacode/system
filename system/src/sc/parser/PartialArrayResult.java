/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

class PartialArrayResult {
   PartialArrayResult(int np, ParentParseNode rn) {
      numProcessed = np;
      resultNode = rn;
   }
   PartialArrayResult(int np, ParentParseNode rn, GenerateError e) {
      this (np, rn);
      error = e;
   }


   int numProcessed;
   ParentParseNode resultNode;
   GenerateError error;

   public String toString() {
      return super.toString();
   }
}
