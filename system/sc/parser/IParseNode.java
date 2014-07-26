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
public interface IParseNode extends CharSequence {
   Parselet getParselet();

   void setParselet(Parselet p);

   Object getSemanticValue();

   void setSemanticValue(Object value);

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
   CharSequence formatString(Object parSemVal, ParentParseNode parParseNode, int curChildIndex);

   CharSequence toStyledString();

   void formatStyled(FormatContext ctx);

   void changeLanguage(Language lang);

   IParseNode deepCopy();

   void updateSemanticValue(IdentityHashMap<Object,Object> oldNewMap);

   boolean isCompressedNode();

   /** Internal method used to walk the parse tree to find the line number for a given parse node (which must be in the tree). */
   void computeLineNumberForNode(LineFormatContext ctx, IParseNode toFindPN);

   ISemanticNode getNodeAtLine(NodeAtLineCtx ctx, int requiredLineNum);
}



