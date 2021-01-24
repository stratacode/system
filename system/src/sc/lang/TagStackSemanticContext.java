/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.parser.SemanticContext;

import java.util.ArrayList;

/**
 * The parser lets you maintain additional parse-state so that you can match or reject any given element in the input stream.
 * This extension point is the SemanticContext.  For HTML, we need to maintain the tagStack so that we can match open and closed
 * tags and allow open tags with no close tag.
 */
public class TagStackSemanticContext extends SemanticContext {
   public boolean ignoreCase = true;

   public TagStackSemanticContext(boolean ignoreCase) {
      this.ignoreCase = ignoreCase;
   }

   public class TSContextEntry {
      int startIx;
      int endIx;
      int endTagIx;
      String tagName;

      public String toString() {
         return tagName + "[" + startIx + ":" + endTagIx + "]";
      }
   }
   ArrayList<TSContextEntry> tagStack = new ArrayList<TSContextEntry>();

   ArrayList<TSContextEntry> removedStack = new ArrayList<TSContextEntry>();

   // Flag we can set to parse closeTag's without matching to a start tag for diagnostics
   boolean allowAnyCloseTag = false;

   void addEntry(Object semanticValue, int startIx, int endIx) {
      TSContextEntry ent = new TSContextEntry();
      ent.startIx = startIx;
      ent.endIx = endIx;
      ent.endTagIx = -1;
      String tagNameStr = semanticValue.toString();
      ent.tagName = ignoreCase ? tagNameStr.toLowerCase() : tagNameStr;
      tagStack.add(ent);
   }

   public String getCurrentTagName() {
      int sz = tagStack.size();
      if (sz == 0)
         return null;
      return tagStack.get(sz-1).tagName;
   }

   public Object resetToIndex(int ix) {
      ArrayList<TSContextEntry> removedList = null;
      // When we are resetting the index back - behind the current pointer, we might need to remove tag stack entries.
      // Keep track of those we remove so we can restore them again if we set the index ahead again.
      for (int i = tagStack.size() - 1; i >= 0; i--) {
         if (tagStack.get(i).startIx >= ix) {
            if (removedList == null)
               removedList = new ArrayList<TSContextEntry>();
            TSContextEntry removedEntry = tagStack.remove(i);
            removedList.add(removedEntry);

            addRemovedStackEntry(removedEntry);
         }
         else {
            break;
         }
      }
      return removedList;
   }

   public void restoreToIndex(int ix, Object retVal) {
      if (retVal != null) {
         ArrayList<TSContextEntry> toRestore = (ArrayList<TSContextEntry>) retVal;
         for (int i = 0; i < toRestore.size(); i++)
            tagStack.add(toRestore.get(i));
      }
      else {
         for (int i = removedStack.size() - 1; i >= 0 && i < removedStack.size(); i--) {
            TSContextEntry removedEnt = removedStack.get(i);
            if (removedEnt.startIx <= ix) {
               // Does this tag overlap the current position?  If so, we add it back in to the current tag stack.
               if (removedEnt.endTagIx != -1 && removedEnt.endTagIx >= ix) {
                  addTagStackEntry(removedEnt);
               }
               //removedStack.remove(i);
               //i++;
            }
         }
      }
   }

   public void popTagName(int endTagIx) {
      int sz = tagStack.size();
      if (sz == 0)
         System.err.println("*** invalid pop tag!");
      else {
         TSContextEntry removedEnt = tagStack.remove(sz - 1);
         removedEnt.endTagIx = endTagIx;
         addRemovedStackEntry(removedEnt);
      }
   }

   private void addTagStackEntry(TSContextEntry toAdd) {
      int i;
      for (i = tagStack.size() - 1; i >= 0; i--) {
         TSContextEntry curEnt = tagStack.get(i);
         if (curEnt.startIx < toAdd.startIx) {
            break;
         }
         else if (curEnt.startIx == toAdd.startIx) {
            assert(curEnt.tagName.equals(toAdd.tagName));
            if (curEnt.endTagIx == -1)
               curEnt.endTagIx = toAdd.endTagIx;
            return;
         }
      }
      if (i == tagStack.size() - 1)
         tagStack.add(toAdd);
      else
         tagStack.add(i + 1, toAdd);
   }

   private void addRemovedStackEntry(TSContextEntry removedEnt) {
      int i;
      for (i = removedStack.size() - 1; i >= 0; i--) {
         TSContextEntry curEnt = removedStack.get(i);
         if (curEnt.startIx < removedEnt.startIx) {
            break;
         }
         else if (curEnt.startIx == removedEnt.startIx) {
            assert(curEnt.tagName.equals(removedEnt.tagName));
            if (curEnt.endTagIx == -1)
               curEnt.endTagIx = removedEnt.endTagIx;
            return;
         }
      }
      if (i == removedStack.size() - 1)
         removedStack.add(removedEnt);
      else
         removedStack.add(i + 1, removedEnt);
   }
}
