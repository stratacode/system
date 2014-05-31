/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.dyn;

import sc.bind.MethodBinding;
import sc.type.ReverseBindingImpl;
import sc.type.CompMethodMapper;

public class CompReverseMethodMapper implements IReverseMethodMapper {
   ReverseBindingImpl settings;

   public CompReverseMethodMapper(MethodBinding binding) {
      CompMethodMapper forwardMethod = (CompMethodMapper) binding.getMethod();
      if (forwardMethod.reverseMethSettings == null)
         throw new IllegalArgumentException("*** BindSettings not set for method: " + forwardMethod + " used in reverse binding " + binding);

      settings = new ReverseBindingImpl(binding, forwardMethod.reverseMethSettings);
      if (settings.methBindSettings.reverseSlot != -1 && settings.methBindSettings.modifyParam) {
         if (settings.methBindSettings.reverseSlot >= binding.getBoundParams().length) {
            System.err.println("*** reverseSlot value set to value greater than input params length with modifyParam=true " + settings.methBindSettings.reverseSlot);
         }
         else
            settings.reverseParam = binding.getBoundParams()[settings.methBindSettings.reverseSlot];
      }
   }

   public Object invokeReverseMethod(Object obj, Object value, Object... params) {
      CompMethodMapper forwardMethod = ((CompMethodMapper) settings.binding.getMethod());
      Object[] reverseParams = settings.getReverseParameters(value, params);
      Object reverseValue;
      int reverseMethodIndex = (Integer)settings.methBindSettings.reverseMethod;
      if (forwardMethod.isStatic) {
         reverseValue = forwardMethod.type.invokeStatic(reverseMethodIndex, reverseParams);
      }
      else {
         if (obj == null)
            reverseValue = null;
         else
            reverseValue = ((IDynObject) obj).invoke(reverseMethodIndex, reverseParams);
      }
      if (settings.methBindSettings.forwardSlot != -1)
         reverseValue = params[settings.methBindSettings.forwardSlot];

      settings.applyReverseBinding(reverseValue);
      return reverseValue;
   }

   public boolean propagateReverse(int slot) {
      return settings.methBindSettings.modifyParam && settings.methBindSettings.reverseSlot == slot;
   }
}
