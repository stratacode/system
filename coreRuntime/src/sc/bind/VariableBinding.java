/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import sc.dyn.DynUtil;
import sc.sync.SyncManager;
import sc.type.IBeanMapper;
import sc.util.CoalescedHashSet;
import sc.util.ISet;
import sc.util.SingleElementSet;

import static sc.bind.Bind.trace;

/**
 * VariableBinding manages an instance of a binding expression like "a.b.c".  It has a direction attribute -
 * for which way to apply the binding: forward, reverse, or bi-directional.  It has a list of the properties
 * "a.b.c" in the binding.  It computes the current array of intermediate or last known values of the binding
 * and stores them in the bound values.  The dstObj is the object who "owns" the binding - the one which defined
 * it.  If this is the top-level binding in an expression, the dstObj is the object which defined the expression.
 * If not, the dstObj is the parent object in the binding expression.   In this case, the dstProp==dstObj.
 * The binding is applied to the destination property which we store both in terms of its property id
 * and the index of that property in the property table of the dstObj's type.
 * <p>
 * The bound parent is the evaluated parent of the leaf reference.  It is not the value of the binding, but
 * the object whose property is being bound to.  If we are evaluating the reverse binding, we are setting a
 * property on the bound parent.  The bound value is the current value of the binding.
 * <p>
 * If any node along the binding chain evaluated to null, the bound value is null.
 * <p>
 * As intermediate nodes change, we have to remove and re-add our listeners on the intermediate objects.
 * If the binding is a forward binding, we add listeners onto the boundProps and cache the binding's current
 * state in the boundValues array.  If the binding is reverse only, we do not cache the values - instead they
 * are evaluated each time the binding fires.
 */
public class VariableBinding extends DestinationListener {
   /** array of String or IBinding - each corresponding to an element in the path a.b */
   Object [] boundProps;
   /** the current value of the binding at each location in the path name */
   Object [] boundValues;

   Object srcObj;
   volatile boolean valid = false;
   boolean isAssignment = false;

   public boolean isValid() {
      return valid;
   }

   /** Use this form for chaining together this binding with another binding */
   public VariableBinding(Object srcObject, Object[] boundProperties) {
      boundProps = boundProperties;
      srcObj = srcObject;   // The object which holds boundProperties chain - may be == dstObj.
      if (trace || (this.flags & Bind.TRACE) != 0) {
         if (srcObj == null)
            System.err.println("Warning: VariableBinding - null src object " + this);
         if (boundProps == null)
            System.err.println("Warning: VariableBinding - null properties " + this);
         else {
            for (Object bp:boundProps)
               if (bp == null)
                  System.err.println("Warning: Variable binding - null property!");
         }
      }
   }

   /** Use this form for a top level binding */
   public VariableBinding(Object dstObject, IBinding dstProperty, Object srcObject, Object[] boundProperties,
                          BindingDirection bindingDirection, int flags, BindOptions opts) {
      this(srcObject, boundProperties);
      dstObj = dstObject;
      dstProp = dstProperty;
      direction = bindingDirection;
      initFlags(flags, opts);
   }

   /** Called by the parent when this is a hierarchical binding - i.e. one expression in another */
   public void setBindingParent(IBinding parent, BindingDirection dir) {
      dstProp = parent;
      dstObj = parent;
      direction = dir;
      initBinding();
      addListeners();
   }

   /** Called to initialize and retrieve the value of a top-level binding */
   public Object initializeBinding() {
      assert dstObj != dstProp;

      initBinding();
      addListeners();
      if (cacheValue()) {
         Object result = getBoundValue(false);
         //if (trace) {
         //   System.out.println("Init: " + this);
         //}
         if (!isValidObject(result))
            result = null;
         return result;
      }
      else
         return null;
   }

