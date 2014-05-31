/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.ISemanticNode;
import sc.lang.SemanticNode;
import sc.util.IntStack;

import java.util.ArrayList;
import java.util.List;

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

   public FormatContext(int initIndent, Object lastNextValue) {
      savedIndentLevels.push(initIndent);
      nextValue = lastNextValue;
   }

   public static class Entry {
      ParentParseNode parent;
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
