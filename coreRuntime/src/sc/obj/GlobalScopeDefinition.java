/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/** 
 * The GlobalScopeDefinition implements the ScopeDefinition contract for objects that are shared 
 * by all users in the same class loader.  
 */
@TypeSettings(objectType=true)
@sc.js.JSSettings(jsModuleFile="js/scgen.js", prefixAlias="sc_")
public class GlobalScopeDefinition extends ScopeDefinition {

   public class GlobalScopeContext extends BaseScopeContext {
      @Override
      public ScopeDefinition getScopeDefinition() {
         return GlobalScopeDefinition.this;
      }

      @Override
      public String getId() {
         return "global";
      }
   }

   GlobalScopeContext globalContext = new GlobalScopeContext();

   static GlobalScopeDefinition globalScopeDef = new GlobalScopeDefinition();

   public static GlobalScopeDefinition getGlobalScopeDefinition() {
      return globalScopeDef;
   }

   // Global scope is the default - the one with no name
   {
      name = null;
      aliases = new ArrayList<String>(1);
      aliases.add("global");
      eventListenerCtx = true; // By default, collect all of the binding events here if there's no more specific context registered
   }
   public GlobalScopeDefinition() {
      super(0); // global scope is always id 0
   }

   public ScopeContext getScopeContext(boolean create) {
      ScopeContext tempCtx = super.getScopeContext(create);
      if (tempCtx != null)
         return tempCtx;
      return globalContext;
   }

   // Drag in the IObjectId class into the JS runtime.  Probably can remove this at some point as eventually someone will add a reference... or fix the bug where we look for JSSettings in src that's imported from a layer definition file.
   IObjectId getDummyObjectId() {
      return null;
   }


}
