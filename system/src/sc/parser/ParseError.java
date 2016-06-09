/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.ISemanticNode;
import sc.layer.SrcEntry;

import java.io.File;
import java.text.MessageFormat;

public class ParseError implements Cloneable, IParseResult {
   public String errorCode;
   public Object[] errorArgs;
   public int startIndex, endIndex;
   public Parselet parselet;
   public Object partialValue;
   public boolean eof;
   public Object continuationValue; // True for enablePartialValues when this error partially extends the list in the value
   public boolean optionalContinuation; // True for optional errors which add additional info so we can string together the partial value of a parent

   private final boolean debugErrors = false;

   public final static String MULTI_ERROR_CODE = "multipleErrors";

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

   public Parselet getParselet() {
      return parselet;
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
      if (errorCode.equals(ParseError.MULTI_ERROR_CODE)) {
         StringBuilder sb = new StringBuilder();
         sb.append("Multiple errors:\n");
         int i = 0;
         for (Object arg:errorArgs) {
            if (i > 3)
               break;
            sb.append("   ");
            sb.append(arg);
            sb.append("\n");
            i++;
         }
         sb.append("\n");
         return sb.toString();
      }
      else {
         MessageFormat formatter = new MessageFormat(errorCode);
         return formatter.format(errorArgs) + " ";
      }
   }

   public boolean isMultiError() {
      return errorCode != null && errorCode.equals(MULTI_ERROR_CODE);
   }

   public ParseError clone() {
      try {
         ParseError res = (ParseError) super.clone();
         return res;
      }
      catch (CloneNotSupportedException exc) {}
      return null;
   }

   private static Object getRootValue(Object val) {
      if (val instanceof ISemanticNode) {
         return ((ISemanticNode) val).getRootNode();
      }
      return val;
   }

   /** Currently the partial value may not be the root node of the semantic value so we need to convert it. */
   public Object getRootPartialValue() {
      Object val = partialValue;
      if (val instanceof ParentParseNode) {
         val = ((ParentParseNode) val).getSemanticValue();
         if (val != null) {
            val = getRootValue(val);
         }
      }
      return val;
   }

   public Object getBestPartialValue() {
      if (partialValue != null)
         return partialValue;
      for (Object errArg : errorArgs) {
         if (errArg instanceof ParseError) {
            ParseError nestedErr = (ParseError) errArg;
            // TODO: for now just return the first one - the errors are already known to have the same start/index and we should have filtered out
            // errors that are parent/child of each other.
            if (nestedErr.partialValue != null) {
               return nestedErr.partialValue;
            }
         }
      }
      return null;
   }

   public ParseError propagatePartialValue(Object pv) {
      partialValue = pv;
      return this;
   }
}
