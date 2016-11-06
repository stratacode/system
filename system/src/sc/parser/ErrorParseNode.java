/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.util.StringUtil;

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

   public void setSemanticValue(Object value, boolean clearOld) {
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

   public void setStartIndex(int ix) {
      super.setStartIndex(ix);
      // We are updating both of these because during the reparse we may pull the error out of the parse node and
      // create a new PreErrorParseNode with the old error - which would have a stale startIndex unless we update it here.
      if (error != null)
         error.startIndex = ix;
   }

   public void updateSemanticValue(IdentityHashMap<Object, Object> oldNewMap) {
   }

   public void computeLineNumberForNode(LineFormatContext ctx, IParseNode toFindPN) {
      if (errorText != null) {
         ctx.append(errorText);
         ctx.curLines += ParseUtil.countLinesInNode(errorText);
      }
   }

   @Override
   public void findStartDiff(DiffContext ctx, boolean atEnd, Object parSemVal, ParentParseNode parSemNode, int childIx) {
      if (errorText == null)
         return;
      String text = ctx.text;
      int textLen = text.length();
      int errorLen = errorText.length();
      for (int i = 0; i < errorLen; i++) {
         if (ctx.startChangeOffset >= textLen) {
            return;
         }
         if (errorText.charAt(i) != text.charAt(ctx.startChangeOffset)) {
            if (DiffContext.debugDiffContext)
               ctx = ctx;
            IParseNode last = ctx.lastVisitedNode;
            if (last == null)
               last = this;
            // For error nodes, we want the last visited node to start the diff since it's possible extensions to the content of an error node
            // will change the previously incomplete parsed result.
            ctx.firstDiffNode = last.getParselet().getBeforeFirstNode(last);
            ctx.beforeFirstNode = ctx.firstDiffNode;
            return;
         }
         else {
            ctx.startChangeOffset++;
         }
      }
      if (atEnd && textLen > ctx.startChangeOffset) {
         IParseNode last = ctx.lastVisitedNode;
         ctx.firstDiffNode = last.getParselet().getBeforeFirstNode(last);
         ctx.beforeFirstNode = ctx.firstDiffNode;
      }
   }

   @Override
   public void findEndDiff(DiffContext ctx, Object parSemVal, ParentParseNode parParseNode, int childIx) {
      if (errorText == null)
         return;
      String text = ctx.text;
      int len = errorText.length();
      for (int i = len - 1; i >= 0; i--) {
         // We did not find any differences at the end - this is the case where there are extra chars in the old
         // file and so going from the back, we hit the end of the new file before seeing any changes
         if (ctx.endChangeNewOffset == -1)
            return;
         if (errorText.charAt(i) != text.charAt(ctx.endChangeNewOffset)) {
            ctx.lastDiffNode = this;
            ctx.afterLastNode = ctx.lastVisitedNode;
            ctx.addSameAgainChildren(ctx.lastVisitedNode);
            return;
         }
         else {
            ctx.endChangeOldOffset--;
            ctx.endChangeNewOffset--;
         }
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

   public boolean isErrorNode() {
      return true;
   }

   public boolean isGeneratedTree() {
      return false;
   }

   public void diffParseNode(IParseNode other, StringBuilder diffs) {
      super.diffParseNode(other, diffs);
      if (other instanceof ErrorParseNode) {
         ErrorParseNode otherErr = (ErrorParseNode) other;
         if (!StringUtil.equalStrings(otherErr.errorText, errorText))
            diffs.append("Error text does not match: " + errorText + " != " + otherErr.errorText);
      }
   }
}
