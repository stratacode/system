/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.sync.SyncManager;

/**
 * Manages the security mapping from the dynamic runtime, so we can control what operations are allowed in a given context.
 * TODO: expand this so it includes additional runtime semantics like runtime and include more operations.
 */
public class SyncExecutionContext extends ExecutionContext {
   SyncManager.SyncContext syncCtx;
   SyncManager mgr;

   public SyncExecutionContext(JavaModel model, SyncManager.SyncContext syncCtx) {
      super(model);
      this.syncCtx = syncCtx;
      mgr = syncCtx.getSyncManager();
   }

   public boolean allowCreate(Object type) {
      return mgr.allowCreate(type);
   }

   public boolean allowSetProperty(Object type, String propName) {
      return mgr.isSynced(type, propName);
   }

   public boolean allowInvoke(Object method) {
      return mgr.allowInvoke(method);
   }
}
