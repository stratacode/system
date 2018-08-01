/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import sc.dyn.DynUtil;

public class UnaryBinding extends AbstractMethodBinding {
   String operator;
   public UnaryBinding(String op, IBinding[] parameterBindings) {
      super(parameterBindings);
      operator = op;
   }
   public UnaryBinding(Object dstObject, IBinding dstBinding, String op, IBinding[] parameterBindings, BindingDirection dir, int flags, BindOptions opts) {
      super(dstObject, dstBinding, dstObject, parameterBindings, dir, flags, opts);
      operator = op;
   }

   protected boolean needsMethodObj() {
      return false;
   }

   protected Object invokeMethod(Object obj) {
      Object val = boundParams[0].getPropertyValue(obj);
      if (val == UNSET_VALUE_SENTINEL || val == null)
          return UNSET_VALUE_SENTINEL;
      if (val == PENDING_VALUE_SENTINEL)
         return val;

      // TODO: do we need to add some form of typing to the binding interface?  Unary expressions would
      // propagate their type.  The top-level guy would get the type from the dst property mapper.
      return DynUtil.evalUnaryExpression(operator, null, val);
   }

   /** Called when reverse bindings fire */
   protected Object invokeReverseMethod(Object obj, Object value) {
      if (value == UNSET_VALUE_SENTINEL || value == null)
         return UNSET_VALUE_SENTINEL;
      if (value == PENDING_VALUE_SENTINEL)
         return value;
      if (operator.equals("++") || operator.equals("--")) {
         IBinding param = boundParams[0];
         Object val = param.getPropertyValue(obj);
         int ival = (Integer) val;
         if (operator.charAt(0) == '+')
            ival++;
         else
            ival--;
         param.applyReverseBinding(obj, ival, this);
         return ival;
      }
      else
         return DynUtil.evalInverseUnaryExpression(operator, null, value);
   }

   @Override
   boolean propagateReverse(int ix) {
      return true;
   }

   public String toString(String operation, boolean displayValue) {
      StringBuilder sb = new StringBuilder();
      if (dstObj != dstProp && operation != null) {
         sb.append(operation);
         sb.append(" ");
      }
      sb.append(super.toString(operation, displayValue));
      sb.append(operator);
      if (displayValue && paramValues[0] != null) {
         if (dstObj != dstProp) {
            sb.append(boundParams[0]);
            sb.append(" = ");
         }
         sb.append(paramValues[0]);
      }
      else
         sb.append(boundParams[0]);
      if (dstObj != dstProp && valid && displayValue)
         sb.append(" = " + boundValue);
      return sb.toString();
   }
}
