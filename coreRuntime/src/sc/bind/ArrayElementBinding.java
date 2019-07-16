/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import sc.dyn.DynUtil;
import sc.type.IBeanMapper;

public class ArrayElementBinding extends VariableBinding {
   IBinding[] arrayBindings;
   int[] lastDim;
   Object boundValue;
   public ArrayElementBinding(Object srcObj, Object[] parameterBindings, IBinding[] arrayElementBindings) {
      super(srcObj, parameterBindings);
      arrayBindings = arrayElementBindings;
      lastDim = new int[arrayBindings.length];
   }
   public ArrayElementBinding(Object dstObject, IBinding dstBinding, Object srcObj, Object[] parameterBindings,
                              IBinding[] arrayElementBindings, BindingDirection dir, int flags, BindOptions opts) {
      super(dstObject, dstBinding, srcObj, parameterBindings, dir, flags, opts);
      arrayBindings = arrayElementBindings;
      lastDim = new int[arrayBindings.length];
   }

   /**
    * Call setBindingParent on the arrayBindings- they'll take care of listening and invalidating us when they
    * change automatically then.
    */
   protected void initBinding() {
      super.initBinding();
      for (IBinding arrayBinding:arrayBindings)
         arrayBinding.setBindingParent(this, direction);
      if (valid)
         revalidateArrayElement();
   }

   private Object getArrayValue() {
      return super.getBoundValue();
   }

   protected Object getBoundValue() {
      if (valid || !activated)
         return boundValue;
      return revalidateArrayElement();
   }

   private Object recomputeArrayElement() {
      Object base = getArrayValue();
      if (base == PENDING_VALUE_SENTINEL)
         return PENDING_VALUE_SENTINEL;
      if (base == UNSET_VALUE_SENTINEL)
         return UNSET_VALUE_SENTINEL;
      if (validateDims()) {
         for (int i = 0; i < arrayBindings.length; i++) {
            if (isValidObject(base))
               base = DynUtil.getArrayElement(base, lastDim[i]);
         }
         return base;
      }
      else
         return UNSET_VALUE_SENTINEL;
   }

   private Object revalidateArrayElement() {
      Object base = getArrayValue();
      if (validateDims()) {
         for (int i = 0; i < arrayBindings.length; i++) {
            if (isValidObject(base))
               base = DynUtil.getArrayElement(base, lastDim[i]);
         }
         boundValue = base;
         valid = true;
      }
      return boundValue;
   }

   protected void reactivate(Object obj) {
      super.reactivate(obj);
      if (valid)
         revalidateArrayElement();
   }

   private boolean validateDims() {
      for (int i = 0; i < arrayBindings.length; i++) {
         IBinding arrayBinding = arrayBindings[i];
         Object value = arrayBinding.getPropertyValue(null, false);
         if (!isValidObject(value))
            return false;

         if (!(value instanceof Number))
            throw new IllegalArgumentException("Array dimension: " + i + " in: " + this + " has invalid value: " + value);
         lastDim[i] = ((Number) value).intValue();
      }
      return true;
   }

   public boolean applyBinding(Object obj, Object value, IBinding src, boolean refresh, boolean pendingChild) {
      // If one of our dimensions changed, we just need to re-execute the binding
      boolean changed = false;
      for (IBinding arrayBinding:arrayBindings) {
         if (arrayBinding == src) {
            valid = false;
            changed = applyBinding(false) || changed;
            return changed;
         }
      }
      // If one of our bound properties changes, make sure to update the new value
      if (!valid || !DynUtil.equalObjects(boundValue, value)) {
         boundValue = value;
         changed = applyBinding(false) || changed;
      }
      return changed;
   }

   public void removeListener() {
      super.removeListener();

      for (int i = 0; i < arrayBindings.length; i++) {
         IBinding arrayBinding = arrayBindings[i];
         arrayBinding.removeListener();
      }
   }

   public String toString(String operation, boolean displayValue) {
      StringBuilder sb = new StringBuilder();
      sb.append(super.toString(operation, false));
      if (dstObj != dstProp && displayValue) {
         for (IBinding arrayBinding:arrayBindings) {
            sb.append("[");
            sb.append(arrayBinding);
            sb.append("]");
         }
         sb.append(" = ");
      }
      for (int i = 0; i < lastDim.length; i++) {
         sb.append("[");
         sb.append(lastDim[i]);
         sb.append("]");
      }
      if (valid && displayValue && dstProp != dstObj) {
         sb.append(" = ");
         sb.append(DynUtil.getInstanceName(boundValue));
      }
      return sb.toString();
   }

