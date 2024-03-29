/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import sc.dyn.DynUtil;
import sc.dyn.ScheduledJob;
import sc.sync.SyncManager;
import sc.type.PTypeUtil;
import sc.util.PerfMon;

import java.util.*;

/**
 * Extended to implement a new scope.  Each ScopeContext manages values obtained with it's scope's lifecycle (e.g. one per session, per-app-per-session, global, per-app but global, per-request or
 * with future extensions scopes like: per-store, per-merchant, etc.
 *
 * Scope definitions can be hierarchical - i.e. a scope can have a parent scope.  Listeners on a scope can receive events from child scopes - so if you listen on the window, you'll receive changes on
 * the session, global, or window.
 *
 * To send an event, call scopeChanged() on the ScopeContext.
 */
@sc.js.JSSettings(jsModuleFile="js/scgen.js", prefixAlias="sc_")
public abstract class ScopeContext {
   ScopeDestroyListener destroyListener;

   ArrayList<ScopeContext> parentContexts;
   HashSet<ScopeContext> childContexts;

   ArrayList<IScopeChangeListener> changeListeners;

   public ArrayList<ScheduledJob> toRunLater = new ArrayList<ScheduledJob>();

   /** Adds a value to the scope context that will be disposed when the scope is destroyed */
   public abstract void setValue(String name, Object value);

   public abstract void setValueByRef(String name, Object value);

   public abstract Object getValue(String name);

   public abstract ScopeDefinition getScopeDefinition();

   public abstract String getId();

   // Like the above but with more info - used as the title for ScopeContext in management UIs
   public String getTraceId() {
      return getId();
   }

   // NOTE: returns the underlying map which is storing the values for efficiency.
   public abstract Map<String,Object> getValues();

   public void setDestroyListener(ScopeDestroyListener listener) {
      destroyListener = listener;
   }

   private boolean refreshPending = false;

   private boolean destroyed = false;

   // Debug only
   private long createTime = System.currentTimeMillis();

   // Used for synchronizing the set/get of eventListener
   public final Object eventListenerLock = new Object();

   // Used for receiving cross-scope binding events
   public IScopeEventListener eventListener = null;

   // Optional set of type names to restrict which types are sent to the client from this context.
   public Set<String> syncTypeFilter = null;

   // Optional set of type names for types where we only send reset state for resetting a lost session
   public Set<String> resetSyncTypeFilter = null;

   public void scopeDestroyed(ScopeContext fromParent) {
      if (destroyed) {
         return;
      }
      destroyed = true;
      if (ScopeDefinition.trace)
         System.out.println("Destroy ScopeContext: " + this);
      if (destroyListener != null)
         destroyListener.scopeDestroyed(this);
      if (parentContexts != null) {
         for (ScopeContext par:parentContexts) {
            if (fromParent != par && !par.destroyed && !par.removeChildContext(this))
               System.err.println("*** Failed to remove child context");
         }
      }
      if (childContexts != null && childContexts.size() > 0) {
         ArrayList<ScopeContext> childrenToRemove = new ArrayList<ScopeContext>(childContexts);
         childContexts = null;
         for (ScopeContext child:childrenToRemove) {
            child.scopeDestroyed(this);
         }
      }
   }

   /** Returns true if this scope is active in the current thread state. */
   public boolean isCurrent() {
      return false;
   }

   public synchronized void addChildContext(ScopeContext childCtx) {
      if (childContexts == null)
         childContexts = new HashSet<ScopeContext>();
      childContexts.add(childCtx);
      if (ScopeDefinition.trace)
         System.out.println("Added child context: " + childCtx.getScopeDefinition().getExternalName() + " to: " + getScopeDefinition().getExternalName() + " with: " + childContexts.size() + " total");
   }

   public void addParentContext(ScopeContext parent) {
      if (parentContexts == null)
         parentContexts = new ArrayList<ScopeContext>();
      parentContexts.add(parent);
   }

   public synchronized boolean removeChildContext(ScopeContext childCtx) {
      boolean res = childContexts != null && childContexts.remove(childCtx);
      if (res) {
         if (ScopeDefinition.trace) {
            System.out.println("Removed child context: " + childCtx.getScopeDefinition().getExternalName() + " from: " + getScopeDefinition().getExternalName() + " with: " + childContexts.size() + " remaining");
         }
      }
      else {
         System.err.println("Failed to remove child context: " + this);
      }
      return res;
   }

