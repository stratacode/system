package sc.parser;

import java.util.HashSet;

/**
 * In order to perform an efficient reparse, we first make a pass over the old parse-node and the new text string to
 * build up some context including: the first different parse node, the last different parse-node.  These are the
 * parse-nodes in the old parse-node tree which bracket the changed region.
 * To help identify the border between the original unchanged text and the changed region we also store the set of
 * changed-parents.  We use the changed-parents to determine whether or not to traverse a parent node in the unchanged
 * region.
 * To detect the region of the file when the text is the "same again" we also store the last diff node.  We start from
 * the end of the file and walk the parse-node-tree in reverse looking for the first node hit from the end of the tree
 * which is not the same.  To help identify the boundary between the parse-nodes we can reuse at the end of the parse-node
 * tree and the changed region in the text, we also store the last-node we fully visited before we encountered the changed
 * region.  As soon as we encounter this node, we can start reusing parse-nodes from the original parse node tree.  We flag
 * this by setting sameAgain = true.   In order to start the same-again process as quickly as possible, all children of
 * the last-visited node which have the same start index are also considered "last visited nodes".  Any one of them can
 * trip the sameAgain flag to true.
 * We finally have two markers to determine the end of the changed region in the new text - endChangeNewOffset and
 * endParseChangeNewOffset.  They both start out the same - pointing to the index of the text which is the same again -
 * i.e. newText[endChangeNewOffset] = oldText[endChangeOldOffset] and every subsequent char in the text is the same.
 * When we encounter a node which parses-differently in the changed region which extends into the unchanged text, we push
 * back the endParseChangeNewOffset to include this region which cannot be parsed the same - because we already parsed it
 * differently.
 */
public class DiffContext {
   public static boolean debugDiffContext = false;

   // The new text string involved in the reparse
   String text;
   int newLen;

   // Text offset into the beginning text of the first unmatching character in both streams
   int startChangeOffset;
   // The child right before the change - in case the change happens at a boundary between two parse-nodes
   IParseNode beforeFirstNode;
   // The first parse-node in the old parse-tree which does not match it's new text
   IParseNode firstDiffNode;
   // The offset into the old text of the first mismatching character when starting from the end of each text string
   int endChangeOldOffset;
   // The offset into the new text of the first mismatching character (also from the back)
   int endChangeNewOffset;
   // The offset into the new text which we must consider as changed during reparse.  This starts out the
   // same as endChangeNewOffset but can be adjusted to force us to reparse a result and delay the "sameAgain" setting.
   int endParseChangeNewOffset;
   // The last changed parse-node
   IParseNode lastDiffNode;

   // Difference in the length of the new file compared to the old
   int diffLen;

   // The number of characters in the original document which were not parsed in the document - any unparseable stuff at the end
   int unparsedLen;

   // During the reparse operation, set to true when we have hit the 'afterLastNode'
   boolean parsedAfterLast = false;

   // We always set changeStartOffset to be the first character we need to treat as changed and changeEndOffset to be
   // where we set changedRegion = false, sameAgainOffset to where we set "sameAgain" to true.  Since we may adjust the pointer
   // back and forth after we've set them, we may need to reset those flags.
   int changeStartOffset = -1;
   int changeEndOffset = -1;
   int sameAgainOffset = -1;

   // The parse node riht after the lastDiffNode - in case the change spans the boundary between nodes
   IParseNode afterLastNode;

   // Keeps track of the last node in the traversal
   IParseNode lastVisitedNode;

   /** Are we currently parsing a changed region?  In between the firstDiffNode and the lastDiffNode? */
   boolean changedRegion = false;
   boolean sameAgain = false;

   /** Keep track of all of the parent nodes which include a change.  This makes for a faster reparse */
   HashSet<IParseNode> changedParents = new HashSet<IParseNode>();

   HashSet<IParseNode> sameAgainChildren = new HashSet<IParseNode>();

   public void addChangedParent(IParseNode parentParseNode) {
      changedParents.add(parentParseNode);
   }

   public int getNewOffset() {
      return !sameAgain ? 0 : getDiffOffset();
   }

   public int getNewOffsetForOldPos(int pos) {
      // If we have a repeating character in the difference (e.g. a change from <!-- -> to <!-- -->), the endChangeOldOffset might be less than the startChangeOffset.  In this case,
      // we are still not in the changed region if before the startChangeOffset
      return pos >= endChangeOldOffset && pos >= startChangeOffset ? getDiffOffset() : 0;
   }

   public int getNewOffsetForNewPos(int pos) {
      return pos >= endChangeNewOffset ? getDiffOffset() : 0;
   }

   public int getDiffOffset() {
      return diffLen;
   }

   public void setChangedRegion(Parser parser, boolean newVal) {
      if (newVal) {
         if (changeStartOffset == -1 || changeStartOffset > parser.currentIndex) {
            changeStartOffset = parser.currentIndex;
            // The changeStartOffset gets set when the beforeFirstNode is encountered in parsing.  If for some reason
            // this happens after the changes start, we should still mark the changes as starting where they do.
            if (changeStartOffset > startChangeOffset) {
               changeStartOffset = startChangeOffset;
            }
         }
      }
      else {
         if (changeEndOffset == -1 || changeEndOffset < parser.currentIndex) {
            if (parser.currentIndex < endParseChangeNewOffset)
               System.out.println("*** Warning - parseChangeNewOffset end ahead of changeEndOffset");
            changeEndOffset = parser.currentIndex;
         }
      }
      changedRegion = newVal;
   }