   protected void initBinding() {
      if (dstProp == null)
         System.err.println("*** Unable to resolve property for binding: " + this);
      Object bindingParent = null;
      if (boundProps.length == 0) {
         valid = true;
         return;
      }

      Object firstBoundProp = boundProps[0];
      if (firstBoundProp == null) {
         valid = true; // Valid with "null" as the value
         return;
      }

      Bind.setBindingParent(firstBoundProp, this, direction);

      if (cacheValue()) {
         boundValues = new Object[boundProps.length];
         boundValues[0] = PBindUtil.getPropertyValue(srcObj, firstBoundProp, false, false);
         bindingParent = boundValues[0];
      }
      // The top-level reverseOnly case has to cache the dstProp because we may add a listener if it is IChangeable.
      // we store it where we store the normal cached value but it comes from the other side of the binding.
      else if (reverseOnly())
         boundValues = new Object[boundProps.length];

      int last = boundProps.length - 1;
      for (int i = 1; i <= last; i++) {
         Object nextProp = boundProps[i];
         if (nextProp == null) {
            valid = true; // Some property did not resolve
            return;
         }
         Bind.setBindingParent(nextProp, this, direction);
         if (cacheValue()) {
            if (isValidObject(bindingParent)) {
               boundValues[i] = bindingParent = PBindUtil.getPropertyValue(bindingParent, nextProp, false, false);
            }
            else {
               boundValues[i] = bindingParent = bindingParent == PENDING_VALUE_SENTINEL ? PENDING_VALUE_SENTINEL : UNSET_VALUE_SENTINEL;
            }
         }
      }
      if (activated)
         valid = true;
   }

   protected boolean cacheValue() {
      // Do not cache assignment bindings because they can be compound expressions, e.g. bar =: foo.fum + 1
      // when foo changes we will not be listening for that change as things are today.  We could make that change
      // but would need to make "foo" bindable as a src property.  It seems more reasonable to just re-evaluate the
      // expression.
      return direction.doForward();
   }

   private boolean reverseOnly() {
      return !direction.doForward() && direction.doReverse() && dstObj != dstProp;
   }

   protected void reactivate(Object obj) {
      if (cacheValue()) {
         Object bindingParent = srcObj;

         int last = boundProps.length - 1;

         for (int i = 0; i <= last && isValidObject(obj); i++) {
            Object oldValue = boundValues[i];
            boundValues[i] = getBoundProperty(bindingParent, i);
            // Need to add/remove the listeners if the instance changed
            if (!isAssignment && (!DynUtil.equalObjects(oldValue, boundValues[i]) || oldValue != boundValues[i])) {
               if (isValidObject(oldValue)) {
                  if (i < last) {
                     PBindUtil.removeBindingListener(oldValue, boundProps[i+1], this, VALUE_CHANGED_MASK);
                  }
                  if (oldValue instanceof IChangeable) {
                     Bind.removeListener(oldValue, null, this, VALUE_CHANGED_MASK);
                  }
               }
               Object newValue = boundValues[i];
               if (isValidObject(newValue)) {
                  if (i < last) {
                     PBindUtil.addBindingListener(newValue, boundProps[i+1], this, VALUE_CHANGED_MASK);
                  }
                  if (newValue instanceof IChangeable) {
                     Bind.addListener(newValue, null, this, VALUE_CHANGED_MASK);
                  }
               }
            }
            bindingParent = boundValues[i];
         }
      }
      /*
      if (reverseOnly()) {
         int last = boundValues.length-1;
         Object oldValue = boundValues[last];
         Object newValue = dstProp instanceof IBinding ? ((IBinding) dstProp).getPropertyValue(dstObj) : PBindUtil.getPropertyValue(dstObj, dstProp);
         if (!isAssignment && (!DynUtil.equalObjects(oldValue, newValue) || newValue != oldValue)) {
            if (oldValue instanceof IChangeable) {
               Bind.removeListener(oldValue, null, this, VALUE_CHANGED_MASK);
            }
            boundValues[last] = newValue;
            if (newValue instanceof IChangeable) {
               Bind.addListener(newValue, null, this, VALUE_CHANGED_MASK);
            }
         }
      }
      */
      valid = true;
   }

   /** Number of properties in the binding - e.g. a.b.c = 3 */
   public int getNumInChain() {
      return boundProps.length;
   }

   /** Returns the String property name, IBinding, or IBeanMapper */
   public Object getChainElement(int ix) {
      return boundProps[ix];
   }

   /** Returns the last property in the chain */
   protected Object getBoundProperty() {
      return boundProps[boundProps.length-1];
   }

