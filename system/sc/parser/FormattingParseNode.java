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

   public void setSemanticValue(Object obj) {
      if (obj == null)
         return;
      throw new UnsupportedOperationException();
   }

   public Object getSkippedValue() {
      return null;
   }
}
