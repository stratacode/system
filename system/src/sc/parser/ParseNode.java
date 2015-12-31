/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.ISemanticNode;

import java.util.IdentityHashMap;

public class ParseNode extends AbstractParseNode {
   public Object value;
   public Object semanticValue;

   Parselet parselet;

   public ParseNode() {
   }

   public ParseNode(Parselet p) {
      parselet = p;
   }

   public Parselet getParselet() {
      return parselet;
   }

   public void setParselet(Parselet p) {
      parselet = p;
   }

   public Object getSemanticValue() {
      return semanticValue;
   }

   public void setSemanticValue(Object val) {
      if (semanticValue != null)
      {
         ParseUtil.clearSemanticValue(semanticValue, this);
         ParseUtil.clearSemanticValue(value, this);
      }
      semanticValue = val;
   }

   public String toString() {
      return value.toString();
   }

   public String toDebugString() {
      if (parselet.getName() != null)
         return "[" + parselet.getName() + ":" + valueDebugString() + "]";
      else {
         return valueDebugString();
      }
   }

   private String valueDebugString() {
      if (value instanceof IParseNode)
         return (((IParseNode) value).toDebugString());
      return value.toString();
   }

   public boolean refersToSemanticValue(Object v) {
      if (v == semanticValue)
         return true;
      if (value instanceof IParseNode)
         return ((IParseNode)value).refersToSemanticValue(v);
      return false;
   }

   public void format(FormatContext ctx) {
      if (value instanceof IParseNode)
         ((IParseNode)value).format(ctx);
      else
         ctx.append(value.toString());
   }

   public IParseNode reformat() {
      if (value instanceof IParseNode)
         ((IParseNode) value).reformat();

      return super.reformat();
   }

   public int firstChar() {
      return ParseUtil.firstCharFromValue(value);
   }

   public int length() {
      if (value == null)
         return 0;
      if (value instanceof IParseNode)
         return ((IParseNode) value).length();
      else if (value instanceof String)
         return ((String) value).length();
      else
         return value.toString().length();
   }

   public boolean isEmpty() {
      if (value == null)
         return true;
      if (value instanceof IParseNode)
         return ((IParseNode) value).isEmpty();
      else if (value instanceof String)
         return ((String) value).length() == 0;
      else
         return value.toString().length() == 0;
   }

   public CharSequence subSequence(int start, int end) {
      if (value == null) {
         if (start == 0 && end == 0)
            return "";
         else
            throw new IndexOutOfBoundsException();
      }
      else if (value instanceof IParseNode) {
         return ((IParseNode) value).subSequence(start, end);
      }
      else if (value instanceof String) {
         return ((String) value).subSequence(start, end);
      }
      else if (value instanceof IString) {
         return ((IString) value).subSequence(start, end);
      }
      else
         return ((value).toString()).subSequence(start, end);
   }

   public char charAt(int ix) {
      if (value == null) {
         throw new IndexOutOfBoundsException();
      }
      else if (value instanceof IParseNode) {
         return ((IParseNode) value).charAt(ix);
      }
      else if (value instanceof String) {
         return ((String) value).charAt(ix);
      }
      else if (value instanceof IString) {
         return ((IString) value).charAt(ix);
      }
      else
         return ((value).toString()).charAt(ix);
   }

   public void styleNode(IStyleAdapter adapter, Object parSemVal, ParentParseNode parentParseNode, int chidlIx) {
      CharSequence res;
      if (value instanceof IParseNode) {
         IParseNode childParseNode = (IParseNode) value;
         Object childSV = childParseNode.getSemanticValue();
         if ((childSV instanceof ISemanticNode)) {
            ISemanticNode childSVNode = (ISemanticNode) childSV;
            if (childSVNode.getParseNode() == childParseNode) {
               ParseUtil.styleString(adapter, childSV, childParseNode.getParselet().styleName, null, false);
               return;
            }
         }
         res = childParseNode.toString();
      }
      else
         res = value.toString();
      ParseUtil.styleString(adapter, null, parselet.styleName, res, true);
   }

   public void formatStyled(FormatContext ctx, IStyleAdapter adapter) {
      if (value instanceof IParseNode)
         ((IParseNode)value).formatStyled(ctx, adapter);
      else {
         adapter.styleString(toString(), false, null, null);
      }
   }

   public void changeLanguage(Language lang) {
      super.changeLanguage(lang);
      if (value instanceof IParseNode)
         ((IParseNode) value).changeLanguage(lang);
   }

