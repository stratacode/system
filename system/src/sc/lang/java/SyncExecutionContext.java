/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.BindingContext;
import sc.sync.SyncManager;

/**
 * Manages the security mapping from the dynamic runtime, so we can control what operations are allowed in a given context.
 * Also provides access to the data binding system... when applying a sync layer we queue events by default but need to
 * flush them to be sure certain types of expressions stay in sync - e.g. before and after remote method calls.
 * TODO: security - need to do an audit here.  Are there more "allow" methods needed?
 */
public class SyncExecutionContext extends ExecutionContext {
   SyncManager.SyncContext syncCtx;
   SyncManager mgr;
   BindingContext bindCtx;

   public SyncExecutionContext(JavaModel model, SyncManager.SyncContext syncCtx, BindingContext bindCtx) {
      super(model);
      this.syncCtx = syncCtx;
      mgr = syncCtx.getSyncManager();
      this.bindCtx = bindCtx;
   }

   public boolean allowCreate(Object type) {
      return mgr.allowCreate(type);
   }

   public boolean allowSetProperty(Object type, String propName) {
      return mgr.isSynced(type, propName, false);
   }

   public boolean allowInvoke(Object method) {
      if (mgr.allowInvoke(method)) {
         // Make sure we keep the original order of changes with respect to the method invocation
         if (bindCtx != null)
            bindCtx.dispatchEvents(null);
         return true;
      }
      return false;
   }

   public void postInvoke() {
      if (bindCtx != null)
         bindCtx.dispatchEvents(null);
   }
}
