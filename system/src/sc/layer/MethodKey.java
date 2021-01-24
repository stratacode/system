/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.util.StringUtil;

import java.io.Serializable;

public class MethodKey implements Serializable {
   public String methodName;
   public String paramSig;
   public MethodKey(String name, String sig) {
      methodName = name;
      paramSig = sig;
   }

   public int hashCode() {
      return methodName.hashCode() + (paramSig == null ? 0 : paramSig.hashCode());
   }

   public boolean equals(Object other) {
      if (!(other instanceof MethodKey))
         return false;
      MethodKey otherKey = (MethodKey) other;
      return StringUtil.equalStrings(methodName, otherKey.methodName) && StringUtil.equalStrings(paramSig, otherKey.paramSig);
   }
}
