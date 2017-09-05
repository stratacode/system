/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.sync;

/**
 * Used as a stub for serializing references to java.lang.Class objects over the wire.  The class name is sent and on the remote, we
 * try to look up the type on the remote side.
 */
public class ClassSyncWrapper {
   public String className;
   public ClassSyncWrapper(String cl) {
      className = cl;
   }
}
