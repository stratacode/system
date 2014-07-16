/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.ISemanticNode;
import sc.lang.SemanticNode;
import sc.util.IntStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles spacing, indentation, and newlines for 'generated' parse nodes.  When you parse a grammar, spacing is turned into
 * literal string nodes.  When you generate language elements, they turn into SpacingParseNode, and NewlineParseNode instances
 * which initially do not have a string value.  It makes sense to do this on a separate pass - after the tree is fully formed.
 * This is the formatting phase and the FormatContext stores the state of the formatting process - where we are in the both the
 * ParseNode and SemanticValue trees.
 */
public class FormatContext {
   public final static String INDENT_STR = "   ";

   List<Entry> pendingParents = new ArrayList<Entry>();

   StringBuffer currentBuffer = new StringBuffer();

   boolean semanticValueOnly = false;
   boolean suppressSpacing = false;
   boolean suppressNewlines = false;
   boolean tagMode = false;

   public IntStack savedIndentLevels = new IntStack(16);
   public int lastIndent;

   private Object nextValue;

   public FormatContext(ParentParseNode curParent, int curChildIndex, int initIndent, Object lastNextValue, Object curSemVal) {
      // If the semantic value is the node above the curParent parse node, we add it first
      // This is a bit of a hack because it only gives us one more level... we could try to find the path to the curParent if there's
      // a case where that's needed and add all of the levels in between.
      if (curSemVal instanceof ISemanticNode) {
         Object parseNode = ((ISemanticNode) curSemVal).getParseNode();
         if (parseNode != curParent && parseNode instanceof ParentParseNode) {
            ParentParseNode pp = (ParentParseNode) parseNode;
            int curValIndex;
            if (pp.children != null && (curValIndex = pp.children.indexOf(curParent)) != -1) {
               Entry ent = new Entry();
               ent.currentIndex = curValIndex;
               ent.parent = pp;
               pendingParents.add(ent);
            }
         }
      }
      // Then add the current parent
      if (curParent != null) {
         Entry ent = new Entry();
         ent.currentIndex = curChildIndex;
         ent.parent = curParent;
         pendingParents.add(ent);
      }
      savedIndentLevels.push(initIndent);
      nextValue = lastNextValue;
   }

   /**
    * During the formatting process, we walk the parse node tree using these Entries as breadcrumbs to point to
    * where we are in the tree at any given moment.  From the format context, we can find the next character, the previous character
    * and next and previous semantic nodes to determine what to do with regards to spacing, indentation and newlines.
    */
   public static class Entry {
      ParentParseNode parent;
      /** The current index of the child we are processing in this current parent. */
      int currentIndex;
   }

   public void append(CharSequence seq) {
      if (seq != null)
         currentBuffer.append(seq);
   }

   public CharSequence getResult() {
      return currentBuffer;
   }

   public int prevChar() {
      if (currentBuffer.length() == 0)
         return -1;
      return currentBuffer.charAt(currentBuffer.length()-1);
   }

   public int nextChar() {
      for (int i = pendingParents.size()-1; i >= 0; i--) {
         Entry ent = pendingParents.get(i);
         ParentParseNode parent = ent.parent;
         for (int j = ent.currentIndex+1; j < parent.children.size(); j++) {
            int c = ParseUtil.firstCharFromValue(parent.children.get(j));
            if (c != -1)
               return c;
         }
      }
      return -1;
   }

   public Object nextSemanticValue() {
      for (int i = pendingParents.size()-1; i >= 0; i--) {
         Entry ent = pendingParents.get(i);
         ParentParseNode parent = ent.parent;
         for (int j = ent.currentIndex+1; j < parent.children.size(); j++) {
            Object child = parent.children.get(j);
            Object val;
            if (child instanceof IParseNode) {
               IParseNode childNode = (IParseNode) child;
               val = childNode.getSemanticValue();
               if (val != null)
                  return val;
            }
         }
      }
      return nextValue;
   }

   public Object nextSemanticNode() {
      for (int i = pendingParents.size()-1; i >= 0; i--) {
         Entry ent = pendingParents.get(i);
         ParentParseNode parent = ent.parent;
         for (int j = ent.currentIndex+1; j < parent.children.size(); j++) {
            Object child = parent.children.get(j);
            Object val;
            if (child instanceof IParseNode) {
               IParseNode childNode = (IParseNode) child;
               val = childNode.getSemanticValue();
               if (val instanceof ISemanticNode)
                  return val;
            }
         }
      }
      return nextValue;
   }

   public Object prevSemanticValue() {
      return prevSemanticValue(0);
   }

   public Object prevSemanticValue(int skip) {
      for (int i = pendingParents.size()-1; i >= 0; i--) {
         Entry ent = pendingParents.get(i);
         ParentParseNode parent = ent.parent;
         for (int j = ent.currentIndex; j >= 0; j--) {
            Object child = parent.children.get(j);
            Object val;
            if (child instanceof IParseNode) {
               IParseNode childNode = (IParseNode) child;
               val = childNode.getSemanticValue();
               if (val != null) {
                  if (skip == 0)
                     return val;
                  skip--;
               }
            }
         }
      }
      return null;
   }

   public void indent(int ct) {
      for (int i = 0; i < ct; i++)
           append(INDENT_STR);
      //lastIndent = ct;
   }

   public void pushIndent() {
      //lastIndent = lastIndent + 1;
      savedIndentLevels.push(getCurrentIndent()+1);
   }

   public int getCurrentIndent() {
      return savedIndentLevels.size() == 0 ? 0 : savedIndentLevels.top();
   }

   public int popIndent() {
      if (savedIndentLevels.size() > 0) {
         int res = savedIndentLevels.pop();
         return getCurrentIndent();
      }
      return 0;
   }

   public void autoPopIndent() {
      Object sv = nextSemanticValue();
      // The popIndent call for this node may have been popped already in NewlineParseNode in a generated node
      // which sits ahead of a non-generated node.  This check avoids the extra call when NewlineParseNode already
      // did it.   We could simplify things by just setting the indent level here to the next semantic value's indent right?
      if (sv == null || ParseUtil.getSemanticValueNestingDepth(sv) < getCurrentIndent())
         popIndent();
   }

   /** Returns the next enclosing type or tag for html */
   public SemanticNode getNextSemanticNode() {
      for (int i = pendingParents.size()-1; i >= 0; i--) {
         Entry ent = pendingParents.get(i);
         ParentParseNode parent = ent.parent;
         for (int j = ent.currentIndex+1; j < parent.children.size(); j++) {
            Object child = parent.children.get(j);
            Object val;
            if (child instanceof IParseNode) {
               IParseNode childNode = (IParseNode) child;
               val = childNode.getSemanticValue();
               if (val != null && val instanceof SemanticNode)
                  return (SemanticNode) val;
            }
         }
      }
      return null;
   }


   public SemanticNode getPrevSemanticNode() {
      for (int i = pendingParents.size()-1; i >= 0; i--) {
         Entry ent = pendingParents.get(i);
         ParentParseNode parent = ent.parent;
         for (int j = ent.currentIndex; j >= 0; j--) {
            Object child = parent.children.get(j);
            Object val;
            if (child instanceof IParseNode) {
               IParseNode childNode = (IParseNode) child;
               val = childNode.getSemanticValue();
               if (val != null && val instanceof SemanticNode) {
                  return (SemanticNode) val;
               }
            }
         }
      }
      return null;
   }
}
