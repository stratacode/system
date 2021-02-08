/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import sc.dyn.DynUtil;
import sc.dyn.ScheduledJob;
import sc.type.PTypeUtil;

import java.util.*;

/**
 * Used to define, save and restore the list of scope contexts used in a given operation.
 * For 'crossScope' data binding expressions, we capture the CurrentScopeContext when the binding is created, so we
 * can restore it later to communicate in the context necessary for that binding.
 * Or more specifically, if an event occurs, which is queued to a different context, the data-binding operations will temporarily restore the
 * CurrentScopeContext before firing the binding so it has access to necessary state and locks required to fire.
 * For example, when an object in session scope receives events from a global object in a context, like a JavaModel changed and
 * we want to update the sessions that are viewing that JavaModel.  That binding is marked with @Bindable(crossScope=true) and
 * we know to queue the binding and save/restore the context before applying the change.
 *
 * Another use case is for allowing the command-line interpreter to control a specific browser window session.  When it opens the
 * window, it adds the scopeContextName query param which signals the PageDispatcher to get the current scope context and register it
 * with a 'scopeContextName'.  There are methods here which let the command line interpreter wait for the CurrentScopeContext to be 'ready'
 * which basically means the client is waiting for commands.  At that point, we continue the test-script and use that CurrentScopeContext
 * to override the default scope lookup.
 *
 * We can target commands to a specific scope within the CurrentScopeContext using the getScopeContext/ByName methods.  So for a test
 * script, when we invoke a command we make sure it goes to the intended window in a multi-window test.
 */
@sc.js.JSSettings(jsModuleFile="js/scgen.js", prefixAlias="sc_")
public class CurrentScopeContext {
   private static final int MAX_STATE_LIST_SIZE = 32;

   private static final Object scopeContextNamesLock = new Object();
   /**
    * Primarily for debugging from the command line, to communicate with a specific browser window (or similar situations)
    * you can register the CurrentScopeContext used to handle that request with a name.  From the command line, you
    * can set the cmd.scopeContextName property to target a specific window in those cases where you have more than one.
    */
   static HashMap<String,CurrentScopeContext> scopeContextNames = null;

   // List of ScopeContexts active
   public List<ScopeContext> scopeContexts = new ArrayList<ScopeContext>();
   // Optional list of locks to acquire to support these contexts
   List<Object> locks = null;

   // Used for debug logging
   public String scopeContextName, traceInfo;

   // Flag set to true when there is a thread waiting for change events for this CurrentScopeContext - it is used as a trigger to wake up the test script (or another waiter) when
   // a scopeContextName has been created, and the corresponding client is waiting for idle events for this window (or another scope).
   boolean contextIsReady = false;

   Thread lockThread = null;

   public CurrentScopeContext(List<ScopeContext> scopeContexts, List<Object> locks) {
      this.scopeContexts = scopeContexts;
      this.locks = locks;
   }

   public ScopeContext getScopeContext(int scopeId) {
      for (int i = 0; i < scopeContexts.size(); i++) {
         ScopeContext ctx = scopeContexts.get(i);
         if (ctx != null && ctx.getScopeDefinition().scopeId == scopeId)
            return ctx;
      }
      return null;
   }

   /** Returns a specific ScopeContext from this CurrentScopeContext - perhaps the "window" scope to use for an DynUtil.invokeRemote call.  */
   public ScopeContext getScopeContextByName(String scopeName) {
      for (int i = 0; i < scopeContexts.size(); i++) {
         ScopeContext ctx = scopeContexts.get(i);
         if (ctx != null && ctx.getScopeDefinition().getExternalName().equals(scopeName))
            return ctx;
      }
      return null;
   }

   public void startScopeContext(boolean acquireLocks) {
      if (acquireLocks) {
         acquireLocks();
         if (scopeContexts != null) {
            for (ScopeContext ctx:scopeContexts) {
               if (ctx.eventListener != null)
                  ctx.eventListener.startContext();
            }
         }
      }
      if (ScopeDefinition.verbose)
         System.out.println("Begin scope context: " + this);
   }

