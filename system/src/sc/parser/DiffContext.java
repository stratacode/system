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
   // The last changed parse-node
   IParseNode lastDiffNode;

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
      return !sameAgain ? 0 : endChangeNewOffset - endChangeOldOffset;
   }
}
