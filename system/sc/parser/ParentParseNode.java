/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.ISemanticNode;
import sc.util.PerfMon;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

public class ParentParseNode extends AbstractParseNode {
   NestedParselet parselet;
   boolean generated = false;

   public ArrayList<Object> children;

   // The semantic value (if any) 
   public Object value;

   public ParentParseNode() {}

   public ParentParseNode(Parselet p) {
      this.parselet = (NestedParselet) p;
   }

   public Parselet getParselet() {
      return parselet;
   }

   public void setParselet(Parselet p) {
      parselet = (NestedParselet) p;
   }

   public Object getSemanticValue() {
      return value;
   }

   public void setSemanticValue(Object val) {
      // First clear out the old value
      if (value != null) {
         ParseUtil.clearSemanticValue(value, this);

         if (children != null) {
            for (Object p:children) {
               ParseUtil.clearSemanticValue(p, this);
            }
         }
      }
      value = val;
      if (value instanceof ISemanticNode)
         ((ISemanticNode) value).setParseNode(this);
   }

   public void add(Object node, Parselet p, int index, boolean skipSemanticValue, Parser parser) {
      if (children == null)
         children = new ArrayList<Object>();

      boolean addChild = true;

      // Special case for adding a string to an "omitted" node.  In this case,
      // we'll just accumulate one string as our value.  When this guy gets 
      // added to its parentNode, it will get removed and the string goes on to represent
      // all of this element's children.
      if (node instanceof StringToken) {
         if (p.getDiscard() || p.getLookahead())
            return;
         if (p.skip && !(parselet.needsChildren())) {
            if (children.size() == 1) {
               Object child = children.get(0);
               if (child instanceof StringToken) {
                  children.set(0, StringToken.concatTokens((StringToken)child, (StringToken)node));
                  addChild = false;
               }
            }
         }
      }
      else if (node instanceof String) {
         if (p.discard || p.lookahead)
            return;
         if (p.skip && !(parselet.needsChildren())) {
            if (children.size() == 1)  {
               Object child = children.get(0);
               if (child instanceof String) { // TODO: should this be PString.isString?  See issue#24 where we are not collapsing string tokens into a single parse node
                  // this is n*n - the need to avoid the Strings...
                  children.set(0, ((String) child) + ((String) node));
                  addChild = false;
               }
            }
         }
      }
      else if (node instanceof IParseNode) {
         IParseNode pnode = (IParseNode) node;
         Parselet childParselet = pnode.getParselet();

         if (!childParselet.addResultToParent(pnode, this, index, parser))
            return;
      }

      if (addChild)
         children.add(node);

      // Only nested parselets should be using the ParentParseNode
      parselet.setSemanticValue(this, node, index, skipSemanticValue, parser, false);
   }

   public void set(Object node, Parselet p, int index, boolean skipSemanticValue, Parser parser) {
      boolean setChild = true;

      // Special case for adding a string to an "omitted" node.  In this case,
      // we'll just accumulate one string as our value.  When this guy gets
      // added to its parentNode, it will get removed and the string goes on to represent
      // all of this element's children.
      if (node instanceof StringToken) {
         if (p.getDiscard() || p.getLookahead())
            return;
         if (p.skip && !(parselet.needsChildren())) {
            if (children.size() == 1) {
               Object child = children.get(0);
               if (child instanceof StringToken) {
                  children.set(0, StringToken.concatTokens((StringToken)child, (StringToken)node));
                  setChild = false;
               }
            }
         }
      }
      else if (node instanceof String) {
         if (p.discard || p.lookahead)
            return;
         if (p.skip && !(parselet.needsChildren())) {
            if (children.size() == 1)  {
               Object child = children.get(0);
               if (child instanceof String) { // TODO: should this be PString.isString?  See issue#24 where we are not collapsing string tokens into a single parse node
                  // this is n*n - the need to avoid the Strings...
                  children.set(0, ((String) child) + ((String) node));
                  setChild = false;
               }
            }
         }
      }
      else if (node instanceof IParseNode) {
         IParseNode pnode = (IParseNode) node;
         Parselet childParselet = pnode.getParselet();

         if (!childParselet.addResultToParent(pnode, this, index, parser))
            return;
      }

      if (setChild)
         children.set(index, node);

      // Only nested parselets should be using the ParentParseNode
      parselet.setSemanticValue(this, node, index, skipSemanticValue, parser, true);
   }

   public void addGeneratedNode(Object node) {
      if (children == null)
         children = new ArrayList<Object>();

      children.add(node);
   }

