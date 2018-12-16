/*
 * Copyright (c) 2018. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.type.TypeUtil;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Used for both GenerateContext and RestoreContext to hold features used by those operations
 * that work top-down.  Specifically, ChainResultSequence rely on certain model properties being 'null'
 * during the time we parse them, so that we know which path to take.  When we have a fully build model
 * object, in order to find the proper path down the parselet tree to rebuild the parse-node tree, we mask
 * these properties so they are not visible - return null - to the logic checking for the next slot value.
 * The mask is added before we restore/generate the child, and cleared afterwards.
 */
abstract public class BaseRebuildCtx {
   IdentityHashMap<Object,Map<Object,Object>> maskTable;

   public void maskProperty(Object obj, Object mapping, Object value) {
      if (maskTable == null)
         maskTable = new IdentityHashMap<Object,Map<Object,Object>>();

      Map<Object,Object> objMasks = maskTable.get(obj);

      if (objMasks == null)
      {
         // TODO: get a more efficient small map here - usually only a couple of values
         objMasks = new HashMap<Object,Object>(7);
         maskTable.put(obj,objMasks);
      }
      objMasks.put(mapping,value);
   }

   public void unmaskProperty(Object obj, Object mapping) {
      if (maskTable == null)
         throw new IllegalArgumentException("No mask to remove");

      Map<Object,Object> objMasks = maskTable.get(obj);

      if (objMasks == null)
         throw new IllegalArgumentException("No mask to remove");

      if (!objMasks.containsKey(mapping))
         throw new IllegalArgumentException("No mask to remove");

      objMasks.remove(mapping);

      if (objMasks.size() == 0)
      {
         maskTable.remove(obj);
         if (maskTable.size() == 0)
            maskTable = null;
      }
   }

   public Object getPropertyValue(Object parent, Object mapping) {
      if (maskTable != null)
      {
         Map<Object,Object> objMasks = maskTable.get(parent);

         if (objMasks != null)
            if (objMasks.containsKey(mapping))
               return objMasks.get(mapping);
      }

      return TypeUtil.getPropertyValue(parent, mapping);
   }

   public String toString() {
      if (maskTable == null)
         return "";
      else
         return maskTable.toString();
   }
}
