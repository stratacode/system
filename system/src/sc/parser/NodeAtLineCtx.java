/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.ISemanticNode;

/** Used to walk the parse node tree and find the semantic value at a given line number. */
public class NodeAtLineCtx extends LineFormatContext {
   ISemanticNode lastVal;

   public NodeAtLineCtx() {
      super();
      curLines = 0; // When finding the line for a node we start at 1 but here we start at 0.
   }
}