   /**
    * Call this to temporarily restore a CurrentScopeContext retrieved from getCurrentScopeContext.   You must call popScopeContext
    * in a finally clause after you've called this method.
    */
   public static void pushCurrentScopeContext(CurrentScopeContext state, boolean acquireLocks) {
      ArrayList<CurrentScopeContext> curStateList = (ArrayList<CurrentScopeContext>) PTypeUtil.getThreadLocal("scopeStateStack");
      if (curStateList == null) {
         curStateList = new ArrayList<CurrentScopeContext>();
         PTypeUtil.setThreadLocal("scopeStateStack", curStateList);
      }
      curStateList.add(state);
      if (curStateList.size() > MAX_STATE_LIST_SIZE)
         throw new IllegalArgumentException("Too many pushCurrentScopeContext calls in a row - max is: " + MAX_STATE_LIST_SIZE);

      // Need to dispatch any event listeners after the current context is set - otherwise, those events could just get queued up again
      if (state != null) {
         state.startScopeContext(acquireLocks);
      }
   }

   public static void popCurrentScopeContext(boolean releaseLocks) {
      ArrayList<CurrentScopeContext> curStateList = (ArrayList<CurrentScopeContext>) PTypeUtil.getThreadLocal("scopeStateStack");
      if (curStateList == null || curStateList.size() == 0) {
         throw new IllegalArgumentException("Empty scopeContext stack!");
      }
      CurrentScopeContext rem = curStateList.remove(curStateList.size() - 1);
      if (ScopeDefinition.verbose) {
         System.out.println("End scope context: " + rem);
      }
      if (releaseLocks && rem != null)
         rem.releaseLocks();
   }


   /**
    * Call this to retrieve the current set of scope contexts that are available to your object.  For methods that may be called
    * from a different scope environment, you can then use the returned CurrentScopeContext to run code again in the context of that original code from the scope perspective.
    */
   public static CurrentScopeContext getCurrentScopeContext() {
      CurrentScopeContext envCtx = getThreadScopeContext();
      if (envCtx != null)
         return envCtx;
      ArrayList<ScopeContext> ctxList = new ArrayList<ScopeContext>();
      for (ScopeDefinition scope : ScopeDefinition.scopes) {
         if (scope == null)
            continue;
         ScopeContext scopeCtx = scope.getScopeContext(false);
         if (scopeCtx != null)
            ctxList.add(scopeCtx);
      }
      // TODO: synchronization: add default locking for scopes.  In the web framework now, we're getting away from this since we already build the CurrentScopeContext for a page
      // but we should have a way a default lock for a ScopeContext and some flags to control which locks it includes.
      return new CurrentScopeContext(ctxList, null);
   }

   /** Returns the currently pushed CurrentScopeContext (or null if there is not one present) */
   public static CurrentScopeContext getThreadScopeContext() {
      ArrayList<CurrentScopeContext> curStateList = (ArrayList<CurrentScopeContext>) PTypeUtil.getThreadLocal("scopeStateStack");
      if (curStateList != null && curStateList.size() > 0) {
         return curStateList.get(curStateList.size() - 1);
      }
      return null;
   }

   /**
    * Register the CurrentScopeContext - so you can target a specific ScopeContext later via remoteMethod calls (e.g. using
    * cmd.scopeContextName or to find the ScopeContext param to DynUtil.invokeRemote.   So far, this is only used in debug mode
    * to make test scripts more flexible in being able to deal with multi-browser window applications.
    */
   public static CurrentScopeContext register(String scopeContextName, CurrentScopeContext ctx) {
      ctx.scopeContextName = scopeContextName;
      CurrentScopeContext old;
      synchronized (scopeContextNamesLock) {
         if (scopeContextNames == null)
            scopeContextNames = new HashMap<String,CurrentScopeContext>();
         old = scopeContextNames.put(scopeContextName, ctx);
         scopeContextNamesLock.notify();
      }
      return old;
   }

   public static CurrentScopeContext get(String scopeContextName) {
      synchronized (scopeContextNamesLock) {
         if (scopeContextNames == null)
            scopeContextNames = new HashMap<String,CurrentScopeContext>();
         return scopeContextNames.get(scopeContextName);
      }
   }

   public static boolean remove(String scopeContextName) {
      synchronized (scopeContextNamesLock) {
         if (scopeContextNames == null)
            scopeContextNames = new HashMap<String,CurrentScopeContext>();
         return scopeContextNames.remove(scopeContextName) != null;
      }
   }