   /** Faster version when we are generating strings a char at a time */
   public void addGeneratedNode(Object node, Parselet childParselet) {
      if (children == null)
         children = new ArrayList<Object>();

      if (childParselet.skip && !(parselet.needsChildren())) {
         if (children.size() == 1) {
            Object child = children.get(0);
            if (child instanceof StringToken) {
               children.set(0, StringToken.concatTokens((StringToken)child, (StringToken)node));
               return;
            }
         }
      }
      children.add(node);
   }

   public void addGeneratedNodeAt(int ix, Object node) {
      if (children == null)
         children = new ArrayList<Object>();

      if (ix > children.size())
         System.out.println("*** Adding generated node at invalid location");

      children.add(ix, node);
   }

   public CharSequence toSemanticString() {
      int sz;
      if (children == null || (sz = children.size()) == 0)
         return "";
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < sz; i++) {
         Object p = children.get(i);
         if (p instanceof IParseNode) {
            sb.append(((IParseNode) p).toSemanticString());
         }
         else if (p != null)
            sb.append((CharSequence) p);
      }
      return sb;
   }

   public int toSemanticStringLength() {
      int sz;
      int len = 0;
      if (children == null || (sz = children.size()) == 0)
         return 0;
      for (int i = 0; i < sz; i++) {
         Object p = children.get(i);
         if (p instanceof IParseNode) {
            len += ((IParseNode) p).toSemanticStringLength();
         }
         else if (p != null)
            len += ((CharSequence)p).length();
      }
      return len;
   }
   public String toString() {
      return formatString(null, null, -1);
   }

   /**
    * Formats the parse node - turning it into a String.  Parent should specify the semantic node parent of this parse node.
    * If null is specified, it's no problem as long as this parse-node's semantic value has a parent.  Some primitive parse nodes have a string
    * semantic value with no ref to their parent.  For the spacing to be computed properly we need this context (for FormatContext.getNextSemanticValue())
    */
   public String formatString(Object parSemVal, ParentParseNode curParseNode, int curChildIndex) {
      // If the parse node is generated, we need to use the formatting process to add in
      // the spacing.  If the parse node was parsed, we toString it just as it
      // was parsed so we get back the identical input strings.
      // TODO: REMOVE THE EXTRA UNCOMMENTED CODE HERE.
      // For non-generated parse nodes, the child.toString() should have all of the formatting already.  But we may have invalidated some children.
      // To get the proper spacing for those children, we need the parent parse nodes in the FormatContext - so they can find the prev/next chars to do proper spacing.
      // If there's overhead here, we could still optimize the case where there are no invalidated children nodes
      //if (generated) {
         int initIndent;

         if (value instanceof ISemanticNode) {
            ISemanticNode sv = (ISemanticNode) value;
            initIndent = sv.getNestingDepth();
         }
         else
            initIndent = 0;
         FormatContext ctx = new FormatContext(curParseNode, curChildIndex, initIndent, getNextSemanticValue(parSemVal), parSemVal);
         //ctx.append(FormatContext.INDENT_STR);
         PerfMon.start("format", false);
         format(ctx);
         PerfMon.end("format");
         return ctx.getResult().toString();
      /*
      }
      else {
         if (children == null)
              return "";
         if (children.size() == 1) {
            Object child = children.get(0);
            if (child == null)
               return "";
            return child.toString();
         }
         else {
            StringBuffer sb = new StringBuffer();
            for (Object o:children)
               if (o != null)
                  sb.append(o);
            return sb.toString();
         }
      }
      */
   }

   public CharSequence toStyledString() {
      // If the parse node is generated, we need to use the formatting process to add in
      // the property spacing.  If the parse node was parsed, we toString it just as it
      // was parsed so we get back the identical input strings.
      if (generated) {
         int initIndent;

         if (value instanceof ISemanticNode) {
            ISemanticNode sv = (ISemanticNode) value;
            initIndent = sv.getNestingDepth();
         }
         else
            initIndent = 0;
         FormatContext ctx = new FormatContext(null, -1, initIndent, getNextSemanticValue(null), null);
         //ctx.append(FormatContext.INDENT_STR);
         formatStyled(ctx);
         return ctx.getResult().toString();
      }
      else {
         if (children == null)
            return "";
         StringBuffer sb = new StringBuffer();
         Parselet childParselet;
         int sz = children.size();
         int numParselets = parselet.parselets.size();
         for (int i = 0; i < sz; i++) {
            Object childNode = children.get(i);
            childParselet = parselet.getChildParselet(childNode, i);
            if (childNode != null) {
               Object childSV;
               CharSequence childStr;
               if (childNode instanceof IParseNode) {
                  IParseNode childParseNode = (IParseNode) childNode;
                  childSV = childParseNode.getSemanticValue();
                  if (!(childSV instanceof ISemanticNode))
                      sb.append(childParseNode.toStyledString());
                  else {
                     ISemanticNode childSVNode = (ISemanticNode) childSV;
                     if (childSVNode.getParseNode() == childNode)
                        sb.append(ParseUtil.styleString(childSV, childParselet.styleName, null, false));
                     else
                        sb.append(childParseNode.toStyledString());
                  }
               }
               else {
                  childStr = ParseUtil.styleString(childParselet.styleName, childNode.toString(), true);
                  sb.append(childStr);
               }
            }
         }
         return ParseUtil.styleString(parselet.styleName, sb.toString(), false);
      }
   }

   public void addParent(ParentParseNode par, int currentChildIndex) {

   }

   public void format(FormatContext ctx) {
      if (children == null || children.size() == 0)
         return;

      FormatContext.Entry ent = new FormatContext.Entry();
      List<FormatContext.Entry> pp = ctx.pendingParents;
      ent.parent = this;
      ent.currentIndex = 0;
      pp.add(ent);
      try {
         for (Object p:children) {
            if (p instanceof IParseNode) {
               IParseNode node = (IParseNode) p;
               Parselet parselet = node.getParselet();
               if (parselet == null)
                  node.format(ctx);
               else
                  parselet.format(ctx, node);
            }
            else if (p != null) {
               ctx.append((CharSequence) p);
            }
            ent.currentIndex++;
         }
      }
      finally {
         pp.remove(pp.size()-1);
      }
      return;
   }

   public IParseNode reformat() {
      if (children != null) {
         for (int i = 0; i < children.size(); i++) {
            Object childParseObj = children.get(i);
            if (childParseObj instanceof IParseNode) {
               IParseNode origChildParseNode = (IParseNode) childParseObj;
               IParseNode newChildParseNode = origChildParseNode.reformat();
               if (newChildParseNode != origChildParseNode)
                  children.set(i, newChildParseNode);
            }
         }
      }
      return super.reformat();
   }

   public void formatStyled(FormatContext ctx) {
      if (children == null || children.size() == 0)
         return;

      FormatContext.Entry ent = new FormatContext.Entry();
      ent.parent = this;
      ent.currentIndex = 0;
      ctx.pendingParents.add(ent);
      try {
         Parselet childParselet;
         int numChildren = parselet.parselets.size();
         int sz = children.size();
         for (int i = 0; i < sz; i++) {
            Object p = children.get(i);
            if (p instanceof IParseNode) {
               IParseNode node = (IParseNode) p;
               Parselet parselet = node.getParselet();
               if (parselet == null)
                  node.formatStyled(ctx);
               else {
                  String styleName = parselet.styleName;
                  if (styleName != null)
                     ctx.append(ParseUtil.getStyleStart(styleName));
                  parselet.formatStyled(ctx, node);
                  if (parselet.styleName != null)
                     ctx.append(ParseUtil.getStyleEnd());
               }
            }
            else if (p != null) {
               childParselet = parselet.parselets.get(i % numChildren);
               ctx.append(ParseUtil.styleString(null, childParselet.styleName, (CharSequence) p, true));
            }
            ent.currentIndex++;
         }
      }
      finally {
         ctx.pendingParents.remove(ctx.pendingParents.size()-1);
      }
   }

   public String toDebugString() {
      StringBuffer sb = new StringBuffer();
      if (parselet != null && parselet.getName() != null)
         sb.append(parselet.getName());
      if (children == null)
         sb.append("<empty>");
      else {
         for (Object o:children) {
            sb.append("[");
            if (o instanceof IParseNode)
               sb.append(((IParseNode)o).toDebugString());
            else
               sb.append(o);
            sb.append("]");
         }
      }
      return sb.toString();
   }

   public boolean refersToSemanticValue(Object sv) {
      if (value == sv)
         return true;

      if (children == null)
         return false;

      for (Object o:children) {
         if (o instanceof IParseNode) {
            boolean ref = ((IParseNode) o).refersToSemanticValue(sv);
            if (ref)
               return true;
         }
      }
      return false;
   }

   void copyGeneratedFrom(ParentParseNode from) {
      List fromChildren = from.children;
      if (fromChildren != null) {
         int sz = fromChildren.size();
         for (int i = 0; i < sz; i++) {
            Object o = fromChildren.get(i);
            addGeneratedNode(o);
         }
      }
   }

   void copyGeneratedAt(int ix, ParentParseNode from) {
      if (from.children != null)
         for (Object o:from.children)
            addGeneratedNodeAt(ix++, o);
   }

   public int firstChar() {
      if (children != null) {
         for (Object p:children) {
            int c =  ParseUtil.firstCharFromValue(p);
            if (c != -1)
               return c;
         }
      }
      return -1;
   }

   public boolean isGenerated() {
      return generated;
   }

   public int length() {
      if (children == null) return 0;
      int len = 0;
      for (int i = 0; i < children.size(); i++) {
         Object child = children.get(i);
         if (child instanceof CharSequence) {
            len += ((CharSequence) child).length();
         }
         else if (child != null) {
            len += child.toString().length();
         }
      }
      return len;
   }

   public boolean isEmpty() {
      if (children == null) return true;
      for (int i = 0; i < children.size(); i++) {
         Object child = children.get(i);
         if (child instanceof CharSequence) {
            if (((CharSequence) child).length() > 0)
               return false;
         }
         else if (child != null) {
            if (child.toString().length() > 0)
               return false;
         }
      }
      return true;
   }

   public char charAt(int ix) {
      if (children == null || ix < 0)
         throw new IndexOutOfBoundsException();
      int len = 0;
      for (int i = 0; i < children.size(); i++) {
         int nextLen;
         Object child = children.get(i);
         CharSequence childSeq;
         if (child instanceof CharSequence) {
            childSeq = (CharSequence) child;
            nextLen = childSeq.length();
         }
         else if (child != null) {
            nextLen = (childSeq = child.toString()).length();
         }
         else {
            nextLen = 0;
            childSeq = null;
         }

         int offset;
         if ((offset = ix - len) < nextLen) {
            return childSeq.charAt(offset);
         }
         len += nextLen;
      }
      throw new IndexOutOfBoundsException();
   }

   // TODO: not efficient for large sequences
   public CharSequence subSequence(int start, int end) {
      StringBuilder sb = new StringBuilder();
      for (int i = start; i < end; i++)
         sb.append(charAt(i));
      return sb.toString();
   }

   public void changeLanguage(Language newLanguage) {
      super.changeLanguage(newLanguage);
      if (children == null)
         return;
      for (Object child:children) {
         if (child instanceof IParseNode) {
            ((IParseNode) child).changeLanguage(newLanguage);
         }
      }
   }

   public void advanceStartIndex(int ix) {
      int myIx = getStartIndex();
      setStartIndex(myIx == -1 ? ix : myIx + ix);
      if (children != null) {
         for (Object child:children) {
            if (child instanceof IParseNode) {
               ((IParseNode) child).advanceStartIndex(ix);
            }
         }
      }
   }

   public ParentParseNode deepCopy() {
      ParentParseNode res = (ParentParseNode) super.deepCopy();
      ArrayList<Object> newChildren;
      if (children != null) {
         res.children = newChildren = new ArrayList<Object>();
         for (Object child:children) {
            if (child instanceof IParseNode)
               newChildren.add(((IParseNode) child).deepCopy());
            else
               newChildren.add(child);
         }
      }
      return res;
   }

   public void updateSemanticValue(IdentityHashMap<Object, Object> oldNewMap) {
      if (value != null && value instanceof ISemanticNode) {
         ISemanticNode oldVal = (ISemanticNode) value;
         ISemanticNode newVal = (ISemanticNode) oldNewMap.get(oldVal);
         if (newVal != null) {
            if (oldVal.getParseNode().getParselet() == getParselet())
               newVal.setParseNode(this);
            value = newVal;
         }
         else { // This is a semantic value - like an extra list or something which was not included in the model itself.  So this parse node refers to something in the old parse tree which is probably not great so make a clone here.
            newVal = oldVal.deepCopy(ISemanticNode.SkipParseNode, null);
            newVal.setParseNode(this);
            value = newVal;
         }
      }
      if (children != null) {
         for (Object child:children) {
            if (child instanceof IParseNode)
               ((IParseNode) child).updateSemanticValue(oldNewMap);
         }
      }
   }

   /** Some semantic nodes compress the parse-tree and so cannot listen for changes on their children.  */
   public boolean isCompressedNode() {
      return children != null && children.size() == 1 && children.get(0) instanceof FormattedParseNode;
   }
}