   /** Returns the parent of the last value */
   protected Object getBoundParent() {
      if (boundProps.length == 1)
          return srcObj;
      else if (cacheValue())
         return boundValues[boundValues.length-2];
      else  // TODO: is this even reached anymore?
         return evalBindingSlot(boundProps.length-2);
   }

   protected Object getBoundValue(boolean pendingChild) {
      // No properties means we just return the srcObj itself
      if (boundProps.length == 0)
         return srcObj;

      if (cacheValue() || (pendingChild && isDefinedObject(boundValues[boundValues.length-1])))
         return boundValues[boundValues.length-1];
      else // reverse bindings are not cached - no listeners so we eval them each time
         return evalBinding();
   }

   private void addListeners() {
      // We always add the forward listeners so that we do not have to re-evaluate everything on demand
      int len = boundProps.length;
      if (srcObj != null && !isAssignment) {
         if ((cacheValue() || reverseOnly())) {
            if (cacheValue()) {
               if (len > 0)
                  PBindUtil.addBindingListener(srcObj, boundProps[0], this, VALUE_CHANGED_MASK);
               if (srcObj instanceof IChangeable) {
                  Bind.addListener(srcObj, null, this, VALUE_CHANGED_MASK);
               }
            }
            for (int i = 0; i < len; i++) {
               Object bv = boundValues[i];
               if (!isValidObject(bv))
                   break;

               // List to the next property on this value, skipping the last one.  Need to do this any time we are caching values
               // so that we keep it up to date.
               if (i < len - 1 && cacheValue())
                  PBindUtil.addBindingListener(bv, boundProps[i+1], this, VALUE_CHANGED_MASK);

               // If the value wants to be notified of individual events, always listen on it
               if (bv instanceof IChangeable) {
                  Bind.addListener(bv, null, this, VALUE_CHANGED_MASK);
               }
            }
         }
      }
      // For reverse only bindings, we do not cache the value up front but if it is an IChangeable, still must
      // add our listener on that.   Also must track the changes to the dst property via valueChanged
      if (direction.doReverse() && null != dstProp) {
         if (!isAssignment)
            Bind.addListener(dstObj, dstProp, this, VALUE_CHANGED_MASK);
         // Cache the reverse value and add a listener if it is IChangeable
         if (reverseOnly()) {
            Object newValue = PBindUtil.getPropertyValue(dstObj, dstProp, false, false);
            boundValues[len-1] = newValue;
         }
      }
   }

   public void removeListener() {
      if (cacheValue() && !isAssignment) {
         if (cacheValue()) {
            if (srcObj != null && boundProps.length > 0)
               PBindUtil.removeBindingListener(srcObj, boundProps[0], this, VALUE_CHANGED_MASK);
            if (srcObj instanceof IChangeable) {
               Bind.removeListener(srcObj, null, this, VALUE_CHANGED_MASK);
            }
         }
         if (boundValues != null) {
            for (int i = 0; i < boundValues.length; i++) {
               Object bv = boundValues[i];
               if (isValidObject(bv)) {
                  if (i < boundValues.length - 1) {
                     // Remove listener for bindings which add explicit listeners
                     PBindUtil.removeBindingListener(bv, boundProps[i+1], this, VALUE_CHANGED_MASK);
                  }
                  if (bv instanceof IChangeable) {
                     Bind.removeListener(bv, null, this, VALUE_CHANGED_MASK);
                  }
               }

               // Remove listener for any stateful bindings
               PBindUtil.removeStatefulListener(boundProps[i]);
            }
         }
      }
      if (direction.doReverse() && dstProp != null && !isAssignment) {
         Bind.removeListener(dstObj, dstProp, this, VALUE_CHANGED_MASK);
         if (reverseOnly()) {
            Object propVal = PBindUtil.getPropertyValue(dstObj, dstProp, false, false);
            if (propVal instanceof IChangeable)
               Bind.removeListener(propVal, null, this, VALUE_CHANGED_MASK);
         }
      }
   }

   public void valueRequested(Object obj, IBeanMapper prop) {
      if (sync == SyncType.ON_DEMAND) {
         // If the binding is out of date and the value requested is the right one.
         if (!valid) {
            synchronized (this) {
               if (!valid) {
                  if (obj == dstObj && prop == dstProp && direction.doForward()) {
                     applyBinding(false);
                  }
                  else {
                     Object boundObj = getBoundParent();
                     Object boundProp = getBoundProperty();
                     if (prop == boundProp && obj == boundObj && direction.doReverse())
                        applyReverseBinding();
                  }
               }
            }
         }
      }
   }