   // The last value is an array - we want to skip the array
   protected boolean equalValues(int index, Object newValue) {
      if (index != boundProps.length-1)
         return super.equalValues(index, newValue);
      return false;
   }

   // Just like equalValues but compares for == not equals so we detect changes when an IChangeable
   // instance changes but the equals does not change.
   protected boolean sameValues(int index, Object newValue) {
      if (index != boundProps.length-1)
         return super.sameValues(index, newValue);
      return false;
   }


   /** For the array element changed case, only trigger a firing if the array element matches.  Optimizing 1D only now */
   public boolean arrayElementInvalidated(Object srcObject, Object srcProp, Object dims, boolean apply) {
      int last = boundProps.length - 1;
      // Only optimizing the case where it is the last property of a 1D array that has changed and we have valid dims
      if (!(dims instanceof Integer) || lastDim == null || lastDim.length != 1 ||
              srcProp != boundProps[last] || dstObj == srcObject) {
         return valueInvalidated(srcObject, srcProp, dims, apply);
      }
      else if (direction.doForward()) {
         Integer index = (Integer) dims;
         boolean changed = false;
         if (index == lastDim[0]) {
            Object newElement = recomputeArrayElement();
            if (!valid || !DynUtil.equalObjects(boundValue, newElement)) {
               changed = true;
            }
            if (changed) {
               bindingInvalidated(apply);
            }
            return changed;
         }
      }
      return false;
   }

   protected boolean validateBinding() {
      boolean superChanged;
      if (cacheValue() && activated) {
         superChanged = super.validateBinding();
         boundValue = recomputeArrayElement();
         return superChanged;
      }
      return true;
   }

   public boolean arrayElementValidated(Object srcObject, Object srcProp, Object dims, boolean apply) {
      int last = boundProps.length - 1;
      // Only optimizing the case where it is the last property of a 1D array that has changed and we have valid dims
      if (!(dims instanceof Integer) || lastDim == null || lastDim.length != 1 ||
              srcProp != boundProps[last] || dstObj == srcObject) {
         return valueValidated(srcObject, srcProp, dims, apply);
      }
      else if (direction.doForward()) {
         Integer index = (Integer) dims;
         boolean changed = false;
         if (index == lastDim[0]) {
            Object newElement = recomputeArrayElement();
            if (!valid || !DynUtil.equalObjects(boundValue, newElement)) {
               changed = true;
               if (apply) {
                  boundValue = newElement;
                  valid = true;
               }
            }
            if (changed) {
               if (apply)
                  applyBinding(false);
            }
         }
      }
      return false;
   }

   protected boolean applyReverseBinding() {
      Object bObj = getBoundParent();
      Object bProp = getBoundProperty();

      if (!validateDims()) {
         throw new NullPointerException("Reverse array binding with null dimensions: " + this);
      }

      valid = true;
      Object newValue = PBindUtil.getPropertyValue(dstObj, dstProp);


      int last = lastDim.length-1;

      for (int i = 0; i < last; i++) {
         if (isValidObject(bObj))
            bObj = DynUtil.getArrayElement(bObj, lastDim[i]);
      }

      if (bProp instanceof IBeanMapper) {
         // Forcing null so that if a path component of a binding is not set, it just does not fire
         Object curVal = !isValidObject(bObj) ? UNSET_VALUE_SENTINEL : DynUtil.getIndexedProperty(bObj, (IBeanMapper) bProp, lastDim[last]);
         if (!DynUtil.equalObjects(curVal, newValue)) {
            boundValue = newValue; // First update the value to prevent the forward binding from firing
            if (isValidObject(bObj) && newValue != UNSET_VALUE_SENTINEL && newValue != PENDING_VALUE_SENTINEL)
               PBindUtil.setIndexedProperty(bObj, bProp, lastDim[last], newValue);
            return true;
         }

         //if (trace && dstProp != dstObj)
         //   System.out.println(toString("=>"));
      }
      else
         System.err.println("*** Invalid reverse binding on array element expression: " + this);
      return false;
   }

   public void applyReverseBinding(Object obj, Object value, IBinding src) {
      valid = true;
      if (!DynUtil.equalObjects(boundValue, value)) {
         boundValue = value;
      }
      Object arrayValue = getArrayValue();
      Object bObj = getBoundParent();
      Object bProp = getBoundProperty();
      if (arrayValue != null) {
         int last = lastDim.length-1;
         for (int i = 0; i < last; i++) {
            if (isValidObject(bObj))
               bObj = DynUtil.getArrayElement(bObj, lastDim[i]);
         }
         if (isValidObject(bObj))
            PBindUtil.setIndexedProperty(bObj, bProp, lastDim[last], value);
      }
   }
}
