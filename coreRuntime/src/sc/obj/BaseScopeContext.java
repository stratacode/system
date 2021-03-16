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
   LinkedHashMap<String, Object> refTable;

   public void setValue(String name, Object value) {
      if (valueTable == null)
         valueTable = new LinkedHashMap<String,Object>();
      valueTable.put(name, value);
   }

   public void setValueByRef(String name, Object value) {
      if (refTable == null)
         refTable = new LinkedHashMap<String,Object>();
      refTable.put(name, value);
   }

   public Object getValue(String name) {
      if (valueTable == null)
         return null;
      return valueTable.get(name);
   }

   public Map getValues() {
      return valueTable;
   }

   public void scopeDestroyed(ScopeContext fromParent) {
      // Do this before we removing the values since SyncContext will be one of the values and is a listener. It looks
      // for itself it in the scope context.
      if (destroyListener != null) {
         destroyListener.scopeDestroyed(this);
         destroyListener = null;
      }
      boolean needsClear = false;
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
         needsClear = true;
      }
      refTable = null;
      // Destroy the sync context after we dispose of any items directly in the attributes list so they are not disposed of twice.
      super.scopeDestroyed(fromParent);
      if (needsClear)
         valueTable = null;
   }


   public String toString() {
      return getId();
   }
}
