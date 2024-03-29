/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.ISemanticNode;

/**
 * Generated parse nodes used for performance that collapse a tree of parse-nodes into a String.
 * This String will match its input.  It also collects spacing and newline parse nodes that follow the matched string
 * adopting the convention used by parselets to store whitespace after the matched node.
 */
public class FormattedParseNode extends ParseNode {
   IParseNode[] formattingParseNodes;
   boolean generated = false;

   public FormattedParseNode(Parselet p) {
      super(p);
   }

   public void format(FormatContext ctx) {
      super.format(ctx);
      if (formattingParseNodes != null) {
         int len = formattingParseNodes.length;
         for (int i = 0; i < len; i++) {
            IParseNode pn = formattingParseNodes[i];
            if (pn != null) {
               pn.format(ctx);
               if (ctx.replaceNode == pn) {
                  formattingParseNodes[i] = ctx.createReplaceNode();
               }
            }
         }
      }
   }

   public void computeLineNumberForNode(LineFormatContext ctx, IParseNode toFindPN) {
      super.computeLineNumberForNode(ctx, toFindPN);
      if (formattingParseNodes != null) {
         for (IParseNode pn:formattingParseNodes) {
            if (pn != null)
               pn.computeLineNumberForNode(ctx, toFindPN);
         }
      }
   }

   public ISemanticNode getNodeAtLine(NodeAtLineCtx ctx, int lineNum) {
      ISemanticNode res = super.getNodeAtLine(ctx, lineNum);
      if (res != null)
         return res;

      if (formattingParseNodes != null) {
         for (IParseNode pn:formattingParseNodes) {
            if (pn != null) {
               res = pn.getNodeAtLine(ctx, lineNum);
               if (res != null)
                  return res;
            }
         }
      }
      return null;
   }

   public String toString() {
      return formatString(null, null, -1, false);
   }

   public String formatString(Object parSemVal, ParentParseNode parParseNode, int curChildIndex, boolean removeFormatting) {
      if (generated) {
         int initIndent;

         if (value instanceof ISemanticNode) {
            ISemanticNode sv = (ISemanticNode) value;
            initIndent = sv.getNestingDepth();
         }
         else
            initIndent = 0;
         FormatContext ctx = new FormatContext(parParseNode, curChildIndex, initIndent, getNextSemanticValue(parSemVal), parSemVal);
         ctx.replaceFormatting = true;
         //ctx.append(FormatContext.INDENT_STR);
         format(ctx);
         return ctx.getResult().toString();
      }
      return super.toString();
   }

   public boolean isCompressedNode() {
      return true;
   }

   public int resetStartIndex(int ix, boolean validate, boolean updateNewIndex) {
      int res = super.resetStartIndex(ix, validate, updateNewIndex);
      if (formattingParseNodes != null) {
         for (IParseNode fpn:formattingParseNodes) {
            if (fpn != null) {
               int sz = fpn.length();
               if (sz > 0)
                  res += sz;
            }
         }
      }
      return res;
   }
}
