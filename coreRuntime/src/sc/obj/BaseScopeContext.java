/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import sc.dyn.DynUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

@sc.js.JSSettings(jsModuleFile="js/scgen.js", prefixAlias="sc_")
public abstract class BaseScopeContext extends ScopeContext {
   LinkedHashMap<String, Object> valueTable;

   @Override
   public void setValue(String name, Object value) {
      if (valueTable == null)
         valueTable = new LinkedHashMap<String,Object>();
      valueTable.put(name, value);
   }

   @Override
   public Object getValue(String name) {
      if (valueTable == null)
         return null;
      return valueTable.get(name);
   }

   public Map getValues() {
      return valueTable;
   }

   public void scopeDestroyed() {
      if (valueTable != null) {
         ArrayList<String> keysToDestroy = new ArrayList<String>(valueTable.size());
         for (String key:valueTable.keySet()) {
            keysToDestroy.add(key);
         }
         for (int i = 0; i < keysToDestroy.size(); i++) {
            Object value = valueTable.get(keysToDestroy.get(i));
            if (value != null) {
               Object parentObj = DynUtil.getOuterObject(value);
               if (parentObj != null) {
                  DynUtil.removeChild(parentObj, value);
               }
               DynUtil.dispose(value);
            }
         }
         valueTable = null;
      }
      // Destroy the sync context after we dispose of any items directly in the attributes list so they are not disposed of twice.
      super.scopeDestroyed();
   }


   public String toString() {
      return getId();
   }
}
