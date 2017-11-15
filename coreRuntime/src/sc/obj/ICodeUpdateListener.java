/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

/**
 * Used to register for changes made to code changes made in the dynamic system.  Triggered once all code changes have
 * are ready to apply.
 */
public interface ICodeUpdateListener {
   void codeUpdated();
}
