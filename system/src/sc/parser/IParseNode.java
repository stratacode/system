/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.ISemanticNode;

import java.util.IdentityHashMap;

/**
 * The core interface for the parse-tree which is built by the parselets.  This tree maps one-to-one to productions
 * from the parselets tree and retains pointers back into the grammar so you can update the tree incrementally.
 * Each parse node also may have a semantic value - the AST or behavioral tree that's created from the parse tree.
 * There are fewer nodes in the semantic value tree and that's usually what the program manipulates.  The semantic value
 * points to it's top-level parse node if it implements the ISemanticNode interface (which is required for the bi-directional
 * and incremental updates).
 * <p>We do occasionally compress the leaf-nodes of the parse-node tree for speed and probably should do more of that</p>
 *
 * TODO: for performance - maybe this should only be an abstract class.  Also we should use final methods for things which are not overridden
 * as this is a high-bandwidth set of classes.
 * */
public interface IParseNode extends CharSequence, IParseResult {
   void setParselet(Parselet p);

   Object getSemanticValue();

   void setSemanticValue(Object value, boolean clearOld);

   String toDebugString();

   boolean refersToSemanticValue(Object v);

   void format(FormatContext ctx);

   /** Reformats the parse node - replacing any explicit spacing nodes with the generateParseNode in the tree.  The next toString will then reformat the parse nodes */
   IParseNode reformat();

   /** Returns the first character value produced or -1 if there is none */
   int firstChar();

   CharSequence toSemanticString();

   int toSemanticStringLength();

   /** Returns the offset in characters from the beginning of the file */
   int getStartIndex();

   /** If we have a parse-node which has been copied from an old definition, the original start index */
   int getOrigStartIndex();

   /** If this parse-node has been reparsed already, this returns the new start index.  Otherwise -1 */
   int getNewStartIndex();

   void setStartIndex(int s);

   /** Returns the index of the string relative to the start of this parse node */
   int indexOf(String subtr);

   int lastIndexOf(String subtr);

   /** If you take a language string and reparse it this lets you advance the start index to where it originally existed in the file (e.g. for HTML attribute expressions which are not parsed as part of the original grammar) */
   void advanceStartIndex(int s);

   /** Returns the number of characters in the parse node */
   int length();

   boolean isEmpty();

   /** Like toString but provides a parent object to handle spacing with re-generated primitive string valued nodes which do not know their parent. */
   CharSequence formatString(Object parSemVal, ParentParseNode parParseNode, int curChildIndex, boolean removeFormattingNodes);

   /** Just like formatString, we need to take an optional parent semantic value, parseNode, and childIndex so we know where this element falls in the context so we can do spacing properly */
   void styleNode(IStyleAdapter adapter, Object parSemVal, ParentParseNode parParseNode, int curChildIndex);

   void formatStyled(FormatContext ctx, IStyleAdapter adapter);

   void changeLanguage(Language lang);

   IParseNode deepCopy();

   void updateSemanticValue(IdentityHashMap<Object,Object> oldNewMap);

   boolean isCompressedNode();

   /** Internal method used to walk the parse tree to find the line number for a given parse node (which must be in the tree). */
   void computeLineNumberForNode(LineFormatContext ctx, IParseNode toFindPN);

   ISemanticNode getNodeAtLine(NodeAtLineCtx ctx, int requiredLineNum);

   /** Find the parse node at the specified start index.  If matchParselet is not null the match is only returned if it is the same as the parselet specified */
   IParseNode findParseNode(int startIndex, Parselet matchParselet);

   /**
    * After updating some parse nodes, you may need to reset the start indexes.  This sets the start index to the one given and returns the start index to
    * continue after this parse node.
    */
   int resetStartIndex(int ix, boolean validate, boolean newIndex);

   /** Returns the length of the parse node eliminating any trailing whitespace */
   int getSemanticLength();

   /** Finds the first parse node whose text does not match the DiffContext */
   void findStartDiff(DiffContext ctx, boolean atEnd, Object parSemVal, ParentParseNode parParseNode, int childIx);

   void findEndDiff(DiffContext ctx, Object parSemVal, ParentParseNode parParseNode, int childIx);

   /** Returns true for ErrorParseNode or parse nodes which are part of an error */
   boolean isErrorNode();

   void setErrorNode(boolean val);

   /** Returns true if any nodes in this tree have been through the 'generate' process.  These nodes might have formatting parse nodes that need to be replaced for some operations */
   boolean isGeneratedTree();

   /**
    * Used for reparse only, this method determines how many semantic value elements were produced by this parse node.
    *
    * Returns 1 for scalar semantic values or for a repeat value, the number of elements in the semantic value array.
    * It's not just the size of the semantic value List because during reparse, we combine all of the elements from
    * multiple parse nodes into one List when there's one repeat array node which has a child parselet that's also a
    * repeat array node.  Instead, we look at the number of child parse nodes and use that to determine how many
    * array values came from the this node.
    */
   int getNumSemanticValues();

   void diffParseNode(IParseNode other, StringBuilder diffs);
}



