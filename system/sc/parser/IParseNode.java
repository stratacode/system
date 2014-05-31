/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import java.util.IdentityHashMap;

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

   /** If you take a language string and reparse it this lets you advance the start index to where it originally existed in the file (e.g. for HTML attribute expressions which are not parsed as part of the original grammar) */
   void advanceStartIndex(int s);

   /** Returns the number of characters in the parse node */
   int length();

   boolean isEmpty();

   CharSequence toStyledString();

   void formatStyled(FormatContext ctx);

   void changeLanguage(Language lang);

   IParseNode deepCopy();

   void updateSemanticValue(IdentityHashMap<Object,Object> oldNewMap);

   boolean isCompressedNode();
}