   public boolean valueInvalidated(Object srcObject, Object srcProp, Object eventDetail, boolean apply) {
      if ((dstObj == srcObject && PBindUtil.equalProps(dstProp, srcProp)) || (srcProp == null && reverseOnly() && srcObject == boundValues[boundValues.length-1])) {
         if (direction.doReverse()) {
            if (sync == SyncType.ON_DEMAND) {
               invalidate(true, VALUE_INVALIDATED);
            }
         }
         return true;
      }

      Object bindingParent = srcObj;
      boolean changed = false;
      int i;

      if (!valid)
         return true;

      if (cacheValue()) {

         // An event occurred on the value itself for IChangeable objects
         // In this case, we need to mark all dstObj bindings as changed since the physical object may not have changed
         // instead, the value of the internal object changed.  equalObjs will return true.  We need valid=false for
         // all parent bindings.
         if (srcProp == null) {
            // If we are applying (and thus not already invalidating) first invalidate the parent binding
            if (direction.doForward()) {
               if (apply)
                  invalidateBinding(null, false, VALUE_INVALIDATED, false);
               // Then invalidate it
               bindingInvalidated(apply);
            }
            return true;
         }

         // Starting at the root binding, moving down the binding chain.  Look for the property which
         // this change event is for.  If the value has changed, update the listeners for all subsequent
         // elements since they may well have changed.
         for (i = 0; i < boundProps.length; i++) {
            if (changed || (PBindUtil.equalProps(srcProp, boundProps[i]) && srcObject == bindingParent)) {

               // This signals this binding that a previous binding has changed.  Any value cached by
               // a method binding needs to be re-evaluated.
               if (changed) {
                  Bind.parentBindingChanged(boundProps[i]);
               }
               // If we have changed our value, we have not updated the bindingParent yet so getting the property value here would just
               // mark it was valid again with the old stale value.
               else {
                  Object newValue = getBoundProperty(bindingParent, i);

                  // Using sameValues here, not equalValues because if an IChangeable instance has changed we need to re-register the listeners
                  // in valueValidated.
                  if (!sameValues(i, newValue)) {
                     changed = true;
                  }
               }
            }
            bindingParent = boundValues[i];
         }

         if (changed && cacheValue())
            bindingInvalidated(apply);
      }
      return changed;
   }

   public boolean valueValidated(Object srcObject, Object srcProp, Object eventDetail, boolean apply) {
      if ((dstObj == srcObject && PBindUtil.equalProps(dstProp, srcProp)) || (srcProp == null && reverseOnly() && srcObject == boundValues[boundValues.length-1])) {
         if (direction.doReverse()) {
            if (sync != SyncType.ON_DEMAND) {
               if (apply)
                  applyReverseBinding();
            }
         }
         return true;
      }

      Object bindingParent = srcObj;
      boolean changed = false;
      int i;

      if (cacheValue()) {

         // Someone must have validated us since the invalidate phase?  Does this ensure we've also been applied?
         if (valid)
            return false;

         // An event occurred on the value itself for IChangeable objects
         // In this case, we need to mark all dstObj bindings as changed since the physical object may not have changed
         // instead, the value of the internal object changed.  equalObjs will return true.  We need valid=false for
         // all parent bindings.
         if (srcProp == null) {
            // If we are applying (and thus not already invalidating) first invalidate the parent binding.
            // For bi-directional bindings though, this ends up apply the RHS value to the LHS side which is the
            // exactly wrong thing to do when something on the LHS side changes.  I think maybe the issue is
            if (direction.doForward() && !direction.doReverse()) {
               if (apply)
                  applyBinding(false);
            }
            return true;
         }

         // Starting at the root binding, moving down the binding chain.  Look for the property which
         // this change event is for.  If the value has changed, update the listeners for all subsequent
         // elements since they may well have changed.
         for (i = 0; i < boundProps.length; i++) {
            if (changed || (PBindUtil.equalProps(srcProp, boundProps[i]) && srcObject == bindingParent)) {
               Object newValue = getBoundProperty(bindingParent, i);

               if (!equalValues(i, newValue)) {
                  changed = true;
                  valid = false;

                  if (apply) {
                     updateBoundValue(i, newValue);
                  }
               }
               // Not enough to fire the binding since the objects say they are equal, but if it is an IChangeable
               // we still need to update the listeners.
               else if (newValue != boundValues[i]) {
                  updateBoundValue(i, newValue);
               }
            }
            bindingParent = boundValues[i];
         }

         if (changed && direction.doForward())
            if (apply)
               applyBinding(false);
      }
      return changed;
   }

