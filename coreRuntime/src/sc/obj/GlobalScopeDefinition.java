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

   public class GlobalScopeContext extends ScopeContext {
      LinkedHashMap<String, Object> globalTable;

      @Override
      public void setValue(String name, Object value) {
         if (globalTable == null)
            globalTable = new LinkedHashMap<String,Object>();
         globalTable.put(name, value);
      }

      @Override
      public Object getValue(String name) {
         if (globalTable == null)
            return null;
         return globalTable.get(name);
      }

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
   }
   public GlobalScopeDefinition() {
      super(0); // global scope is always id 0
   }

   @Override
   public ScopeContext getScopeContext() {
      return globalContext;
   }

   // Drag in the IObjectId class into the JS runtime.  Probably can remove this at some point as eventually someone will add a reference... or fix the bug where we look for JSSettings in src that's imported from a layer definition file.
   IObjectId getDummyObjectId() {
      return null;
   }


}
