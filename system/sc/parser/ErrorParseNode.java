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
      return error;
   }

   public void setSemanticValue(Object value) {
      throw new UnsupportedOperationException();
   }

   public String toDebugString() {
      return "<errorParseNode>";
   }

   public boolean refersToSemanticValue(Object v) {
      return false;
   }

   public void format(FormatContext ctx) {
   }

   public int firstChar() {
      return 0;
   }

   public void updateSemanticValue(IdentityHashMap<Object, Object> oldNewMap) {
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
}
