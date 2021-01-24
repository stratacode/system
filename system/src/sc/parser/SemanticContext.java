/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

/**
 * The semantic context is a hook used for when the generated parse-tree requires additional contextual information
 * to be work (e.g. HTML with it's stack of tag names).  You can subclass this class and manage whatever state you want in the 'accept' methods
 * of a grammar's parselets.  You must record the range of where your information is relevant though and deal with
 * the parser's need to go back and forward - which means undoing and redoing the semantic context's fields.
 */
public abstract class SemanticContext {
   public abstract Object resetToIndex(int ix);
   public abstract void restoreToIndex(int ix, Object resetReturnValue);
}
