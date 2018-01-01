/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import sc.dyn.DynUtil;
import sc.sync.SyncManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

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

   public abstract void setValue(String name, Object value);

   public abstract Object getValue(String name);

   public abstract ScopeDefinition getScopeDefinition();

   public abstract String getId();

   // NOTE: returns the underlying map which is storing the values for efficiency.
   public abstract Map<String,Object> getValues();

   public void setDestroyListener(ScopeDestroyListener listener) {
      destroyListener = listener;
   }

   private boolean refreshPending = false;

   // Used for synchronizing the set/get of eventListener
   public final Object eventListenerLock = new Object();

   // Used for receiving cross-scope binding events
   public IScopeEventListener eventListener = null;

   public void scopeDestroyed() {
      if (destroyListener != null)
         destroyListener.scopeDestroyed(this);
      if (parentContexts != null) {
         for (ScopeContext par:parentContexts) {
            if (!par.removeChildContext(this))
               System.err.println("*** Failed to remove child context");
         }
      }
      if (childContexts != null && childContexts.size() > 0) {
         ArrayList<ScopeContext> childrenToRemove = new ArrayList<ScopeContext>(childContexts);
         childContexts = null;
         for (ScopeContext child:childrenToRemove)
            child.scopeDestroyed();
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
   }

   public void addParentContext(ScopeContext parent) {
      if (parentContexts == null)
         parentContexts = new ArrayList<ScopeContext>();
      parentContexts.add(parent);
   }

   public synchronized boolean removeChildContext(ScopeContext childCtx) {
      return childContexts != null && childContexts.remove(childCtx);
   }

   /** Should be called to initialize the parent/child scopeContexts */
   public void init() {
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
         for (Object inst:valueMap.entrySet()) {
            if (DynUtil.isAssignableFrom(typeObj, DynUtil.getType(inst))) {
               if (firstInst == null)
                  firstInst = inst;
               else
                  return null; // More than one instance of the same type - not going to return any in this case since it's not a singleton
            }
         }
      }
      return null;
   }

}
