/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

/** Used to walk the parse node tree and find the line number of a given node. */
public class LineFormatContext extends FormatContext {
   public boolean found = false;
   public int curLines = 1; // The first line starts at 1

   int prevChar;

   public LineFormatContext() {
      super(null, -1, 0, null, null);
   }

   // Optimized here so we don't build up the entire string... currently the format methods only require knowing the
   // last character of the input so that's all we store.
   public void append(CharSequence str) {
      int newLines = ParseUtil.countLinesInNode(str);
      curLines += newLines;
      if (str.length() > 0)
         prevChar = str.charAt(str.length() - 1);
   }

   public int prevChar() {
      return prevChar;
   }
}
