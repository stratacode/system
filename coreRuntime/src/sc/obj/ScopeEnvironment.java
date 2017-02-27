/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import sc.type.PTypeUtil;

/**
 * Stores values kept in thread-local storage which are used to resolve a ScopeContext
 * object - i.e. all of the data values with a particular lifecycle.
 */
public class ScopeEnvironment {
   String appId;
   String userId; // placeholder for state shared per-user
   String tenantId; // placeholder for 'multi-tenant' support - either appGlobal uses appId + tenantId or we could create one or more new scopes that include this id in the hash-key lookup

   public ScopeEnvironment() {
   }

   public static ScopeEnvironment getEnv() {
      return (ScopeEnvironment) PTypeUtil.getThreadLocal(("scopeEnvironment"));
   }

   public static void setEnv(ScopeEnvironment env) {
      PTypeUtil.setThreadLocal("scopeEnvironment", env);
   }

   public static void setAppId(String appId) {
      ScopeEnvironment env = getEnv();
      if (env == null) {
         env = new ScopeEnvironment();
         setEnv(env);
      }
      env.appId = appId;
   }

   public static String getAppId() {
      ScopeEnvironment env = getEnv();
      if (env == null) {
         return null;
      }
      return env.appId;
   }
}
