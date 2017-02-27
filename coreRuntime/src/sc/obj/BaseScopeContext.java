/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import java.util.LinkedHashMap;

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

}
