/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.util.IntStack;

public class OffsetToLineIndex {
   int numChars;
   int numLines;
   IntStack offsets;

   private final static int AVG_CHARS_PER_LINE = 20;

   public OffsetToLineIndex(CharSequence chars) {
      numChars = chars.length();
      offsets = new IntStack(Math.max(numChars/AVG_CHARS_PER_LINE, 5)); // use a resizable int array - pick a reasonable initial size

      for (int i = 0; i < numChars; i++) {
         char c = chars.charAt(i);
         if (c == '\n') {
            offsets.push(i);
         }
      }
      numLines = offsets.size() + 1;
   }

   public int getLineForOffset(int offset) {
      if (offset >= numChars)
         return numLines;

      if (offset == 0)
         return 1;

      // Use a binary search to find the line number for the supplied character offset
      int minLine = 1;
      int maxLine = numLines == 0 ? 1 : numLines;
      int minOffset = 0;
      int maxOffset = numChars;

      int lastCurLine = -1;
      do {
         // We know that minOffset < offset < maxOffset
         int curLine = minLine + (int) ((double) (maxLine - minLine) * ((offset - minOffset) / (double) (maxOffset - minOffset)) + 0.5);

         // We have to make progress
         if (curLine == minLine)
            curLine++;
         if (curLine == maxLine)
            curLine--;

         if (curLine < minLine || curLine > maxLine) {
            System.out.println("*** algorithm error");
         }

         int curOffset = offsets.get(curLine - 1);
         int nextOffset = curLine >= offsets.size() ? numChars : offsets.get(curLine);
         // Is the supplied offset on this line - i.e. it starts after this line starts and before it ends
         if (offset > curOffset) {
            if (offset <= nextOffset)
               return curLine + 1;
            minLine = curLine;
            minOffset = curOffset;
         }
         else if (curLine > 1) {
            maxLine = curLine;
            maxOffset = curOffset;
         }
         else // we are before the first newline
            return 1;

         if (lastCurLine != -1 && lastCurLine == curLine)
            System.out.println("*** algorithm error 3");
         lastCurLine = curLine; // TODO: debug only - to avoid possible infinite loops

         if (minLine >= maxLine) {
            System.out.println("*** algorithm error2");
            return minLine;
         }
      } while (true);
   }
}