   public static CurrentScopeContext waitForCreate(String scopeContextName, long timeout) {
      synchronized (scopeContextNamesLock) {
         if (scopeContextNames == null)
            scopeContextNames = new HashMap<String,CurrentScopeContext>();
      }
      long now = System.currentTimeMillis();
      long startTime = now;
      boolean waited = false;
      do {
         synchronized (scopeContextNamesLock) {
            CurrentScopeContext ctx = scopeContextNames.get(scopeContextName);
            if (ctx == null) {
               if (now - startTime > timeout) {
                  break;
               }
               CurrentScopeContext threadCtx = CurrentScopeContext.getThreadScopeContext();
               if (threadCtx != null)
                  threadCtx.releaseLocks();
               try {
                  if (ScopeDefinition.verbose || PTypeUtil.testMode)
                     System.out.println("--- Waiting for client: " + scopeContextName);

                  waited = true;
                  scopeContextNamesLock.wait(timeout);
               }
               catch (InterruptedException exc) {}
               finally {
                  if (threadCtx != null)
                     threadCtx.acquireLocks();
               }
            }
            else {
               if (waited && (ScopeDefinition.verbose || PTypeUtil.testMode))
                  System.out.println("- Client connected: " + scopeContextName + (PTypeUtil.testVerifyMode ? "" : " in: " + (now - startTime) + " millis"));
               return ctx;
            }
         }
         now = System.currentTimeMillis();
      } while (true);

      if (ScopeDefinition.verbose || PTypeUtil.testMode)
         System.out.println("* Wait for client: " + scopeContextName + " timed out in: " + (now - startTime) + " millis");
      return null;
   }

   public static CurrentScopeContext waitForReady(String scopeContextName, long timeout) {
      long startTime = System.currentTimeMillis();
      CurrentScopeContext ctx = waitForCreate(scopeContextName, timeout);
      if (ctx == null) {
         return null;
      }
      long now;
      do {
         now = System.currentTimeMillis();
         synchronized (ctx) {
            if (ctx.contextIsReady) { // A thread is already waiting on this scopeContextName
               break;
            }
            if (now - startTime > timeout) {
               ctx = null;
               break;
            }
            CurrentScopeContext threadCtx = CurrentScopeContext.getThreadScopeContext();
            if (threadCtx != null) {
               threadCtx.popCurrentScopeContext(true);
            }
            try {
               long waitTime = timeout - (now - startTime);
               if (ScopeDefinition.verbose)
                  System.out.println("waitForReady waiting up to: " + waitTime + " millis for scope context to be ready: " + scopeContextName);
               ctx.wait(waitTime);
            }
            catch (InterruptedException exc) {}
            finally {
               if (threadCtx != null)
                  threadCtx.acquireLocks();
            }
         }
      } while (true);

      if (ctx != null) {
         if (ScopeDefinition.verbose)
            System.out.println("waitForReady(" + scopeContextName + ", " + timeout + ") - scope ready: " + ctx + " after: " + (now - startTime) + " millis");
      }
      else {
         if (ScopeDefinition.verbose)
            System.out.println("waitForReady(" + scopeContextName + ", " + timeout + ") - timed out: scope created but not ready in " + (now - startTime) + " millis");
      }
      return ctx;
   }

   public static void markReady(String scopeContextName, boolean val) {
      CurrentScopeContext ctx;
      synchronized (scopeContextNamesLock) {
         ctx = scopeContextNames.get(scopeContextName);
      }
      if (ctx == null)
         System.err.println("**** CurrentScopeContext.markReady - no scope for scopeContextName: " + scopeContextName);
      else if (!ctx.contextIsReady) {
         if (ScopeDefinition.verbose)
            System.out.println("ScopeContextName " + scopeContextName + " is ready");
         ctx.markWaiting(val);
      }
   }

   public static void closeScopeContext(String scopeContextName) {
      CurrentScopeContext ctx;
      synchronized (scopeContextNamesLock) {
         ctx = scopeContextNames.get(scopeContextName);
      }
      if (ctx == null)
         System.err.println("**** CurrentScopeContext.closeScopeContext - no scope for scopeContextName: " + scopeContextName);
      else {
         if (ctx.scopeContexts != null) {
            if (ScopeDefinition.verbose)
               System.out.println("Closing scopeContextName " + scopeContextName);
            for (ScopeContext scopeContext:ctx.scopeContexts) {
               scopeContext.closeScopeContext();
               scopeContext.scopeChanged();
            }
         }
      }
   }

   /** Wake up anyone listening for changes to the event context in this current scope context */
   public void scopeChanged() {
      ScopeContext eventCtx = getEventScopeContext();
      if (eventCtx != null)
         eventCtx.scopeChanged();
   }

   public synchronized void markWaiting(boolean val) {
      contextIsReady = val;
      if (val)
         notify();
   }

   public String getTraceInfo() {
      if (traceInfo == null)
         traceInfo = scopeContexts == null ? " <no scope contexts>" : " " + scopeContexts.toString();

      return (scopeContextName != null ? " " + scopeContextName + ":" : "") + traceInfo;
   }

