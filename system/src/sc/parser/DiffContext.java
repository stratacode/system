package sc.parser;

import java.util.HashSet;
import java.util.TreeSet;

public class DiffContext {
   String text;
   // Text offset into the beginning text of the first unmatching character in both streams
   int startChangeOffset;
   // The child right before the change - in case the change happens at a boundary between two parse-nodes
   IParseNode beforeFirstNode;
   // The first parse-node in the old parse-tree which does not match it's new text
   IParseNode firstDiffNode;
   // The offset into the old text where the text starts matching again
   int endChangeOldOffset;
   // The offset into the new text where the text starts matching again
   int endChangeNewOffset;
   // The offset into the new text which we must consider as changed during reparse.  This starts out the
   // same as endChangeNewOffset but can be adjusted to force us to reparse a result and delay the "sameAgain" setting.
   int endParseChangeNewOffset;
   // The last changed parse-node
   IParseNode lastDiffNode;

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

   public void addChangedParent(IParseNode parentParseNode) {
      changedParents.add(parentParseNode);
   }

   public int getNewOffset() {
      return !sameAgain ? 0 : getDiffOffset();
   }

   public int getDiffOffset() {
      return endChangeNewOffset - endChangeOldOffset;
   }

   public void setChangedRegion(Parser parser, boolean newVal) {
      if (newVal) {
         if (changeStartOffset == -1 || changeStartOffset > parser.currentIndex)
            changeStartOffset = parser.currentIndex;
      }
      else {
         if (changeEndOffset == -1 || changeEndOffset < parser.currentIndex)
            changeEndOffset = parser.currentIndex;
      }
      changedRegion = newVal;
   }

   public void setSameAgain(Parser parser, boolean newVal) {
      if (newVal) {
         if (sameAgainOffset == -1 || sameAgainOffset < parser.currentIndex)
            sameAgainOffset = parser.currentIndex;
      }
      sameAgain = newVal;
   }

   public void changeCurrentIndex(Parser parser, int newIndex) {
      parser.changeCurrentIndex(newIndex);
      if (changeStartOffset != -1 && changeEndOffset != -1) {
         if (newIndex >= changeStartOffset && newIndex < changeEndOffset) {
            changedRegion = true;
         }
         else if (changedRegion)
            changedRegion = false;
      }
      if (sameAgainOffset != -1) {
         if (!sameAgain && newIndex >= sameAgainOffset) {
            sameAgain = true;
         }
         else if (sameAgain && newIndex < sameAgainOffset)
            sameAgain = false;
      }
   }
}
