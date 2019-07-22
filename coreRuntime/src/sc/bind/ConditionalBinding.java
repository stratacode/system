/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import static sc.bind.Bind.info;
import static sc.bind.Bind.trace;
import sc.dyn.DynUtil;

public class ConditionalBinding extends AbstractMethodBinding {
   String operator;
   public ConditionalBinding(String op, IBinding[] parameterBindings) {
      super(parameterBindings);
      operator = op;
   }
   public ConditionalBinding(Object dstObject, IBinding dstBinding, String op, IBinding[] parameterBindings, BindingDirection dir, int flags, BindOptions opts) {
      super(dstObject, dstBinding, dstObject, parameterBindings, dir, flags, opts);
      operator = op;
   }

   protected boolean needsMethodObj() {
      return false;
   }

   protected Object invokeMethod(Object obj, boolean pendingChild) {
      Object lhsVal = boundParams[0].getPropertyValue(obj, false, pendingChild);
      Object res = lhsVal;
      paramValues[0] = lhsVal;
      if (lhsVal == PENDING_VALUE_SENTINEL)
         return PENDING_VALUE_SENTINEL;
      if (lhsVal == UNSET_VALUE_SENTINEL)
         return UNSET_VALUE_SENTINEL;

      for (int i = 1; i < boundParams.length; i++) {
         // TODO: do we need to add some form of typing to the binding interface?  Conditional expressions would
         res = DynUtil.evalPreConditionalExpression(operator, lhsVal);
         IBinding boundP = boundParams[i];
         if (res == null) {
            boundP.activate(true, obj, false);

            Object rhsVal = boundP.getPropertyValue(obj, false, pendingChild);
            paramValues[i] = rhsVal;
            if (rhsVal == PENDING_VALUE_SENTINEL)
               return PENDING_VALUE_SENTINEL;
            if (rhsVal == UNSET_VALUE_SENTINEL)
               return UNSET_VALUE_SENTINEL;
            try {
               // propagate their type.  The top-level guy would get the type from the dst property mapper.
               res = DynUtil.evalConditionalExpression(operator, lhsVal, rhsVal);
            }
            catch (RuntimeException exc) {
               if (info || trace || (flags & Bind.TRACE) != 0) {
                  System.err.println("Runtime exception from conditional binding: " + this + ": " + exc);
                  exc.printStackTrace();
               }
            }
         }
         else {
            boundP.activate(false, obj, false);
         }
      }
      return res;
   }

   /** Called when reverse bindings fire */
   protected Object invokeReverseMethod(Object obj, Object value) {
      throw new UnsupportedOperationException("Reverse bindings not implemented for conditional expressions");
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
      if (dstObj != dstProp && displayValue) {
         sb.append(toBindingString(false));
         sb.append(" = ");
      }
      sb.append(toBindingString(displayValue));
      if (activated && valid && displayValue) {
         sb.append(" = ");
         sb.append(DynUtil.getInstanceName(boundValue));
      }
      return sb.toString();
   }

   public StringBuilder toBindingString(boolean displayValue) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < boundParams.length; i++) {
         if (i != 0) {
            sb.append(" ");
            sb.append(operator);
            sb.append(" ");
         }
         if (displayValue)
            sb.append(paramValues == null ? "null" : DynUtil.getInstanceName(paramValues[i]));
         else
            sb.append(boundParams == null ? "null" : boundParams[i]);
      }
      return sb;
   }
}
