/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import java.io.File;
import java.text.MessageFormat;

public class ParseError {
   public String errorCode;
   public Object[] errorArgs;
   public int startIndex, endIndex;
   public Parselet parselet;
   public Object partialValue;
   public boolean eof;
   public Object continuationValue; // True for enablePartialValues when this error partially extends the list in the value
   public boolean optionalContinuation; // True for optional errors which add additional info so we can string together the partial value of a parent

   private final boolean debugErrors = false;

   public ParseError(String ec, Object[] a, int sp, int ep) {
      this(null, ec, a, sp, ep);
   }

   public ParseError(Parselet plt, String ec, Object[] a, int sp, int ep) {
      this.parselet = plt;
      this.errorCode = ec;
      errorArgs = a;
      startIndex = sp;
      endIndex = ep;
   }

   public String toString() {
      return errorString() + "at character: " + endIndex + (debugErrors && endIndex != startIndex ? " (ending at:" + endIndex : "");
   }

   private String errorStringWithLineNumbers(String startStr, String endStr, String nearStr) {
      String startsAt = "";
      if (endIndex - startIndex > 32)
         startsAt = " starting at: " + startStr;
      return errorString() + "at " + endStr +
              (debugErrors && endIndex != startIndex ? " (starting at " + startStr + ")" : "") +
              " near: " + nearStr + startsAt;
   }

   public String errorStringWithLineNumbers(File file) {
      return errorStringWithLineNumbers(ParseUtil.charOffsetToLine(file, startIndex),
              ParseUtil.charOffsetToLine(file, endIndex),
              ParseUtil.getInputString(file, startIndex, 8));
   }

   public String errorStringWithLineNumbers(String str) {
      return errorStringWithLineNumbers(ParseUtil.charOffsetToLine(str, startIndex),
              ParseUtil.charOffsetToLine(str, endIndex),
              str.substring(Math.max(0,Math.min(startIndex,str.length()-5)), Math.max(0,Math.min(str.length(),startIndex + 8))));
   }

   public String errorString() {
      if (errorCode == null)
         return "Syntax error ";
      MessageFormat formatter = new MessageFormat(errorCode);
      return formatter.format(errorArgs) + " ";
   }
}
