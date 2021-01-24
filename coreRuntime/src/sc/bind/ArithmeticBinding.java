/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import sc.dyn.DynUtil;
import sc.type.InverseOp;

import java.util.ArrayList;

/** 
  * Implements the basic arithemtic operations via a data binding.  You have one operator for usually two parameters.
  * bindings are propagated up and down the chain for forward and reverse bindings.  For the most part this is a lot like
  * a method binding so the AbstractMethodBinding provides most of the functionality.  ArithmeticBindings are invertible if
  * there's only one non-constant parameter.
  */
public class ArithmeticBinding extends AbstractMethodBinding {
   public String operator;
   public ArithmeticBinding(String op, IBinding[] parameterBindings) {
      super(parameterBindings);
      operator = op;
   }

   public ArithmeticBinding(Object dstObject, IBinding dstBinding, String op, IBinding[] parameterBindings, BindingDirection dir, int flags, BindOptions opts) {
      super(dstObject, dstBinding, dstObject, parameterBindings, dir, flags, opts);

      operator = op;
   }

   protected boolean needsMethodObj() {
      return false;
   }

   protected Object invokeMethod(Object obj, boolean pendingChild) {
      Object lhsVal = boundParams[0].getPropertyValue(obj, false, pendingChild);
      paramValues[0] = lhsVal;
      boolean isString = lhsVal instanceof CharSequence;
      boolean hasUnsetParams = lhsVal == null || lhsVal == UNSET_VALUE_SENTINEL;
      for (int i = 1; i < boundParams.length; i++) {
         Object nextVal;
         // TODO: we really need to add some form of typing to the binding interface so we know up front if it's a string
         // arithmetic expression or not. Right now, we go through and skip unset values (either null or an 'a.b.c' where
         // b is null count as unset to make it more convenient to build up tag expressions which might concatenate a value
         // that's not available. We can discover the type of the expression in many cases by looking at the parent's property type.
         paramValues[i] = nextVal = boundParams[i].getPropertyValue(obj, false, pendingChild);
         if (nextVal == PENDING_VALUE_SENTINEL || lhsVal == PENDING_VALUE_SENTINEL) {
            return PENDING_VALUE_SENTINEL;
         }

         boolean nextIsUnset = nextVal == UNSET_VALUE_SENTINEL || nextVal == null;

         if (nextIsUnset) {
            hasUnsetParams = true;
            continue;
         }
         try {
            if (nextVal instanceof CharSequence) {
               isString = true;
            }
            if (lhsVal == UNSET_VALUE_SENTINEL || lhsVal == null)
               lhsVal = nextVal;
            else
               lhsVal = DynUtil.evalArithmeticExpression(operator, isString ? String.class : null, lhsVal, nextVal);
         }
         catch (ArithmeticException exc) {
            if (Bind.trace || ((this.flags & Bind.TRACE) != 0))
               System.out.println("Binding: " + this + " caught arithmetic error: " + exc);

            return UNSET_VALUE_SENTINEL;
         }
      }
      if (!isString && hasUnsetParams)
         return UNSET_VALUE_SENTINEL;
      return lhsVal;
   }


   /** Called when reverse bindings fire */
   protected Object invokeReverseMethod(Object obj, Object value) {
      InverseOp inverseOp = InverseOp.get(operator);
      Object lhsVal;
      boolean propagated = false;
      int startParam = 1;

      // First mark the new current value for this binding
      boundValue = value;

      if (!boundParams[0].isConstant()) {
         lhsVal = evalInverseExpr(inverseOp.inverseOpA, value,
                                  boundParams[1].getPropertyValue(obj, false, false), inverseOp.swapArgsA);
         propagated = true;
         startParam = 2;
         boundParams[0].applyReverseBinding(obj, lhsVal, this);
      }
      else
         lhsVal = boundParams[0].getPropertyValue(obj, false, false);
      for (int i = startParam; i < boundParams.length; i++) {
         if (!propagated && !boundParams[i].isConstant()) {
            lhsVal = evalInverseExpr(inverseOp.inverseOpB, lhsVal, value, inverseOp.swapArgsB);
            boundParams[i].applyReverseBinding(obj, lhsVal, this);
         }
         else {
            lhsVal = DynUtil.evalArithmeticExpression(operator, null, lhsVal, boundParams[i].getPropertyValue(obj, false, false));
         }
      }
      return lhsVal;
   }

   /** Propagate the value to the first non-constant value in the expression */
   private int getReverseSlot() {
      for (int i = 0; i < boundParams.length; i++)
         if (!boundParams[i].isConstant())
            return i;
      return -1;
   }

   @Override
   boolean propagateReverse(int ix) {
      return ix == getReverseSlot();
   }

   private static Object evalInverseExpr(String op, Object lhsVal, Object rhsVal, boolean swapArgs) {
      if (swapArgs) {
         Object t = lhsVal;
         lhsVal = rhsVal;
         rhsVal = t;
      }
      return DynUtil.evalArithmeticExpression(op, null, lhsVal, rhsVal);
   }

   public String toString(String operation, boolean displayValue) {
      StringBuilder sb = new StringBuilder();
      if (dstObj != dstProp && operation != null) {
         sb.append(operation);
         sb.append(" ");
      }
      sb.append(super.toString(operation, displayValue));
      if (dstObj != dstProp && displayValue) {
         sb.append((Object) toBindingString(false));
         sb.append(" = ");
      }
      sb.append((Object) toBindingString(displayValue));

      if (displayValue && dstObj != dstProp) {
         sb.append(" = ");
         sb.append(DynUtil.toString(boundValue));
      }

      return sb.toString();
   }

   public StringBuilder toBindingStringNested(boolean displayValue) {
      StringBuilder sb = new StringBuilder();
      sb.append("(");
      sb.append(toBindingString(displayValue));
      sb.append(")");
      return sb;
   }

   public StringBuilder toBindingString(boolean displayValue) {
      StringBuilder sb = new StringBuilder();
      if (boundParams != null) {
         for (int i = 0; i < boundParams.length; i++) {
            if (i != 0) {
               sb.append(" ");
               sb.append(operator);
               sb.append(" ");
            }
            if (displayValue) {
               // Expand Nested arithmetic bindings with parens
               if (boundParams != null && boundParams[i] instanceof ArithmeticBinding) {
                  ArithmeticBinding nestedArith = (ArithmeticBinding) boundParams[i];
                  if (!nestedArith.operator.equals(operator))
                     sb.append(nestedArith.toBindingStringNested(true));
                  else
                     sb.append(nestedArith.toBindingString(true));
               }
               else
                  sb.append(paramValues == null ? "null" : DynUtil.getInstanceName(paramValues[i]));
            }
            else
               sb.append(boundParams == null ? "null" : DynUtil.toString(boundParams[i]));
         }
      }
      return sb;
   }
}
