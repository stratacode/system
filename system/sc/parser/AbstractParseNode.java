/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.ISemanticNode;

import java.util.List;

public abstract class AbstractParseNode implements IParseNode, Cloneable {
   int startIndex = -1;

   public void setParselet(Parselet p) {}

   public Parselet getParselet() {
      return null;
   }

   public CharSequence toSemanticString() {
      return this;
   }

   public int getStartIndex() {
      return startIndex;
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

   public int indexOf(String substr) {
      return toString().indexOf(substr);
   }

   public int lastIndexOf(String substr) {
      return toString().lastIndexOf(substr);
   }

   public CharSequence toStyledString() {
      return ParseUtil.styleString(null, this, toString(), true);
   }

   public int toSemanticStringLength() {
      return toSemanticString().length();
   }

   public void formatStyled(FormatContext ctx) {
      format(ctx);
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

   public String formatString(Object parentSemVal, ParentParseNode parParseNode, int curChildIndex) {
      return toString();
   }
}
