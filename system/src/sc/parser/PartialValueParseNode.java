/*
 * Copyright (c) 2016. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

/**
 * Used for the top-level parse-node when we were unable to parse the entire document.  Stores
 * the number of bytes not processed.
 */
public class PartialValueParseNode extends ParentParseNode {
   int unparsedLen;
   public static ParentParseNode copyFrom(ParentParseNode orig, int unparsedLen) {
      PartialValueParseNode newPN = new PartialValueParseNode();
      newPN.children = orig.children;
      newPN.startIndex = orig.startIndex;
      newPN.value = orig.value;
      newPN.generated = orig.generated;
      newPN.parselet = orig.parselet;
      newPN.unparsedLen = unparsedLen;
      return newPN;
   }
}
