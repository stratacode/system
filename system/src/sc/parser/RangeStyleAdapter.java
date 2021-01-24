/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

/**
 * Used by IDE integrations to hook into the parselets style engine.  You extend this class,
 * override the styleMatched method, and call "styleNode".
*/
public abstract class RangeStyleAdapter implements IStyleAdapter {
   int curOffset;
   FormatContext ctx;

   int pendingStyleStart;
   String pendingStyleName;

   public boolean getNeedsFormattedString() {
      return false;
   }

   public void setFormatContext(FormatContext ctx) {
      this.ctx = ctx;
   }

   public void styleStart(String styleName) {
      if (pendingStyleName != null)
         System.err.println("*** Pending style in progress: " + pendingStyleName + " != " + styleName);
      pendingStyleName = styleName;
      pendingStyleStart = curOffset;
   }

   public void styleEnd(String styleName) {
      if (pendingStyleName == null)
         System.err.println("*** Pending style not in progress for styleEnd: " + styleName);
      else if (!pendingStyleName.equals(styleName))
         System.err.println("*** Pending styles do not match for styleEnd: " + styleName);
      else {
         styleMatched(styleName, null, pendingStyleStart, curOffset);
         pendingStyleName = null;
      }
   }

   public abstract void styleMatched(String styleName, String styleDesc, int startOffset, int endOffset);

   public void styleString(CharSequence codeOut, boolean escape, String styleName, String styleDesc) {
      int startOffset = curOffset;
      int endOffset = startOffset + codeOut.length();

      if (ctx != null)
         ctx.append(codeOut);

      if (styleName != null)
         styleMatched(styleName, styleDesc, startOffset, endOffset);

      curOffset += codeOut.length();
   }
}