   protected Object getBoundProperty(Object bindingParent, int i) {
      return !isValidObject(bindingParent) ? (bindingParent == PENDING_VALUE_SENTINEL ? PENDING_VALUE_SENTINEL : UNSET_VALUE_SENTINEL) : PBindUtil.getPropertyValue(bindingParent, boundProps[i], false, false);
   }

   protected boolean validateBinding() {
      return validateBinding(boundProps.length);
   }

   protected boolean validateBinding(int validateTo) {
      boolean changed;

      if (cacheValue() && activated) {
         changed = false;
         Object bindingParent = srcObj;
         // Starting at the root binding, moving down the binding chain.  Look for the property which
         // this change event is for.  If the value has changed, update the listeners for all subsequent
         // elements since they may well have changed.
         for (int i = 0; i < validateTo; i++) {
            Object newValue = getBoundProperty(bindingParent, i);

            if (!equalValues(i, newValue)) {
               changed = true;
               updateBoundValue(i, newValue);
            }
            // Not enough to fire the binding since the objects say they are equal, but if it is an IChangeable
            // we still need to update the listeners.
            else if (newValue != boundValues[i]) {
               updateBoundValue(i, newValue);
            }
            bindingParent = boundValues[i];
         }

         if (validateTo == boundProps.length)
            valid = true;
      }
      else
         changed = true;
      return changed;
   }

   private void updateBoundValue(int i, Object newValue) {
      Object oldValue = boundValues[i];
      if (i != boundProps.length-1) { // We don't listen for changes on the leaf property
         int next = i+1;
         if (isValidObject(oldValue))
            PBindUtil.removeBindingListener(oldValue, boundProps[next], this, VALUE_CHANGED_MASK);

         if (isValidObject(newValue))
            PBindUtil.addBindingListener(newValue, boundProps[next], this, VALUE_CHANGED_MASK);
      }

      if (!isAssignment) {
         updateChangeableListeners(oldValue, newValue);
      }

      boundValues[i] = newValue;
   }

   private void updateChangeableListeners(Object oldValue, Object newValue) {
      // If it is marked as changeable, add the leaf property listener
      if (oldValue instanceof IChangeable)
         Bind.removeListener(oldValue, null, this, VALUE_CHANGED_MASK);
      if (newValue instanceof IChangeable)
         Bind.addListener(newValue, null, this, VALUE_CHANGED_MASK);
   }

   protected void bindingInvalidated(boolean apply) {
      if (sync == SyncType.ON_DEMAND) {
         invalidate(true, VALUE_INVALIDATED);
      }
      else {
         invalidateBinding(null, true,  VALUE_INVALIDATED, false);
      }
   }

   // Uses == for IChangeable - so we know to register the listeners
   protected boolean sameValues(int index, Object newValue) {
      Object oldValue = boundValues[index];
      return newValue == oldValue || (!(oldValue instanceof IChangeable) && DynUtil.equalObjects(oldValue, newValue));
   }

   // Uses equals method to do the comparison
   protected boolean equalValues(int index, Object newValue) {
      return DynUtil.equalObjects(newValue, boundValues[index]);
   }

