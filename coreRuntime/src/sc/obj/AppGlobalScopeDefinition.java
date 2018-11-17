/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import sc.type.PTypeUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;

/** 
 * The AppGlobalScopeDefinition implements the ScopeDefinition contract for objects that are shared 
 * by all users in the same application 'appId'.  For web applications the
 * appId is the base URL of the page, so each page shares global scope but does not share appGlobal scope.
 */
@TypeSettings(objectType=true)
@sc.js.JSSettings(jsModuleFile="js/scgen.js", prefixAlias="sc_")
public class AppGlobalScopeDefinition extends ScopeDefinition {
   public class AppGlobalScopeContext extends BaseScopeContext {
      String appId;

      public AppGlobalScopeContext(String appId) {
         this.appId = appId;
      }

      public ScopeDefinition getScopeDefinition() {
         return AppGlobalScopeDefinition.this;
      }

      public String getId() {
         return "appGlobal:" + appId;
      }
   }

   static AppGlobalScopeDefinition appGlobalScopeDef = new AppGlobalScopeDefinition();

   static HashMap<String,AppGlobalScopeContext> appGlobalTable = new HashMap<String,AppGlobalScopeContext>();

   public static ScopeContext getAppGlobalScope() {
      return appGlobalScopeDef.getScopeContext(true);
   }

   public static AppGlobalScopeDefinition getAppGlobalScopeDefinition() {
      return appGlobalScopeDef;
   }

   {
      name = "appGlobal";
      addParentScope(GlobalScopeDefinition.getGlobalScopeDefinition());
   }
   public AppGlobalScopeDefinition() {
      super(1); // app global scope is always id 1
   }

   public ScopeContext getScopeContext(boolean create) {
      ScopeContext tempCtx = super.getScopeContext(create);
      if (tempCtx != null)
         return tempCtx;
      String appId = PTypeUtil.getAppId();

      synchronized (appGlobalTable) {
         AppGlobalScopeContext ctx = appGlobalTable.get(appId);
         if (ctx == null) {
            ctx = new AppGlobalScopeContext(appId);
            appGlobalTable.put(appId, ctx);
            ctx.init();
         }
         return ctx;
      }
   }
}