   public void updateSemanticValue(IdentityHashMap<Object, Object> oldNewMap) {
      if (semanticValue instanceof ISemanticNode) {
         ISemanticNode oldVal = (ISemanticNode) semanticValue;
         ISemanticNode newVal = (ISemanticNode) oldNewMap.get(oldVal);
         if (newVal != null) {
            newVal.setParseNode(this);
            semanticValue = newVal;
         }
         else
            System.err.println("*** Unable to find semantic value to update");
      }
      if (value instanceof IParseNode)
         ((IParseNode) value).updateSemanticValue(oldNewMap);
   }

   public ParseNode deepCopy() {
      ParseNode res = (ParseNode) super.deepCopy();
      Object val = res.value;
      if (val instanceof IParseNode)
         res.value = ((IParseNode) val).deepCopy();
      return res;
   }

   public void computeLineNumberForNode(LineFormatContext ctx, IParseNode toFindPN) {
      Object pnVal = value;
      if (pnVal == toFindPN) {
         if (pnVal instanceof IParseNode) {
            IParseNode pn = (IParseNode) pnVal;
            Object semVal = pn.getSemanticValue();
            if (semVal instanceof ISemanticNode && ((ISemanticNode) semVal).isTrailingSrcStatement()) {
               // Add in the contribution for this parse node if we treat it's src line as the last line of the statement.
               toFindPN.computeLineNumberForNode(ctx, toFindPN);
               ctx.curLines--;
            }
         }
         ctx.found = true;
      }
      else if (pnVal instanceof IParseNode) {
         IParseNode pn = (IParseNode) pnVal;
         pn.computeLineNumberForNode(ctx, toFindPN);
      }
      else if (pnVal instanceof CharSequence) {
         CharSequence pnSeq = (CharSequence) pnVal;
         ctx.append(pnSeq);
         ctx.curLines += ParseUtil.countLinesInNode(pnSeq);
      }
   }

   public ISemanticNode getNodeAtLine(NodeAtLineCtx ctx, int lineNum) {
      ISemanticNode res = super.getNodeAtLine(ctx, lineNum);
      if (res != null)
         return res;

      Object childVal = value;
      if (childVal == null)
         return null;
      if (PString.isString(childVal)) {
         CharSequence nodeStr = (CharSequence) childVal;
         ctx.curLines += ParseUtil.countLinesInNode(nodeStr);
         if (ctx.curLines >= lineNum) {
            return ctx.lastVal;
         }
      }
      else {
         res = ((IParseNode) childVal).getNodeAtLine(ctx, lineNum);
         if (res != null)
            return res;
         // Returning the lowest level match that has a parent.  That seems more reliable than this approach of trying to hit it on the way up
         // If you think about it we need to include the node which contains the newline and use that as the context node to find the 'real statement'
         // to use.
         //ctx.lastVal = parentVal;
      }
      return null;
   }

   public IParseNode findParseNode(int startIndex, Parselet matchParselet) {
      IParseNode res = super.findParseNode(startIndex, matchParselet);
      if (res != null)
         return res;

      if (value instanceof IParseNode)
         return ((IParseNode) value).findParseNode(startIndex, matchParselet);

      return null;
   }

   public Object getSkippedValue() {
      return value;
   }

   public int resetStartIndex(int ix) {
      startIndex = ix;
      if (value != null) {
         if (value instanceof IParseNode) {
            return ((IParseNode) value).resetStartIndex(ix);
         }
         else if (value instanceof CharSequence) {
            return startIndex + ((CharSequence) value).length();
         }
      }
      return startIndex;
   }

   public int getSemanticLength() {
      if (value instanceof IParseNode) {
         IParseNode pnode = (IParseNode) value;
         if (ParseUtil.isSpacingNode(pnode))
            return 0;
         else
            return pnode.getSemanticLength();
      }
      return length();
   }

   @Override
   public void findStartDiff(DiffContext ctx) {
      if (value instanceof IParseNode) {
         ((IParseNode) value).findStartDiff(ctx);
      }
      else if (value instanceof CharSequence) {
         CharSequence parsedText = (CharSequence) value;
         int plen = parsedText.length();
         String text = ctx.text;
         for (int i = 0; i < plen; i++) {
            if (parsedText.charAt(i) != text.charAt(ctx.curOffset)) {
               ctx.diffNode = this;
               return;
            }
            else
               ctx.curOffset++;
         }
      }
   }

   @Override
   public void findEndDiff(DiffContext ctx) {
      if (value instanceof IParseNode) {
         ((IParseNode) value).findEndDiff(ctx);
      }
      else if (value instanceof CharSequence) {
         CharSequence parsedText = (CharSequence) value;
         int plen = parsedText.length();
         String text = ctx.text;
         for (int i = plen - 1; i >= 0; i--) {
            if (parsedText.charAt(i) != text.charAt(ctx.curOffset)) {
               ctx.diffNode = this;
               return;
            }
            else
               ctx.curOffset--;
         }
      }
   }
}

