package sc.parser;

import java.util.HashSet;
import java.util.TreeSet;

public class DiffContext {
   String text;
   // Text offset into the beginning text of the first unmatching character in both streams
   int startChangeOffset;
   // The first parse-node in the old parse-tree which does not match it's new text
   IParseNode firstDiffNode;
   // The offset into the old text where the text starts matching again
   int endChangeOldOffset;
   // The offset into the new text where the text starts matching again
   int endChangeNewOffset;
   IParseNode lastDiffNode;

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
