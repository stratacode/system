/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

public interface IUndoOp {
   void undo();

   void redo();
}
