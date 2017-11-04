package sc.obj;

import sc.obj.ScopeDefinition;
import sc.obj.ScopeContext;
import sc.obj.GlobalScopeDefinition;
import sc.type.PTypeUtil;

@TypeSettings(objectType=true)
@sc.js.JSSettings(jsModuleFile="js/scgen.js", prefixAlias="sc_")
public class RequestScopeDefinition extends ScopeDefinition {
   {
      name = "request";
      addParentScope(GlobalScopeDefinition.getGlobalScopeDefinition());
   }

   static RequestScopeDefinition requestScopeDefinition;

   private RequestScopeDefinition() {
      super(5);
   }

   public synchronized static RequestScopeDefinition getRequestScopeDefinition() {
      if (requestScopeDefinition == null) {
         requestScopeDefinition = new RequestScopeDefinition();
      }
      return requestScopeDefinition;
   }

   public ScopeContext getScopeContext(boolean create) {
      RequestScopeContext ctx = (RequestScopeContext) PTypeUtil.getThreadLocal("requestScope");
      if (ctx == null && create) {
         ctx = new RequestScopeContext(Thread.currentThread().getName());
         PTypeUtil.setThreadLocal("requestScope", ctx);
         ctx.init();
      }
      return ctx;
   }

   static void removeCurrentRequestScopeContext() {
      PTypeUtil.setThreadLocal("requestScope", null);
   }

   public ScopeDefinition getScopeDefinition() {
      return RequestScopeDefinition.getRequestScopeDefinition();
   }

   public static Object getValue(String name) {
      RequestScopeDefinition def = getRequestScopeDefinition();
      ScopeContext ctx = def.getScopeContext(false);
      if (ctx != null)
         return ctx.getValue(name);
      return null;
   }

   public static void setValue(String name, Object value) {
      RequestScopeDefinition def = getRequestScopeDefinition();
      ScopeContext ctx = def.getScopeContext(true);
      ctx.setValue(name, value);
   }

   public boolean isTemporary() {
      return true;
   }
}
