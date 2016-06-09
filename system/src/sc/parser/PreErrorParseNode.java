/*
 * Copyright (c) 2016. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import java.util.IdentityHashMap;

/**
 * A parse node which stores an error that precedes a valid parse for a slot.  Used in some cases when
 * skipping errors to force the parse-node tree back into the same shape, even when we had to parse
 * some error text.
 */
public class PreErrorParseNode extends ErrorParseNode {
   IParseNode value;
   public PreErrorParseNode(ParseError err, String errText, IParseNode valueNode) {
      super(err, errText);

      value = valueNode;
   }

   public Object getSemanticValue() {
      return value.getSemanticValue();
   }

   public boolean refersToSemanticValue(Object v) {
      return value.refersToSemanticValue(v);
   }

   public void format(FormatContext ctx) {
      // This ensures we render the document the way it was originally... including the error text.
      ctx.append(errorText);
      value.format(ctx);
   }

   public int firstChar() {
      return 0;
   }

   public void updateSemanticValue(IdentityHashMap<Object, Object> oldNewMap) {
      value.updateSemanticValue(oldNewMap);
   }

   public void computeLineNumberForNode(LineFormatContext ctx, IParseNode toFindPN) {
      super.computeLineNumberForNode(ctx, toFindPN);
      value.computeLineNumberForNode(ctx, toFindPN);
   }

   @Override
   public void findStartDiff(DiffContext ctx, boolean atEnd, Object parSemVal, ParentParseNode parSemNode, int childIx) {
      super.findStartDiff(ctx, atEnd, parSemVal, parSemNode, childIx);
      if (ctx.firstDiffNode != null)
         return;
      value.findStartDiff(ctx, atEnd, parSemVal, parSemNode, childIx);
   }

   @Override
   public void findEndDiff(DiffContext ctx, Object parSemVal, ParentParseNode parParseNode, int childIx) {
      super.findEndDiff(ctx, parSemVal, parParseNode, childIx);
      if (ctx.lastDiffNode != null)
         return;
      value.findEndDiff(ctx, parSemVal, parParseNode, childIx);
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(errorText);
      sb.append(value.toString());
      return sb.toString();
   }
}
