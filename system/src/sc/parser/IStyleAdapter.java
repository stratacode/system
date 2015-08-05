/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

/**
 * Implemented to handle the styling rules for code syntax highlighting.
 */
public interface IStyleAdapter {
   /**
    * For styling in HTML, the styleString method returns the styled string.  For IDEs, it's api drive so no result
    * string is required.
    */
   boolean getNeedsFormattedString();

   void styleStart(String styleName);

   void styleEnd(String styleName);

   void setFormatContext(FormatContext ctx);

   /**
    * Adds a style for the text range specified.  If needsFormattedString is true, the result is used to accumulate the
    * result string.
    */
   public void styleString(CharSequence codeOut, boolean escape, String styleName, String styleDesc);
}