   /** Should be called to initialize the parent/child scopeContexts */
   public void init() {
      if (destroyed) {
         System.err.println("*** Reinitializing destroyed scope context");
         destroyed = false;
      }
      ScopeDefinition def = getScopeDefinition();
      if (def != null) {
         List<ScopeDefinition> parScopeDefs = def.getParentScopes();
         if (parScopeDefs != null) {
            for (ScopeDefinition parScopeDef: parScopeDefs) {
               ScopeContext parCtx = parScopeDef.getScopeContext(true);
               if (parCtx != null) {
                  parCtx.addChildContext(this);
                  addParentContext(parCtx);
               }
            }
         }
      }
   }

   public synchronized void scopeChanged() {
      if (!refreshPending) {
         refreshPending = true;
         DynUtil.invokeLater(new Runnable() {
            public void run() {
               notifyListeners();
            }
            public String toString() {
               return "scope changed listener for: " + ScopeContext.this.toString();
            }
         }, 0);
      }
      // when the session changes, potentially all of the windows will change
      if (childContexts != null)
         for (ScopeContext child:childContexts)
            child.scopeChanged();
   }

   private void notifyListeners() {
      refreshPending = false;
      if (changeListeners != null) {
         ArrayList<IScopeChangeListener> listenersToNotify;
         synchronized (this) {
            listenersToNotify = new ArrayList<IScopeChangeListener>(changeListeners);
         }
         for (IScopeChangeListener listener:listenersToNotify)
            listener.scopeChanged();
      }
   }

   public synchronized void addChangeListener(IScopeChangeListener listener) {
      if (changeListeners == null)
         changeListeners = new ArrayList<IScopeChangeListener>();
      changeListeners.add(listener);
   }

   public synchronized boolean removeChangeListener(IScopeChangeListener listener) {
      if (changeListeners == null)
         return false;
      return changeListeners.remove(listener);
   }

   public Object getSingletonForType(Object typeObj) {
      Map<String,Object> valueMap = getValues();
      Object firstInst = null;
      if (valueMap != null) {
         for (Object inst:valueMap.values()) {
            if (DynUtil.isAssignableFrom(typeObj, DynUtil.getType(inst))) {
               if (firstInst == null)
                  firstInst = inst;
               else
                  return null; // More than one instance of the same type - not going to return any in this case since it's not a singleton
            }
         }
      }
      return firstInst;
   }

   public ScopeContext getParentContext(String scopeName) {
      if (parentContexts != null) {
         for (ScopeContext ctx:parentContexts) {
            if (ctx.getScopeDefinition().getExternalName().equals(scopeName))
               return ctx;
         }
         for (ScopeContext ctx:parentContexts) {
            ScopeContext parCtx = ctx.getParentContext(scopeName);
            if (parCtx != null)
               return parCtx;
         }
      }
      return null;
   }

   public HashSet<ScopeContext> getChildContexts() {
      return childContexts;
   }

   public String getTraceInfo() {
      StringBuilder sb = new StringBuilder();
      sb.append("createTime = " + PerfMon.getTimeDelta(createTime) + "\n");
      sb.append("current context = " + isCurrent() + "\n");
      sb.append("refreshPending = " + refreshPending + "\n");
      if (destroyed)
         sb.append("*** warning - scopeContext has been destroyed!\n");
      if (parentContexts != null) {
         for (int i = 0; i < parentContexts.size(); i++) {
            ScopeContext parCtx = parentContexts.get(i);
            sb.append("parent = " + parCtx + "\n");
         }
      }
      if (childContexts != null) {
         sb.append("numChildren = " + childContexts.size() + "\n");
      }
      return sb.toString();
   }

   public void addInvokeLater(Runnable r, int priority, CurrentScopeContext curScopeCtx) {
      ScheduledJob job = new ScheduledJob();
      job.toInvoke = r;
      job.priority = priority;
      job.curScopeCtx = curScopeCtx;
      ScheduledJob.addToJobList(toRunLater, job);
      scopeChanged();
   }

   public void closeScopeContext() {
   }
}
