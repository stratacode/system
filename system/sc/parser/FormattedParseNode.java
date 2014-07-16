/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.ISemanticNode;

/** Generated parse nodes used for performance.  The value is a String which matches its input.  We must store any spacing, newline parse nodes as well */
public class FormattedParseNode extends ParseNode {
   IParseNode[] formattingParseNodes;
   boolean generated = false;

   public FormattedParseNode(Parselet p) {
      super(p);
   }

   public void format(FormatContext ctx) {
      super.format(ctx);
      if (formattingParseNodes != null) {
         for (IParseNode pn:formattingParseNodes) {
            pn.format(ctx);
         }
      }
   }

   public String toString() {
      return formatString(null, null, -1);
   }

   public String formatString(Object parSemVal, ParentParseNode parParseNode, int curChildIndex) {
      if (generated) {
         int initIndent;

         if (value instanceof ISemanticNode) {
            ISemanticNode sv = (ISemanticNode) value;
            initIndent = sv.getNestingDepth();
         }
         else
            initIndent = 0;
         FormatContext ctx = new FormatContext(parParseNode, curChildIndex, initIndent, getNextSemanticValue(parSemVal), parSemVal);
         //ctx.append(FormatContext.INDENT_STR);
         format(ctx);
         return ctx.getResult().toString();
      }
      return super.toString();
   }

   public boolean isCompressedNode() {
      return true;
   }
}
