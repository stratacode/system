/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.type;

import sc.dyn.IDynObject;

public class CompMethodMapper implements IMethodMapper {
   public DynType type;
   public String methodName;
   public boolean isStatic;

   int methodIndex;
   String paramSig;

   public MethodBindSettings reverseMethSettings;

   public CompMethodMapper(DynType type, int methodIndex, String methodName, String paramSig, boolean isStatic) {
      this.type = type;
      this.methodIndex = methodIndex;
      this.methodName = methodName;
      this.paramSig = paramSig;
      this.isStatic = isStatic;
   }

   public CompMethodMapper(DynType type, int methodIndex, String methodName, String paramSig, boolean isStatic, MethodBindSettings reverseSettings) {
      this(type, methodIndex, methodName, paramSig, isStatic);
      reverseMethSettings = reverseSettings;
   }

   public Object invoke(Object thisObj, Object... params) {
      IDynObject dynObj = (IDynObject) thisObj;
      if (isStatic)
         return type.invokeStatic(methodIndex, params);
      else
         return dynObj.invoke(methodIndex, params);
   }
}
