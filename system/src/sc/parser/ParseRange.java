/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

/**
 * Simple utility class used to store a range start/end values.
 */
public class ParseRange {
   public int startIx, endIx;

   public ParseRange(int startIx, int endIx) {
      this.startIx = startIx;
      this.endIx = endIx;
   }

   public void mergeInto(int start, int end) {
      startIx = Math.min(start, startIx);
      endIx = Math.max(end, endIx);
   }
}
