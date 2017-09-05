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
   // True for optional errors which add additional info so we can string together the partial value of a parent
   public boolean optionalContinuation;

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
      int pvSz = -1;
      Object pv = null;
      for (Object errArg : errorArgs) {
         if (errArg instanceof ParseError) {
            ParseError nestedErr = (ParseError) errArg;
            // Picking the largest error in the list
            CharSequence nestedPv = (CharSequence) nestedErr.partialValue;
            if (nestedPv != null) {
               int newSz = nestedPv.length();
               if (newSz > pvSz) {
                  pv = nestedPv;
                  pvSz = newSz;
               }
            }
         }
      }
      return pv;
   }

   public ParseError propagatePartialValue(Object pv) {
      Parselet errParselet = null;
      if (pv instanceof IParseNode) {
         errParselet = ((IParseNode) pv).getParselet();
         // If we are caching an error at a lower level, don't reuse the same error because then the cached
         // results partial value will point to the wrong parselet and semantic value.
         if (parselet.cacheResults) {
            ParseError propError = this.clone();
            propError.partialValue = pv;
            propError.parselet = errParselet;
            return propError;
         }
      }
      partialValue = pv;
      if (errParselet != null)
         parselet = errParselet;
      return this;
   }
}
