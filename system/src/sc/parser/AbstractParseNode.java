/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.ISemanticNode;

import java.util.List;

public abstract class AbstractParseNode implements IParseNode, Cloneable {
   int startIndex = -1;
   /** Set during the reparse process as a node is moved from the old to the new tree.  TODO: we could eliminate this perhaps by copying the parse-node tree? */
   int newStartIndex = -1;
   /** Set to true for any parse-nodes which are generated as part of an error state */
   boolean errorNode = false;

   public void setParselet(Parselet p) {}

   public Parselet getParselet() {
      return null;
   }

   public CharSequence toSemanticString() {
      return this;
   }

   public int getStartIndex() {
      if (newStartIndex != -1)
         return newStartIndex;
      return startIndex;
   }

   public int getOrigStartIndex() {
      return startIndex;
   }

   public int getNewStartIndex() {
      return newStartIndex;
   }

   public void setStartIndex(int ix) {
      startIndex = ix;
   }

   public void advanceStartIndex(int ix) {
      setStartIndex(ix);
   }

   public CharSequence subSequence(int start, int end) {
      return toString().subSequence(start, end);
   }

   public int length() {
      return toString().length();
   }

   public boolean isEmpty() {
      return toString().length() == 0;
   }

   public char charAt(int ix) {
      return toString().charAt(ix);
   }

   // TODO: this could be made a lot faster
   public int indexOf(String substr) {
      return toString().indexOf(substr);
   }

   // TODO: this too
   public int indexOf(String substr, int fromIndex) {
      return toString().indexOf(substr, fromIndex);
   }

   // TODO: and this!
   public int lastIndexOf(String substr) {
      return toString().lastIndexOf(substr);
   }

   public void styleNode(IStyleAdapter adapter, Object parSemValue, ParentParseNode parParseNode, int childIx) {
      ParseUtil.styleString(adapter, null, this, toString(), true);
   }

   public int toSemanticStringLength() {
      return toSemanticString().length();
   }

   /**
    * Called to format a string using the styled adapter.  For basic parse nodes, we just need to pass the string to
    * the adapter so that the current document offset is updated properly
    */
   public void formatStyled(FormatContext ctx, IStyleAdapter adapter) {
      IStyleAdapter oldStyleAdapter = ctx.styleAdapter;
      // All of the appendWithStyle calls will funnel through the adapter so they are recorded in style adapter offsets before going through ctx.append()
      ctx.styleAdapter = adapter;
      format(ctx);
      ctx.styleAdapter = oldStyleAdapter;
   }

   /**
    * When we are formatting a node incrementally, we need to supply a way to find the next semantic value
    * following this one in the stream.  We use that to determine indentation and spacing.
    */
   Object getNextSemanticValue(Object parent) {
      Object ourVal = getSemanticValue();
      ISemanticNode parVal = null;
      if (ourVal instanceof ISemanticNode)
         parVal = ((ISemanticNode) ourVal).getParentNode();
      else if (parent instanceof ISemanticNode)
         parVal = (ISemanticNode) parent;

      if (parVal instanceof List) {
         List l = (List) parVal;
         int ix = l.indexOf(ourVal);
         if (ix == -1)
            return null;
         ix++;
         if (ix == l.size())
            return parVal.getParentNode();
         else
            return l.get(ix);
      }
      return parVal;
   }

   public void changeLanguage(Language newLanguage) {
      Parselet old = getParselet();
      Parselet newParselet = newLanguage.findMatchingParselet(old);

      if (newParselet == null) {
         System.err.println("*** Unable to find parselet named: " + old.getName() + " in language: " + newLanguage);
         newParselet = newLanguage.findMatchingParselet(old);
      }
      else {
         setParselet(newParselet);
      }
   }

   public IParseNode reformat() {
      Parselet p;
      if ((p = getParselet()) != null && p.generateParseNode != null)
         return p.generateParseNode;
      return this;
   }

   public AbstractParseNode clone() {
      try {
         return (AbstractParseNode) super.clone();
      }
      catch (CloneNotSupportedException exc) {}
      return null;
   }

   public AbstractParseNode deepCopy() {
      AbstractParseNode clone = this.clone();
      return clone;
   }

   public boolean isCompressedNode() {
      return false;
   }

   public String formatString(Object parentSemVal, ParentParseNode parParseNode, int curChildIndex, boolean removeFormattingNodes) {
      return toString();
   }

   public ISemanticNode getNodeAtLine(NodeAtLineCtx ctx, int lineNum) {
      // Keep track of the last semantic node we past in the hierarchy
      Object val = getSemanticValue();
      if (val instanceof ISemanticNode) {
         ISemanticNode valNode = (ISemanticNode) val;
         if (valNode.getParentNode() != null && !valNode.isTrailingSrcStatement())
            ctx.lastVal = valNode;
      }
      return null;
   }

   public IParseNode findParseNode(int startIndex, Parselet matchParselet) {
      if (this.startIndex == startIndex && (matchParselet == null || matchParselet == getParselet()))
         return this;
      return null;
   }

   public int getChildStartOffset(Parselet matchParselet) {
      return -1;
   }

   public abstract Object getSkippedValue();

   public boolean canSkip() {
      return true;
   }

   public int resetStartIndex(int ix, boolean validate, boolean newIndex) {
      if (validate && ix != getStartIndex())
         System.err.println("Invalid start index found");
      if (!newIndex) {
         startIndex = ix;
         newStartIndex = -1;
      }
      else
         newStartIndex = ix;
      return ix + length();
   }

   public int getSemanticLength() {
      return length();
   }

   public boolean isErrorNode() {
      return errorNode;
   }

   public void setErrorNode(boolean val) {
      errorNode = val;
   }

   public int getNumSemanticValues() {
      return 1;
   }

   public void diffParseNode(IParseNode other, StringBuilder diffs) {
      if (other.getClass() != this.getClass()) {
         diffs.append("Difference classes for node: " + this.getClass() + " and " + other.getClass());
      }
   }

   public boolean isIncomplete() {
      return errorNode;
   }
}
