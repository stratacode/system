/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import sc.dyn.DynUtil;
import sc.obj.CurrentScopeContext;
import sc.sync.SyncManager;

import java.util.ArrayList;
import java.util.List;

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
   // For crossScope bindings, this stores the list of CurrentScopeContexts where change events should be delivered. If
   // the current CurrentScopeContext matches one in this list, it's sent right away. If not it's queued and delivered the
   // next time a thread runs in that context;
   List<CurrentScopeContext> curScopeCtxs;

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
         addCurrentScopeContext(CurrentScopeContext.getCurrentScopeContext());

      int propFlags = (flags & Bind.PROPAGATED_FLAGS);
      if (propFlags != 0) {
         initFlagsOnChildren(propFlags);
      }
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

   public void accessBinding() {
      if ((flags & Bind.CROSS_SCOPE) != 0) {
         addCurrentScopeContext(CurrentScopeContext.getCurrentScopeContext());
      }
   }

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

   public List<CurrentScopeContext> getCurrentScopeContexts() { return curScopeCtxs; }

   public void addCurrentScopeContext(CurrentScopeContext ctx) {
      if (curScopeCtxs == null)
         curScopeCtxs = new ArrayList<CurrentScopeContext>();
      else if (curScopeCtxs.contains(ctx))
         return;
      curScopeCtxs.add(ctx);
   }

   protected void initFlagsOnChildren(int flags) {
      this.flags |= flags;
   }
}
