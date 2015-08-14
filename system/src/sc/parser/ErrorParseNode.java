/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import java.util.IdentityHashMap;

/**
 * Represents an error in the parse-node tree that was parsed.  The errorText may be the empty string if
 * the error does not correspond to any actual text in the document (i.e. it represents something missing like
 * a semicolon).
 */
public class ErrorParseNode extends AbstractParseNode {
   ParseError error;
   String errorText;

   public ErrorParseNode(ParseError err, String errText) {
      error = err;
      startIndex = err == null ? -1 : err.startIndex;
      errorText = errText;
   }

   public Object getSemanticValue() {
      return error == null || !(error.partialValue instanceof IParseNode) ? null : ((IParseNode) error.partialValue).getSemanticValue();
   }

   public void setSemanticValue(Object value) {
      if (value != null)
         throw new UnsupportedOperationException();
   }

   public String toDebugString() {
      return "<errorParseNode>";
   }

   public boolean refersToSemanticValue(Object v) {
      return false;
   }

   public void format(FormatContext ctx) {
      // This ensures we render the document the way it was originally... including the error text.
      ctx.append(errorText);
   }

   public int firstChar() {
      return 0;
   }

   public void updateSemanticValue(IdentityHashMap<Object, Object> oldNewMap) {
   }

   public void computeLineNumberForNode(LineFormatContext ctx, IParseNode toFindPN) {
      if (errorText != null) {
         ctx.append(errorText);
         ctx.curLines += ParseUtil.countLinesInNode(errorText);
      }
   }

   public String toString() {
      return errorText == null ? "" : errorText;
   }

   public Parselet getParselet() {
      return error.parselet;
   }

   public String getErrorMessage() {
      return error.errorString();
   }

   public String getErrorText() {
      return errorText;
   }

   public Object getSkippedValue() {
      return this;
   }

   public boolean canSkip() {
      return false;
   }
}