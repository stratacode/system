/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.ISemanticNode;

import java.util.IdentityHashMap;
import java.util.List;

/**
 * For newline and indentation formatting of languages.
 */
public abstract class FormattingParseNode extends AbstractParseNode {
   public void computeLineNumberForNode(LineFormatContext ctx, IParseNode toFindPN) {
      format(ctx);
   }

   public boolean refersToSemanticValue(Object obj) {
      return false;
   }

   public ISemanticNode getNodeAtLine(NodeAtLineCtx ctx, int lineNum) {
      ISemanticNode res = super.getNodeAtLine(ctx, lineNum);
      if (res != null)
         return res;

      format(ctx);
      if (ctx.curLines >= lineNum)
         return ctx.lastVal;
      return null;
   }

   public CharSequence toSemanticString() {
      return "";
   }

   // No state in this parse nodes so do not copy them
   public FormattingParseNode deepCopy() {
      return this;
   }

   public void updateSemanticValue(IdentityHashMap<Object, Object> oldNewMap) {
   }

   public Object getSemanticValue() {
      return null;
   }

   public void setSemanticValue(Object obj, boolean clearOld, boolean restore) {
      if (obj == null)
         return;
      throw new UnsupportedOperationException();
   }

   public Object getSkippedValue() {
      return null;
   }

   // TODO: we may need to implement these in the case we've generated some of the nodes we
   // are editing.  Need to call format(..) to determine the textual representation and use
   // that in the diff.  This will require that DiffContext maintain the history so we know
   // where we are in the parse tree.  Or we could just reparse the entire file on the first change
   // and only do the incremental stuff on the second change.
   public void findStartDiff(DiffContext ctx, boolean atEnd, Object parSemVal, ParentParseNode parParseNode, int childIx) {
      String nodeText = formatString(parSemVal, parParseNode, childIx, false);
      String text = ctx.text;
      int textLen = text.length();
      int nodeTextLen = nodeText.length();
      for (int i = 0; i < nodeTextLen; i++) {
         if (ctx.startChangeOffset >= textLen) {
            return;
         }
         if (nodeText.charAt(i) != text.charAt(ctx.startChangeOffset)) {
            IParseNode last = ctx.lastVisitedNode;
            // For formatting nodes, like error nodes, we want the last visited node to start the diff since it's possible extensions to the content of an error node
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

   public void findEndDiff(DiffContext ctx, Object parSemVal, ParentParseNode parParseNode, int childIx) {
      String nodeText = formatString(parSemVal, parParseNode, childIx, false);
      if (nodeText == null)
         return;
      String text = ctx.text;
      int len = nodeText.length();
      for (int i = len - 1; i >= 0; i--) {
         if (nodeText.charAt(i) != text.charAt(ctx.endChangeNewOffset)) {
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

   public boolean isGeneratedTree() {
      return true; // These formatting nodes only live in generated models
   }

   public void addParseErrors(List<ParseError> res, int max) {
   }
}