   boolean applyBinding(boolean onlyIfChanged) {

      boolean changed = false;
      if (!valid)
         changed = validateBinding();

      // Get this before marking it valid
      Object newValue = getBoundValue(false);

      if (activated)
         valid = true;
      else
         changed = true; // Need to propagate to parent when not inactivated in case we need to be now active - this is in parallel to the logic in TernaryBinding.invalidateBinding() that deactivates children.  We need to propagate changes up to it in this case

      if (dstObj != dstProp) {

         // Don't set the top-level property with the sentinel, do pass it up to nested bindings though
         // so they know their value is UNSET rather than null.
         if (newValue == UNSET_VALUE_SENTINEL)
            newValue = null;

         //if (trace)
         //   System.out.println(toString("<="));
      }

      if (!onlyIfChanged || changed) {
         if (dstProp instanceof IBinding) {
            ((IBinding) dstProp).applyBinding(dstProp == dstObj ? null : dstObj, newValue, this, false, false);
         }
         else {
            if (dstObj != dstProp && newValue != PENDING_VALUE_SENTINEL)
               PBindUtil.setPropertyValue(dstObj, dstProp, newValue);
         }
      }

      return changed;
   }

   protected boolean applyReverseBinding() {
      Object bObj = getBoundParent();
      Object bProp = getBoundProperty();

      valid = true;
      Object newValue = dstProp instanceof IBinding ? ((IBinding)dstProp).getPropertyValue(dstObj, false, false) : PBindUtil.getPropertyValue(dstObj, dstProp, false, false);

      // For reverse only bindings, we do not want to eval the binding at all... that's what we do when
      // we decide to execute it anyway!
      if (!direction.doForward() || !DynUtil.equalObjects(newValue, getBoundValue(false))) {
         // If we have a reverse binding with a.b but where a is not set yet don't try to set b
         if (bObj == UNSET_VALUE_SENTINEL)
            return false;

         if (!isAssignment) {
            Object oldValue = boundValues[boundValues.length-1];
            if (oldValue instanceof IChangeable)
               Bind.removeListener(oldValue, null, this, VALUE_CHANGED_MASK);
            if (newValue instanceof IChangeable)
               Bind.addListener(newValue, null, this, VALUE_CHANGED_MASK);
         }

         // For bi-directional bindings we have to update this since it gets used above in getBoundValue
         // It needs to be updated before we propagate the change to prevent an extra-round trip firing
         if (cacheValue() || reverseOnly()) {
            boundValues[boundValues.length-1] = newValue;
         }

         if (dstProp != dstObj) {
            if (newValue == UNSET_VALUE_SENTINEL)
               newValue = null;

            //if (trace)
            //   System.out.println(toString("=>"));
         }

         if (newValue == PENDING_VALUE_SENTINEL) {
            System.err.println("*** Pending value in applyReverseBinding");
            return false;
         }

         Bind.applyReverseBinding(bObj, bProp, newValue, this);
         return true;
      }
      return false;
   }

   private Object evalBinding() {
      return evalBindingSlot(boundProps.length-1);
   }

   private Object evalBindingSlot(int last) {
      Object bindingParent = srcObj;

      for (int i = 0; i <= last; i++) {
         bindingParent = PBindUtil.getPropertyValue(bindingParent, boundProps[i], false, false); // TODO: should we use getBoundProperty here to deal with null bindingParent?
      }
      return bindingParent;
   }

   private void invalidate(boolean sendEvent, int event) {
      if (dstObj == dstProp || dstProp instanceof IBinding)
         ((IBinding) dstProp).invalidateBinding(dstObj, sendEvent, event, false);
      else if (sendEvent) {
         if (dstProp instanceof String)
            Bind.sendEvent(event, dstObj, (String) dstProp);
      }
      valid = false;
   }

   // Returns the read dependencies
   // TODO: shouldn't this depend on the propoerty in the change event?  That would let us do meaningful dependencies
   // for bi-dir bindings
   public ISet<Object> getReads() {
      if (direction == BindingDirection.BIDIRECTIONAL)
         return null;
      if (direction.doForward())
         return new CoalescedHashSet<Object>(boundValues);
      else
         return dstPropSet();
   }

   // Returns the write dependencies
   public ISet<Object> getWrites() {
      if (direction == BindingDirection.BIDIRECTIONAL)
         return null;
      if (direction.doForward())
         return dstPropSet();
      else
         return new CoalescedHashSet<Object>(boundValues);
   }

   private ISet<Object> dstPropSet() {
      if (dstProp instanceof IBinding)
         return new SingleElementSet<Object>(((IBinding)dstProp).getPropertyValue(dstObj, false, false));
      return null;
   }

