/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.type;

import sc.bind.IBinding;
import sc.bind.MethodBinding;

public class ReverseBindingImpl {
   public MethodBinding binding;
   public IBinding reverseParam;

   public MethodBindSettings methBindSettings;

   public ReverseBindingImpl(MethodBinding binding, MethodBindSettings settings) {
      this.binding = binding;
      this.methBindSettings = settings;
   }

   public void applyReverseBinding(Object reverseVal) {
      // If the slot we are using as the reverse param is constant, skip the propagation.
      // the reverse binding often happens as a side-effect of the reverse method so propagation
      // through a parameter is not necessary.
      //
      // Not sure why this was happening - with the reverse method, we do not actually set the value of the
      // parameter with the reverse value right?
      if (reverseParam != null && !reverseParam.isConstant() && methBindSettings.modifyParam)
         reverseParam.applyReverseBinding(null, reverseVal, binding);
   }

   public Object[] getReverseParameters(Object value, Object[] params) {
      Object[] res;
      if (methBindSettings.oneParamReverse) {
         res = new Object[1];
         res[0] = value;
      }
      else {
         int vlen = params.length + 1;
         res = new Object[vlen];
         int j = 0;
         for (int i = 0; i < vlen; i++) {
            if (i == methBindSettings.reverseSlot)
               res[i] = value;
            else {
               if (j >= params.length)
                  res[i] = null;
               else
                  res[i] = params[j++];
            }
         }
      }
      return res;
   }
}
