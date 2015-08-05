/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.type;

import sc.dyn.DynUtil;

public class MethodBindSettings {
   public Object reverseMethod;
   public int reverseSlot;
   public int forwardSlot;
   public boolean modifyParam = false;
   public boolean oneParamReverse = false;
   public boolean reverseMethodStatic = false;

   public MethodBindSettings() {
      forwardSlot = -1;
   }

   public MethodBindSettings(Object reverseMethod, int reverseSlot, int forwardSlot, boolean modifyParam, boolean oneParamReverse, boolean reverseMethodStatic) {
      this.reverseMethod = reverseMethod;
      this.reverseSlot = reverseSlot;
      this.forwardSlot = forwardSlot;
      this.modifyParam = modifyParam;
      this.oneParamReverse = oneParamReverse;
      this.reverseMethodStatic = reverseMethodStatic;
   }


   public String getReverseMethodName() {
      return DynUtil.getMethodName(reverseMethod);
   }
}
