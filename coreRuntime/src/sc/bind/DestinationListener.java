/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import sc.dyn.DynUtil;
import sc.obj.CurrentScopeContext;
import sc.sync.SyncManager;

/**
 * The base class for binding objects which can be the root level binding - i.e. that have
 * a reference to the dstObj, the dstProp, and are initially given the direction.
 */
public abstract class DestinationListener extends AbstractListener implements IBinding {
   Object dstObj;    // The object whose property is set by a forward binding
   Object dstProp;   // The property/binding set by a forward binding.  dstProp == dstObj for nested bindings
   BindingDirection direction;
   int flags;
   BindOptions opts;
   CurrentScopeContext curScopeCtx;

   protected void initFlags(int flags, BindOptions opts) {
      this.flags = flags;
      this.opts = opts;
      if ((flags & Bind.INACTIVE) != 0)
         activated = false;
      if ((flags & Bind.QUEUED) != 0)
         sync = SyncType.QUEUED;
      else if ((flags & Bind.IMMEDIATE) != 0)
         sync = SyncType.IMMEDIATE;
      else {
         // Depending on the BindingManager, this might be a thread-local lookup to determine whether the framework managing this object requires
         // queuing or not.  We do this to avoid thread-local lookups in each sendEvent method under the
         // theory that there will be at least one sendEvent per property (but maybe that's not the case?)
         sync = Bind.bindingManager.getDefaultSyncType();
      }
      if ((flags & Bind.CROSS_SCOPE) != 0)
         curScopeCtx = CurrentScopeContext.getCurrentScopeContext();
   }

   public String toString(String operation, boolean displayValue) {
      if (dstProp != dstObj) {
         StringBuilder sb = new StringBuilder();
         sb.append(objectToString(dstObj));
         sb.append('.');
         sb.append(dstProp);
         if (direction != null) {
            sb.append(" ");
            sb.append(direction.getOperatorString());
         }
         sb.append(" ");
         return sb.toString();
      }
      return "";
   }

   public String toString() {
      return toString(null);
   }

   public String toString(String op) {
      return toString(op, false);
   }

   public abstract boolean isValid();

   public abstract int refreshBinding();

   public abstract void accessBinding();

   protected void accessObj(Object obj) {
      String scopeName = DynUtil.getScopeName(obj);
      if (scopeName != null)
         SyncManager.accessSyncInst(obj, scopeName);
   }

   protected boolean isValidObject(Object obj) {
      return obj != null && obj != UNSET_VALUE_SENTINEL && obj != PENDING_VALUE_SENTINEL;
   }

   protected boolean isDefinedObject(Object obj) {
      return obj != UNSET_VALUE_SENTINEL && obj != PENDING_VALUE_SENTINEL;
   }

   protected Object getUnsetOrPending(Object val) {
      return val == PENDING_VALUE_SENTINEL ? val : UNSET_VALUE_SENTINEL;
   }

   protected void applyPendingChildValue(Object val, IBinding src) {
   }

   protected abstract Object getBoundValueForChild(IBinding child);

   public boolean getTrace() {
      return (flags & Bind.TRACE) != 0;
   }

   public boolean getVerbose() {
      return (flags & Bind.VERBOSE) != 0;
   }

   public boolean isCrossScope() { return (flags & Bind.CROSS_SCOPE) != 0; }

   public CurrentScopeContext getCurrentScopeContext() { return curScopeCtx; }
}
