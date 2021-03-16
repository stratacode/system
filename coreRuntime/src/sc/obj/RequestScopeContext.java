/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import sc.obj.ScopeDefinition;
import sc.obj.ScopeContext;

import sc.dyn.DynUtil;

import java.util.HashMap;

@sc.js.JSSettings(jsModuleFile="js/scgen.js", prefixAlias="sc_")
public class RequestScopeContext extends BaseScopeContext {
   String id;
   public RequestScopeContext(String id) {
      this.id = id;
   }

   public ScopeDefinition getScopeDefinition() {
      return RequestScopeDefinition.getRequestScopeDefinition();
   }

   public String getId() {
      return "request:" + DynUtil.getTraceObjId(id);
   }

   public boolean isCurrent() {
      return RequestScopeDefinition.getRequestScopeDefinition().getScopeContext(false) == this;
   }

   public void scopeDestroyed(ScopeContext fromParent) {
      super.scopeDestroyed(fromParent);
      // Needs to be done after super.scopeDestroyed so that we can find the sync inst to remove
      RequestScopeDefinition.removeCurrentRequestScopeContext();
   }
}
