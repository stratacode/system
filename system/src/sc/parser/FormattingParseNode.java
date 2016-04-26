/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.ISemanticNode;

import java.util.IdentityHashMap;

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

   public void setSemanticValue(Object obj, boolean clearOld) {
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
   public void findStartDiff(DiffContext ctx, boolean atEnd) {
      throw new UnsupportedOperationException();
   }

   public void findEndDiff(DiffContext ctx) {
      throw new UnsupportedOperationException();
   }
}
