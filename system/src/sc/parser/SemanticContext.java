/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

public abstract class SemanticContext {
   public abstract Object resetToIndex(int ix);
   public abstract void restoreToIndex(int ix, Object resetReturnValue);
}
