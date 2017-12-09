/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import sc.type.PTypeUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Used to capture, save and restore the set of scope contexts from a current context, so we can run code in another thread
 * using the same context.   There are two use cases, but only one of them is currently being used.  The original use case
 * was for when we run data-binding operations in another thread that needs the context from when the binding change event occurred.
 * This happens when an object in session scope receives events from a global object in a context, like a model changed.
 * The session object can get the CurrentContextState when it's defined and push that context state before executing
 * code that depends on the session being available.
 *
 * The new use case is for allowing the command-line interpreter to control a specific browser window session.  When it opens the
 * window, it adds the scopeContextName query param which signals the PageDispatcher to get the current scope context and register it
 * with a 'scopeContextName'.  There are methods here which let the command line interpreter wait for the CurrentScopeContext to be 'ready'
 * which basically means the client is waiting for commands.  At that point, we continue the test-script and use that CurrentScopeContext
 * to override the default scope lookup.
 */
@sc.js.JSSettings(jsModuleFile="js/scgen.js", prefixAlias="sc_")
public class CurrentScopeContext {
   private static final int MAX_STATE_LIST_SIZE = 32;

   private static final Object scopeContextNamesLock = new Object();
   static HashMap<String,CurrentScopeContext> scopeContextNames = null;

   // List of ScopeContexts active
   List<ScopeContext> scopeContexts = new ArrayList<ScopeContext>();
   // Optional list of locks to acquire to support these contexts
   List<Object> locks = null;

   // Used for debug logging
   public String scopeContextName, traceInfo;

   // Flag set to true when there is a thread waiting for change events for this CurrentScopeContext - it is used as a trigger to wake up the test script (or another waiter) when
   // a scopeContextName has been created, and the corresponding client is waiting for idle events for this window (or another scope).
   boolean contextIsReady = false;

   public CurrentScopeContext(List<ScopeContext> scopeContexts, List<Object> locks) {
      this.scopeContexts = scopeContexts;
      this.locks = locks;
   }

   ScopeContext getScopeContext(int scopeId) {
      for (int i = 0; i < scopeContexts.size(); i++) {
         ScopeContext ctx = scopeContexts.get(i);
         if (ctx != null && ctx.getScopeDefinition().scopeId == scopeId)
            return ctx;
      }
      return null;
   }

   /**
    * Call this to temporarily restore a CurrentScopeContext retrieved from getCurrentScopeContext.   You must call popScopeContext
    * in a finally clause after you've called this method.
    */
   public static void pushCurrentScopeContext(CurrentScopeContext state, boolean acquireLocks) {
      ArrayList<CurrentScopeContext> curStateList = (ArrayList<CurrentScopeContext>) PTypeUtil.getThreadLocal("scopeStateStack");
      if (state != null) {
         if (acquireLocks)
            state.acquireLocks();
         if (ScopeDefinition.verbose)
            System.out.println("Begin scope context: " + state);
      }
      if (curStateList == null) {
         curStateList = new ArrayList<CurrentScopeContext>();
         PTypeUtil.setThreadLocal("scopeStateStack", curStateList);
      }
      curStateList.add(state);
      if (curStateList.size() > MAX_STATE_LIST_SIZE)
         throw new IllegalArgumentException("Too many pushCurrentScopeContext calls in a row - max is: " + MAX_STATE_LIST_SIZE);
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
      CurrentScopeContext envCtx = getEnvScopeContextState();
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
      return new CurrentScopeContext(ctxList, null);
   }

   /** Returns the currently pushed CurrentScopeContext (or null if there is not one present) */
   public static CurrentScopeContext getEnvScopeContextState() {
      ArrayList<CurrentScopeContext> curStateList = (ArrayList<CurrentScopeContext>) PTypeUtil.getThreadLocal("scopeStateStack");
      if (curStateList != null && curStateList.size() > 0) {
         return curStateList.get(curStateList.size() - 1);
      }
      return null;
   }


   public static void register(String scopeContextName, CurrentScopeContext ctx) {
      ctx.scopeContextName = scopeContextName;
      synchronized (scopeContextNamesLock) {
         if (scopeContextNames == null)
            scopeContextNames = new HashMap<String,CurrentScopeContext>();
         scopeContextNames.put(scopeContextName, ctx);
         scopeContextNamesLock.notify();
      }
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
      do {
         synchronized (scopeContextNamesLock) {
            CurrentScopeContext ctx = scopeContextNames.get(scopeContextName);
            if (ctx == null) {
               if (now - startTime > timeout) {
                  break;
               }
               try {
                  scopeContextNamesLock.wait(timeout);
               }
               catch (InterruptedException exc) {}
            }
            else
               return ctx;
         }
         now = System.currentTimeMillis();
      } while (true);

      if (ScopeDefinition.verbose)
         System.out.println("waitForCreate(" + scopeContextName + ", " + timeout + ") - timed out in " + (now - startTime) + " millis");
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
            try {
               ctx.wait(timeout - (now - startTime));
            }
            catch (InterruptedException exc) {}
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
      CurrentScopeContext ctx = null;
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


   public synchronized void markWaiting(boolean val) {
      contextIsReady = val;
      if (val)
         notify();
   }

   public String getTraceInfo() {
      return (scopeContextName != null ? scopeContextName + ": " : "") + traceInfo;
   }

   public void setTraceInfo(String info) {
      this.traceInfo = info;
   }

   public void acquireLocks() {
      if (locks != null)
         PTypeUtil.acquireLocks(locks, ScopeDefinition.traceLocks ? getTraceInfo() : null);
   }

   public void releaseLocks() {
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
}