   public void invalidateBinding(Object obj, boolean sendEvent, int event, boolean includeParams) {
      invalidate(sendEvent, event);
   }

   public boolean applyBinding(Object obj, Object value, IBinding src, boolean refresh, boolean pendingChild) {
      int last = boundValues.length-1;
      boolean changed = false;
      if (!DynUtil.equalObjects(boundValues[last], value)) {
         boundValues[last] = value;
         applyBinding(false);
         changed = true;
      }
      else if (!activated)
         applyBinding(true);
      else {
         // If all of our children are valid, mark this binding as valid again
         for (int i = 0; i < boundProps.length; i++) {
            Object boundProp = boundProps[i];
            if (boundProp instanceof DestinationListener) {
               if (!((DestinationListener) boundProp).isValid())
                  return false;
            }
         }
         valid = true;
         // TODO: if this is a child binding, should we mark the parent binding valid too? This happens when a property changes in a child
         // binding of ours but it did not result in the child's value changing. This binding was marked invalid when the original message
         // was propagated through and so needs to be marked valid again as part of the chain. Otherwise, we don't propagate the invalid
         // state to the child the next time this binding is invalidated and fail to re-validate the child.
      }
      return changed;
   }

   public void applyReverseBinding(Object obj, Object value, Object src) {
      int last = boundProps.length-1;
      Object prop = boundProps[last];
      if (prop instanceof IBinding)
         value = ((IBinding)prop).performCast(value);
      // For reverse only expressions we may not cache the value.  right now, happens in the ternary case for the
      // true/false case.
      if (isAssignment || boundValues == null || !DynUtil.equalObjects(boundValues[last], value)) {
         if (boundValues != null) {
            boundValues[last] = value;
         }
         Bind.applyReverseBinding(getBoundParent(), boundProps[last], value, this);
      }
   }

   public Object getPropertyValue(Object obj, boolean getField, boolean pendingChild) {
      if (!valid)
         validateBinding();
      return getBoundValue(pendingChild);
   }

   /** These are implemented for VariableBindings but not for AbstractMethodBindings.  These listeners are
    *  invoked when the previous binding in the chain's value changes.  Since the bound props of the variable
    *  binding depend on the value of the previous value, we need to remove/re-add them on any upstream change.
    *  Method parameters listeners do not respond to changes in the previous value so they don't implement this
    *  method.
    */
   public void addBindingListener(Object eventObject, IListener listener, int event) {
      if (null == dstProp && isValidObject(eventObject)) {
         for (Object param:boundProps)
           PBindUtil.addBindingListener(eventObject, param, this, event);
      }
   }

   public void removeBindingListener(Object eventObject, IListener listener, int event) {
      if (null == dstProp && isValidObject(eventObject)) {
         for (Object param:boundProps)
            PBindUtil.removeBindingListener(eventObject, param, this, event);
      }
   }



   public boolean isConstant() {
      if (boundProps != null) {
         for (int i = 0; i < boundProps.length; i++) {
            Object boundProp = boundProps[i];
            if (boundProp instanceof IBinding) {
               if (!((IBinding) boundProp).isConstant())
                  return false;
            }
            else // TODO: we could check for a final modifier on the property here?
               return false;
         }
         return true;
      }
      return false;
   }

   public String toString(String operation, boolean displayValue) {
      StringBuilder sb = new StringBuilder();
      if (dstObj != dstProp && operation != null) {
         sb.append(operation);
         sb.append(" ");
      }
      sb.append(super.toString(operation, displayValue));
      if (displayValue) {
         if (dstObj != dstProp) {
            sb.append(toBindingString());
            sb.append(" ");
         }
         if (direction != null && direction.doForward()) {
            if (dstObj != dstProp) {
               sb.append("= ");
            }
            if (boundValues != null)
               sb.append(DynUtil.getInstanceName(boundValues[boundValues.length - 1]));
         }
      }
      else {
         sb.append(toBindingString());
      }
      return sb.toString();
   }