   public void setTraceInfo(String info) {
      this.traceInfo = info;
   }

   public void acquireLocks() {
      Thread thisThread = Thread.currentThread();
      if (lockThread == thisThread) {
         System.err.println("*** Locks for context scope already acquired: " + this);
         return;
      }
      if (locks != null)
         PTypeUtil.acquireLocks(locks, ScopeDefinition.traceLocks ? getTraceInfo() : null);
      if (lockThread != null)
         throw new UnsupportedOperationException();
      lockThread = Thread.currentThread();
   }

   public void releaseLocks() {
      Thread cur = Thread.currentThread();
      if (cur != lockThread)
         throw new IllegalArgumentException("Context scope: " + this + " releaseLocks called from thread: " + cur + " when held by: " + lockThread);
      lockThread = null;
      if (locks != null)
         PTypeUtil.releaseLocks(locks, ScopeDefinition.traceLocks ? getTraceInfo() : null);
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("[");
      boolean first = true;
      for (ScopeContext ctx:scopeContexts) {
         if (!first)
            sb.append(",");
         sb.append(ctx);
         first = false;
      }
      sb.append("]");
      return sb.toString();
   }

   public ScopeContext getEventScopeContext() {
      if (scopeContexts != null) {
         int sz = scopeContexts.size();
         for (int i = sz - 1; i >= 0; i--) {
            ScopeContext ctx = scopeContexts.get(i);
            if (ctx.getScopeDefinition().eventListenerCtx)
               return ctx;
         }
      }
      System.err.println("*** No event scope context in the current scope context: " + this);
      return null;
   }

   /** Return the scope attribute for this current scope context */
   /*
   public Object getScopeAttribute(String attributeName) {
      for (ScopeContext ctx:scopeContexts) {
         Object ctxVal = ctx.getValue(attributeName);
         if (ctxVal != null)
            return ctxVal;
      }
      return null;
   }
   */

   /**
    * If there is a singleton instance for this type, return it, otherwise not.  Since we frequently use singletons this method helps
    * diagnostics like the command interpreter where you want to select an instance by it being the only one of it's type for a specific scope context
    * For example, use HtmlPage { } to select whatever page instance is available if there's only one.
     */
   public Object getSingletonForType(Object typeObj) {
      String scopeName = DynUtil.getScopeNameForType(typeObj);
      if (scopeName != null) {
         ScopeContext sctx = getScopeContextByName(scopeName);
         if (sctx != null)
            return sctx.getSingletonForType(typeObj);
         // TODO: including the parent scopes in this search but maybe we should create the CurrentScopeContext with all required scopes?
         // thinking of adding a compile time search for "dependent scopes" that uses the getDependentTypes code.
         for (ScopeContext ctx:scopeContexts) {
            ScopeContext parCtx = ctx.getParentContext(scopeName);
            if (parCtx != null)
               return parCtx.getSingletonForType(typeObj);
         }
      }
      return null;
   }

   public void addSyncTypeToFilter(String typeName, String reason) {
      if (ScopeDefinition.verbose)
         System.out.println("Adding type: " + typeName + " to sync type filter for: " + reason);

      ScopeContext eventScopeCtx = getEventScopeContext();

      if (eventScopeCtx.syncTypeFilter != null) {// null means no filtering in this context
         if (eventScopeCtx.syncTypeFilter instanceof HashSet)
            eventScopeCtx.syncTypeFilter.add(typeName);
         else {
            eventScopeCtx.syncTypeFilter = new HashSet<String>(eventScopeCtx.syncTypeFilter);
            eventScopeCtx.syncTypeFilter.add(typeName);
         }
      }
   }

   public boolean sameContexts(CurrentScopeContext other) {
      if (other == this)
         return true;
      if (scopeContexts != null) {
         if (other.scopeContexts != null) {
            int sz = scopeContexts.size();
            if (sz == other.scopeContexts.size()) {
               for (int i = 0; i < sz; i++) {
                  ScopeContext scopeCtx = scopeContexts.get(i);
                  if (scopeCtx != other.scopeContexts.get(i))
                     return false;
               }
               return true;
            }
         }
      }
      return false;
   }

   public int indexInList(List<CurrentScopeContext> ctxs) {
      if (ctxs == null)
         return -1;
      int ct = 0;
      for (CurrentScopeContext elem:ctxs) {
         if (elem.sameContexts(this))
            return ct;
         ct++;
      }
      return -1;
   }

}
