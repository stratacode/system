/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import static sc.bind.Bind.info;
import static sc.bind.Bind.trace;
import sc.dyn.DynUtil;

public class ConditionalBinding extends AbstractMethodBinding {
   public String operator;
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
      // A special case that's useful for turning booleans into bitMasks and back again.
      if (boundParams.length == 2) {
         ArithmeticBinding arithBind;
         IBinding topConstBind;
         if (boundParams[0].isConstant() && boundParams[1] instanceof ArithmeticBinding) {
            topConstBind = boundParams[0];
            arithBind = (ArithmeticBinding) boundParams[1];
         }
         else if (boundParams[0] instanceof ArithmeticBinding && boundParams[1].isConstant()) {
            arithBind = (ArithmeticBinding) boundParams[0];
            topConstBind = boundParams[1];
         }
         else
            throw new UnsupportedOperationException("Reverse binding not supported on this conditional expression");

         if (value == null)
            return null;

         if (!(value instanceof Boolean))
            throw new UnsupportedOperationException("Reverse binding on conditional expression supports only boolean types");

         Object topConstVal = topConstBind.getPropertyValue(obj, false, false);
         if (arithBind.operator.equals("&") && arithBind.boundParams.length == 2 && topConstVal instanceof Number) {
            Number constVal = (Number) topConstVal;
            IBinding innerConst;
            VariableBinding innerVar;
            boolean p0Const = arithBind.boundParams[0].isConstant();
            boolean p1Const = arithBind.boundParams[1].isConstant();
            if (p0Const && !p1Const && arithBind.boundParams[1] instanceof VariableBinding) {
               innerConst = arithBind.boundParams[0];
               innerVar = (VariableBinding) arithBind.boundParams[1];
            }
            else if (p1Const && !p0Const && arithBind.boundParams[0] instanceof VariableBinding) {
               innerVar = (VariableBinding) arithBind.boundParams[0];
               innerConst = arithBind.boundParams[1];
            }
            else
               throw new UnsupportedOperationException("Unsupported conditional reverse binding pattern");
            Object innerConstVal = innerConst.getPropertyValue(obj, false, false);
            if (!(innerConstVal instanceof Number)) {
               throw new UnsupportedOperationException("Unsupported conditional reverse binding data type");
            }
            long innerNum = ((Number) innerConstVal).longValue();
            if ((operator.equals("!=") && constVal.longValue() == 0) || (operator.equals("==") && constVal.longValue() == innerNum)) {
               Boolean bval = (Boolean) value;
               Number curVal = (Number) innerVar.getBoundValue(false);
               if (curVal == null)
                  return null;
               long curBits = curVal.longValue();

               long newBits;
               if (bval)
                  newBits = curBits | innerNum;
               else
                  newBits = curBits & ~(innerNum);
               if (newBits != curBits)
                  innerVar.applyReverseBinding(obj, newBits, this);
               return null;
            }
            else
               throw new UnsupportedOperationException("Unsupported conditional reverse binding values");
         }

      }
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