   public void setSameAgain(Parser parser, boolean newVal) {
      if (newVal) {
         if (sameAgainOffset == -1 || sameAgainOffset < parser.currentIndex) {
            sameAgainOffset = parser.currentIndex;
            // the changed end offset might get set till after sameAgain but should never be unset when sameAgain is true
            if (changeEndOffset == -1) {
               changeEndOffset = sameAgainOffset;
               if (changeEndOffset < endParseChangeNewOffset) {
                  changeEndOffset = endParseChangeNewOffset;
               }
            }
         }
      }
      sameAgain = newVal;
   }

   public Object resetCurrentIndex(Parser parser, int newIndex) {
      Object res = parser.resetCurrentIndex(newIndex);
      updateForNewIndex(newIndex);
      return res;
   }

   public void restoreCurrentIndex(Parser parser, int index, Object semanticContext) {
      parser.restoreCurrentIndex(index, semanticContext);
      updateForNewIndex(index);
   }

   public void changeCurrentIndex(Parser parser, int newIndex) {
      parser.changeCurrentIndex(newIndex);
      updateForNewIndex(newIndex);
      // updateStateForPosition(newIndex);
   }

   public void updateForNewIndex(int newIndex) {
      if (changeStartOffset != -1) {
         if (changeEndOffset != -1) {
            if (newIndex >= changeStartOffset && newIndex < changeEndOffset) {
               changedRegion = true;
            }
            else if (changedRegion)
               changedRegion = false;
         }
         // If only the start is set, we still need to change it to false if we move before the start
         else {
            if (newIndex < changeStartOffset && changedRegion)
               changedRegion = false;
         }
      }
      if (sameAgainOffset != -1) {
         if (!sameAgain && newIndex >= sameAgainOffset) {
            sameAgain = true;
         }
         else if (sameAgain && newIndex < sameAgainOffset)
            sameAgain = false;
      }
   }

   /*
    * Currently we don't use this code to toggle the changedRegion/sameAgain state directly when the current index changes.  Instead, it happens
    * when we explicitly enter or exit parse-nodes.  The isAfterLastParseNode does does the index and will accept any parse-node
    * after the afterLastNode as being part of the afterLast region.  That's because we might take a different path and not process
    * the oldParseNode in the midst of the changed region section.   I don't think the same thing happens during the start.  If
    * we set the changedRegion explicitly based on the index, things go bad because it happens at the wrong time of the process.
   void updateStateForPosition(int newIndex) {
      boolean inChangedRegion = inChangedRegion(newIndex);
      if (inChangedRegion != changedRegion) {
         changedRegion = inChangedRegion;
      }

      int endIndex = endParseChangeNewOffset;
      if (afterLastNode != null && afterLastNode.getStartIndex() > endIndex)
         endIndex = afterLastNode.getStartIndex();

      boolean inSameAgainRegion = newIndex >= endIndex;
      if (inSameAgainRegion != sameAgain) {
         sameAgain = inSameAgainRegion;
      }
   }

   boolean inChangedRegion(int index) {
      int startChange = startChangeOffset;
      if (beforeFirstNode != null)
         startChange = beforeFirstNode.getStartIndex();
      else if (firstDiffNode != null)
         startChange = firstDiffNode.getStartIndex();
      return index >= startChangeOffset && index < endParseChangeNewOffset;
   }
      */

   /** Looks to see if there's a smaller parse node which has the same start index as the parent. */
   void addSameAgainChildren(IParseNode startPN) {
      if (!(startPN instanceof ParentParseNode))
         return;

      ParentParseNode start = (ParentParseNode) startPN;
      if (start.children == null)
         return;

      int startIx = start.getStartIndex();
      for (Object child:start.children) {
         if (child != null) {
            // Don't dig down past the first or last diff nodes
            if (child == firstDiffNode || child == lastDiffNode)
               return;
            // We need an IParseNode at least
            if (!(child instanceof IParseNode))
               return;

            IParseNode childPN = (IParseNode) child;
            // If content has advanced we'll go with the parent?
            if (childPN.getStartIndex() != startIx)
               return;

            sameAgainChildren.add(childPN);
            addSameAgainChildren(childPN);
         }
      }
   }

   public boolean isAfterLastNode(Object node, boolean beforeMatch) {
      if (node != null && node == afterLastNode)
         return true;
      if (node instanceof IParseNode) {
         if (sameAgainChildren.contains(node))
            return true;
         // Before the match we check if the node plus it's length is greater
         if (afterLastNode != null) {
            IParseNode pn = (IParseNode) node;
            int nodeStartIndex = pn.getStartIndex();
            // TODO: try this out to reset the sameAgain sooner
            //if (!beforeMatch)
            //   nodeStartIndex += pn.length();
            if (nodeStartIndex >= afterLastNode.getStartIndex())
               return true;
         }
      }
      return false;
   }
}