   private StringBuffer toBindingString() {
      StringBuffer sb = new StringBuffer();
      if (boundProps == null)
         sb.append("<not initialized>");
      else {
         // Indicate that these bindings are not on properties from the "this" object.
         // they could have come from any object reference or a parent of this... not sure
         // how we get from the instance back to its type name (if any)
         if (srcObj != dstObj) {
            sb.append(DynUtil.getInstanceName(srcObj));
            sb.append(".");
         }
         for (int i = 0; i < boundProps.length; i++) {
            Object b = boundProps[i];
            if (i != 0)
               sb.append(".");
            if (b == null)
               System.err.println("<prop " + i + " failed to init>");
            else
               sb.append(b.toString());
         }
      }
      return sb;
   }

   public void activate(boolean state, Object obj, boolean chained) {
      if (state == activated)
         return;
      super.activate(state, null, chained);
      Object bindingParent = srcObj;
      int i = 0;
      for (Object param:boundProps) {
         Bind.activate(param, state, bindingParent, true);

         if (state && (i == 0 || (bindingParent != null && bindingParent != UNSET_VALUE_SENTINEL))) {
            bindingParent = getBoundProperty(bindingParent, i);
         }
         else
            bindingParent = null; // This is only used for state = true - so we can revalidate the tree
         i++;
      }

      if (state) {
         if (!valid)
            reactivate(obj);
      }
      else // TODO: required to set this to false because bindings when deactivated do not always deliver events.  It would not be necessary to invalidate them if they invalidate the parent during the re-activation process when their value is changed.  The goal being that if you switch back and forth you do not have to recache the world.  See SelectorBinding as well
         valid = false;
   }

   public int refreshBinding() {
      if (!activated)
         return 0;
      if (direction.doForward() && !direction.doReverse()) {
         invalidate(false, 0);
         if (applyBinding(true)) {
            return 1;
         }
         return 0;
      }
      else if (direction.doReverse() && !direction.doForward()) {
         // Why would we always invoke a reverse binding on refresh?  Maybe we should be comparing the dstProp's old value with the current one?
         //if (applyReverseBinding())
         //   return 1;
         return 0;
      }
      // Can't refresh bi-directional bindings
      return 0;
   }

   public void accessBinding() {
      super.accessBinding();
      if (!activated)
         return;
      if (direction.doForward() && !direction.doReverse()) {
         if (srcObj != null) {
            accessObj(srcObj);
         }
         // TODO: should we access the boundValues here?
      }
   }

   public boolean isReversible() {
      return true;
   }

   protected void applyPendingChildValue(Object val, IBinding src) {
      // Starting at the root binding, moving down the binding chain.  Look for the property which
      // this change event is for.  If the value has changed, update the listeners for all subsequent
      // elements since they may well have changed.
      for (int i = 0; i < boundProps.length; i++) {
         if (boundProps[i] == src) {
            if (cacheValue())
               updateBoundValue(i, val);
            if (i != boundProps.length - 1)
               System.err.println("*** Need to write code to validate a pending binding");
            else {
               // If we are a binding in the chain, propagate the pending value up the chain
               if (dstProp == dstObj) {
                  if (dstObj instanceof DestinationListener) {
                     // Need to cache the bound value so that our parent binding can retrieve it
                     // without having to eval the binding again (and call the remote method over again)
                     if (boundValues == null)
                        boundValues = new Object[boundProps.length];
                     boundValues[i] = val;
                     ((DestinationListener) dstObj).applyPendingChildValue(val, this);
                  }
               }
               // If we're a top-level binding and is a := we need to apply the binding
               else if (direction.doForward()) {
                  applyBinding(false); // Once our value arrives, need to apply if we are the last binding in the chain.
               }
            }
         }
      }
   }

   protected Object getBoundValueForChild(IBinding child) {
      int childCt = 0;
      for (; childCt < boundProps.length; childCt++) {
         if (boundProps[childCt] == child)
            break;
      }
      // Validate up until the property before the child who needs the parent value.
      // This will not mark the binding as 'valid' because we still need to apply it up
      // it's chain.
      if (!valid)
         validateBinding(childCt);
      if (childCt == 0)
         return srcObj;
      return boundValues[childCt-1];
   }

   protected void initFlagsOnChildren(int flags) {
      super.initFlagsOnChildren(flags);
      if (boundProps != null) {
         for (Object bp:boundProps)
            if (bp instanceof DestinationListener)
               ((DestinationListener) bp).initFlagsOnChildren(flags);
      }
   }
}
