/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.type;

import sc.bind.MethodBinding;
import sc.dyn.DynUtil;
import sc.dyn.IReverseMethodMapper;
import sc.dyn.RDynUtil;

public class RuntimeReverseMethodMapper implements IReverseMethodMapper {
   ReverseBindingImpl settings;

   public RuntimeReverseMethodMapper(MethodBinding binding) {
      settings = RDynUtil.initBindSettings(binding);
   }

   public Object invokeReverseMethod(Object obj, Object value, Object... paramValues) {
      // Because this is a reverse binding, any auto-upconversion done by Java in an expression like a float to
      // double conversion needs to be undone here.
      value = DynUtil.evalCast(DynUtil.getParameterTypes(settings.methBindSettings.reverseMethod)[0], value);  // TODO: should "0" here be the reverseSlot?
      Object reverseVal;
      if (settings.methBindSettings.oneParamReverse)
         reverseVal = DynUtil.invokeMethod(obj, settings.methBindSettings.reverseMethod, value);
      else {
         int vlen = paramValues.length + 1;
         Object[] values = new Object[vlen];
         int j = 0;
         for (int i = 0; i < vlen; i++) {
            if (i == settings.methBindSettings.reverseSlot)
               values[i] = value;
            else {
               if (j >= paramValues.length)
                  values[i] = null;
               else
                  values[i] = paramValues[j++];
            }
         }
         if (obj != null || DynUtil.hasModifier(settings.methBindSettings.reverseMethod, "static")) {
            if (settings.methBindSettings.forwardSlot != -1) {
               DynUtil.invokeMethod(obj, settings.methBindSettings.reverseMethod, values);
               reverseVal = paramValues[settings.methBindSettings.forwardSlot];
            }
            else
               reverseVal = DynUtil.invokeMethod(obj, settings.methBindSettings.reverseMethod, values);
         }
         else
            reverseVal = null;
      }

      settings.applyReverseBinding(reverseVal);
      return reverseVal;
   }

   public boolean propagateReverse(int slot) {
      return settings.methBindSettings.modifyParam && settings.methBindSettings.reverseSlot == slot;
   }
}
