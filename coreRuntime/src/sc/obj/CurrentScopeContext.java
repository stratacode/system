/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import sc.type.PTypeUtil;

import java.util.ArrayList;

/**
 * Used to capture, save and restore the set of scope contexts that affect a given object.
 * For example, an object in session scope might receive events from a global object in a context
 * in which that session is not available.  The session object can get the CurrentContextState when it's
 * defined and push that context state before executing code that depends on the session being available.
 */
@sc.js.JSSettings(jsModuleFile="js/scgen.js", prefixAlias="sc_")
public class CurrentScopeContext {
   private static final int MAX_STATE_LIST_SIZE = 32;

   ArrayList<ScopeContext> scopeContexts = new ArrayList<ScopeContext>();

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
   public static void pushCurrentScopeContext(CurrentScopeContext state) {
      ArrayList<CurrentScopeContext> curStateList = (ArrayList<CurrentScopeContext>) PTypeUtil.getThreadLocal("scopeStateStack");
      if (ScopeDefinition.verbose && state != null)
         System.out.println("Begin scope context: " + state);
      if (curStateList == null) {
         curStateList = new ArrayList<CurrentScopeContext>();
         PTypeUtil.setThreadLocal("scopeStateStack", curStateList);
      }
      curStateList.add(state);
      if (curStateList.size() > MAX_STATE_LIST_SIZE)
         throw new IllegalArgumentException("Too many pushCurrentScopeContext calls in a row - max is: " + MAX_STATE_LIST_SIZE);
   }

   public static void popCurrentScopeContext() {
      ArrayList<CurrentScopeContext> curStateList = (ArrayList<CurrentScopeContext>) PTypeUtil.getThreadLocal("scopeStateStack");
      if (curStateList == null || curStateList.size() == 0) {
         throw new IllegalArgumentException("Empty scopeContext stack!");
      }
      CurrentScopeContext rem = curStateList.remove(curStateList.size() - 1);
      if (ScopeDefinition.verbose) {
         System.out.println("End scope context: " + rem);
      }
   }

   /**
    * Call this to retrieve the current set of scope contexts that are available to your object.  For methods that may be called
    * from a different scope environment, you can then use the
    */
   public static CurrentScopeContext getCurrentScopeContext() {
      CurrentScopeContext envCtx = getEnvScopeContextState();
      if (envCtx != null)
         return envCtx;
      CurrentScopeContext ctx = new CurrentScopeContext();
      for (ScopeDefinition scope : ScopeDefinition.scopes) {
         if (scope == null)
            continue;
         ScopeContext scopeCtx = scope.getScopeContext(false);
         if (scopeCtx != null)
            ctx.scopeContexts.add(scopeCtx);
      }
      return ctx;
   }

   /** Returns the currently pushed CurrentScopeContext (or null if there is not one present) */
   public static CurrentScopeContext getEnvScopeContextState() {
      ArrayList<CurrentScopeContext> curStateList = (ArrayList<CurrentScopeContext>) PTypeUtil.getThreadLocal("scopeStateStack");
      if (curStateList != null && curStateList.size() > 0) {
         return curStateList.get(curStateList.size() - 1);
      }
      return null;
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      for (ScopeContext ctx:scopeContexts) {
         if (sb.length() > 0)
            sb.append(", ");
         sb.append(ctx);
      }
      return sb.toString();
   }
}
