/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import sc.dyn.DynUtil;

public class NewBinding extends AbstractMethodBinding {
   Object newClass;
   String paramSig;

   public NewBinding(Object theClass, String paramSig, IBinding[] parameterBindings) {
      super(parameterBindings);
      newClass = theClass;
      this.paramSig = paramSig;
   }
   public NewBinding(Object dstObject, IBinding dstBinding, Object theClass, String paramSig, IBinding[] parameterBindings, BindingDirection dir, int flags, BindOptions opts) {
      super(dstObject, dstBinding, dstObject, parameterBindings, dir, flags, opts);
      newClass = theClass;
      this.paramSig = paramSig;
   }

   protected Object invokeMethod(Object obj) {
      boolean valid = true;
      for (int i = 0; i < paramValues.length; i++) {
         paramValues[i] = boundParams[i].getPropertyValue(obj);
         if (paramValues[i] == PENDING_VALUE_SENTINEL)
            return PENDING_VALUE_SENTINEL;
         if (paramValues[i] == UNSET_VALUE_SENTINEL)
            valid = false;
      }

      if (valid)
         return DynUtil.createInstance(newClass, paramSig, paramValues);
      else
         return UNSET_VALUE_SENTINEL;
   }

   protected boolean needsMethodObj() {
      return false;
   }

   /** Called when reverse bindings fire */
   protected Object invokeReverseMethod(Object obj, Object value) {
      if (!direction.doForward())
         return invokeMethod(obj);
      System.err.println("*** reverse binding not supported on new expressions");
      return null;
   }

   @Override
   boolean propagateReverse(int ix) {
      return false;
   }

   public String toString(String operation, boolean displayValue) {
      StringBuilder sb = new StringBuilder();
      if (dstObj != dstProp && operation != null) {
         sb.append(operation);
         sb.append(" ");
      }
      sb.append(super.toString(operation, displayValue));
      sb.append("new ");
      sb.append(newClass);
      sb.append("(");
      if (displayValue && dstObj != dstProp) {
         sb.append(Bind.arrayToString(boundParams));
         sb.append(") = ");
      }
      if (displayValue)
         sb.append(DynUtil.arrayToInstanceName(paramValues));
      else
         sb.append(Bind.arrayToString(boundParams));
      sb.append(")");
      if (dstObj != dstProp && valid && displayValue)
         sb.append(" = " + boundValue);
      return sb.toString();
   }
}
