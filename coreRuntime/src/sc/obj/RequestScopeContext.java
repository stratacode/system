package sc.obj;

import sc.obj.ScopeDefinition;
import sc.obj.ScopeContext;

import sc.dyn.DynUtil;

import java.util.HashMap;

public class RequestScopeContext extends BaseScopeContext {
   String id;
   public RequestScopeContext(String id) {
      this.id = id;
   }

   public ScopeDefinition getScopeDefinition() {
      return RequestScopeDefinition.getRequestScopeDefinition();
   }

   public String getId() {
      return id;
   }

   public boolean isCurrent() {
      return RequestScopeDefinition.getRequestScopeDefinition().getScopeContext(false) == this;
   }

   public void scopeDestroyed() {
      RequestScopeDefinition.removeCurrentRequestScopeContext();
      super.scopeDestroyed();
   }
}
