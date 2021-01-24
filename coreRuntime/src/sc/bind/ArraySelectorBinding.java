/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import sc.dyn.DynUtil;

public class ArraySelectorBinding extends AbstractListener implements IBinding {
   IBinding arrayBinding;
   IBinding dstBinding;
   BindingDirection direction;
   Object boundParent;
   Object boundValue;
   volatile boolean valid = false;

   public ArraySelectorBinding(IBinding arrayElementBinding) {
      arrayBinding = arrayElementBinding;
   }

   // Only called for top-level bindings and this is not used for top-level bindings
   public Object initializeBinding() {
      throw new UnsupportedOperationException();
   }

   public void setBindingParent(IBinding parent, BindingDirection dir) {
      dstBinding = parent;
      direction = dir;
      arrayBinding.setBindingParent(this, dir);
   }

   public boolean isConstant() {
      return false;
   }

   public Object getPropertyValue(Object parent, boolean getField, boolean pendingChild) {
      boundParent = parent;
      return getBoundValue(pendingChild);

   }
   public Object getBoundValue(boolean pendingChild) {
      if (valid)
         return boundValue;
      if (boundParent == null || boundParent == UNSET_VALUE_SENTINEL) {
         return UNSET_VALUE_SENTINEL;
      }
      Object dimObj = arrayBinding.getPropertyValue(null, false, pendingChild);
      if (dimObj == null || dimObj == UNSET_VALUE_SENTINEL)
         return UNSET_VALUE_SENTINEL;
      int dim = ((Number) dimObj).intValue();
      boundValue = DynUtil.getArrayElement(boundParent, dim);
      valid = true;
      return boundValue;
   }

   public void addBindingListener(Object eventObject, IListener listener, int event) {
   }

   public void removeBindingListener(Object eventObject, IListener listener, int event) {
   }

   public void invalidateBinding(Object object, boolean sendEvent, int event, boolean includeParams) {
      valid = false;
   }

   public boolean applyBinding(Object obj, Object value, IBinding src, boolean refresh, boolean pendingChild) {
      if (src == arrayBinding) {
         valid = false;
         if (direction.doForward()) {
            return dstBinding.applyBinding(null, getBoundValue(pendingChild), this, refresh, pendingChild);
         }
      }
      return true;
   }

   public void applyReverseBinding(Object obj, Object value, Object src) {
      throw new UnsupportedOperationException("Reverse bindings against array selectors not implemented");
   }

   public void removeListener() {
   }

   public void parentBindingChanged() {
      valid = false;
   }

   public boolean valueInvalidated(Object srcObj, Object srcProp, Object eventDetail, boolean apply) {
      return true;
   }

   public boolean valueValidated(Object srcObj, Object srcProp, Object eventDetail, boolean apply) {
      return true;
   }

   public boolean isReversible() {
      return true;
   }
}
