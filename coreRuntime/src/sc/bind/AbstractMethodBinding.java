/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import sc.dyn.DynUtil;
import sc.sync.SyncManager;

public abstract class AbstractMethodBinding extends DestinationListener {
   Object methObj; // Usually equal to dstObj but can be different with nested types
   IBinding[] boundParams;
   Object[] paramValues;
   Object boundValue;
   boolean constant;
   boolean valid = false;
   boolean methObjSet = false;

   public boolean isValid() {
      return valid;
   }

   AbstractMethodBinding(IBinding[] params) {
      boundParams = params;
   }

   public AbstractMethodBinding(Object dstObject, Object dstBinding, Object methodObject, IBinding[] parameterBindings, BindingDirection dir, int flags, BindOptions opts) {
      this(parameterBindings);
      dstObj = dstObject;
      setMethObj(methodObject);
      methObjSet = methodObject != null;
      dstProp = dstBinding;
      direction = dir;
      initFlags(flags, opts);
   }

   abstract protected Object invokeMethod(Object obj, boolean pendingChild);

   abstract protected Object invokeReverseMethod(Object obj, Object value);

   protected void setMethObj(Object newMethObj) {
      methObj = newMethObj;
   }

   /** Called by the parent when this is a hierarchical binding - i.e. one expression in another */
   public void setBindingParent(IBinding parent, BindingDirection dir) {
      dstProp = parent;
      dstObj = parent;
      direction = dir;

      initParams();
   }

   protected void initParams() {
      boolean allConst = true;
      paramValues = new Object[boundParams.length];
      int i = 0;
      for (IBinding param:boundParams) {
         if (!param.isConstant())
            allConst = false;
         BindingDirection propBD;
         if (direction.doReverse() && !propagateReverse(i))
            propBD = direction.doForward() ? BindingDirection.FORWARD : BindingDirection.NONE;
         else
            propBD = direction;
         param.setBindingParent(this, propBD);
         i++;
      }
      constant = allConst;
   }

   abstract boolean propagateReverse(int ix);

   /** Called only for top level bindings */
   public Object initializeBinding() {
      assert dstObj != dstProp; // This should only be called for a top-level binding
      initParams();

      Object result = null;
      if (direction.doForward()) {
         if (!activated)
            result = null;
         else {
            result = invokeMethod(methObj, false);
            if (isDefinedObject(result))
               valid = true;
         }
         boundValue = result;
      }
      if (direction.doReverse()) {
         // TODO: need flag for "read-only" bindings - throw if it is a read-only
         if (dstProp instanceof IBinding)
            ((IBinding)dstProp).addBindingListener(dstObj, this, VALUE_CHANGED_MASK);
         else
            PBindUtil.addBindingListener(dstObj, dstProp, this, VALUE_CHANGED_MASK);

         // This is a bit of a hack.  For reverse only bindings, we should not be changing the value of the dst property
         // at all.  When the binding replaces the assignment expression, it's awkward to rewrite the code so the
         // property assignment never happens (TODO: is this still true? reverse bindings do not set the destination property)
         // In some cases we do also need to register the IChangeable hook and keep it up to date in the value change part
         if (!direction.doForward()) {
            if (dstProp == null)
               System.out.println("*** Null property in binding");
            boundValue = PBindUtil.getPropertyValue(dstObj, dstProp, false, false);

            if (useReverseListener()) {
               if (boundValue instanceof IChangeable)
                  Bind.addListener(boundValue, null, this, VALUE_CHANGED_MASK);
            }
            result = boundValue;
         }
      }
      //if (trace)
      //   System.out.println("Init: " + toString());
      if (!isDefinedObject(result))
         result = null;
      return result;
   }

   private void reactivate(Object obj) {
      if (direction.doForward()) {
         if (!methObjSet)
            setMethObj(obj);

         if (methObj == null)
            boundValue = null;
         else
            boundValue = invokeMethod(methObj, false);
      }
      else if (direction.doReverse()) {
         Object newValue;
         if (dstProp instanceof IBinding)
             newValue = ((IBinding) dstProp).getPropertyValue(dstObj, false, false);
          else
             newValue = PBindUtil.getPropertyValue(dstObj, dstProp, false, false);

         if (!equalObjects(newValue, boundValue)) {
            if (useReverseListener()) {
               if (boundValue instanceof IChangeable)
                  Bind.removeListener(boundValue, null, this, VALUE_CHANGED_MASK);
               if (newValue instanceof IChangeable)
                  Bind.addListener(newValue, null, this, VALUE_CHANGED_MASK);
            }
            boundValue = newValue;
         }
      }
      if (isDefinedObject(boundValue))
         valid = true;
   }

   boolean useReverseListener() {
      return direction.doReverse() && !direction.doForward() && dstObj != dstProp;
   }

   public Object getPropertyValue(Object object, boolean getField, boolean pendingChild) {
      // Do not cache results when it's a reverse only binding.  We're not listening on our parameters in
      // that case so we can't rely on being notified.
      if (!valid || !direction.doForward()) {
         if (!activated) {
            boundValue = null;
         }
         else {
            boundValue = invokeMethod(object, pendingChild);
            if (isDefinedObject(boundValue))
               valid = true;
         }
         // If we were set with an explicit method object, always use that one.  Otherwise, we'll use
         // the one from the previous binding in the chain.
         if (!methObjSet)
            setMethObj(object);
      }
      return boundValue;
   }

   /** Implemented only for child property bindings */
   public void addBindingListener(Object eventObject, IListener listener, int event) {
      /*
      if (dstObj == dstProp) {
         for (IBinding param:boundParams)
           param.addBindingListener(eventObject, this, event);
      }
      */
   }

   /** Abstract method objects don't in general do this.  Their listeners are implicitly added via the
    *  bindings list in the parameters.  That gets set up during init and torn down in "removeListener". */
   public void removeBindingListener(Object eventObject, IListener listener, int event) {
      //if (dstObj == dstProp) {
      //   removeListener();
            //param.removeBindingListener(eventObject, this, event);
      //}
   }

   public void invalidateBinding(Object object, boolean sendEvent, boolean invalidateParams) {
      valid = false;
      if (dstProp instanceof IBinding)
         ((IBinding) dstProp).invalidateBinding(dstObj, sendEvent, false);
      // This option walks down the chain and is used when we call 'refreshBinding' - we need to invalidate all of the parameters in the call chain so that we re-evaluate them.  Otherwise, we assume they are valid and only go one level deep.
      if (invalidateParams) {
         for (IBinding param:boundParams) {
            param.invalidateBinding(object, sendEvent, true);
         }
      }
      else if (dstProp instanceof String) {
         if (sendEvent)
            Bind.sendEvent(VALUE_CHANGED, dstObj, (String) dstProp);
      }
   }

   /** Override in subclasses that do not use the methObj so we can tell when the method just could not be resolved */
   protected boolean needsMethodObj() {
      return true;
   }

   boolean removed = false;
   public void removeListener() {
      if (removed) {
         System.err.println("*** removing binding twice");
         return;
      }
      removed = true;
      if (direction.doReverse() && dstProp != dstObj)
         Bind.removeListener(dstObj, dstProp, this, VALUE_CHANGED_MASK);

      // For the special reverse-only bindings we add the listener onto the IChangeable values
      if (direction.doReverse() && !direction.doForward()) {
         if (useReverseListener()) {
            if (boundValue instanceof IChangeable)
               Bind.removeListener(boundValue, null, this, VALUE_CHANGED_MASK);
         }
      }
      for (IBinding param:boundParams)
         param.removeListener();
   }

   public void parentBindingChanged() {
      valid = false;
   }

   public boolean applyBinding(Object obj, Object value, IBinding src, boolean refresh, boolean pendingChild) {
      Object newBoundValue = null;

      if (activated) {
         try {
            // Before we apply this method, if there's a parent binding we need to make sure it's valid and that we have the up-to-date version of the current object for this binding (the methObj).
            if (needsMethodObj() && dstObj == dstProp) {
               Object newMethObj = ((DestinationListener) dstObj).getBoundValueForChild(this);
               if (newMethObj != UNSET_VALUE_SENTINEL)
                  setMethObj(newMethObj);
            }
            // When we don't have a method object and there is no current value, we can't apply the method
            // or it will get an RTE.  Need to set it to UNSET sentinel so that we don't mark this guy as valid
            if (obj == null && (needsMethodObj() && methObj == null)) {
               newBoundValue = UNSET_VALUE_SENTINEL;
            }
            else
               newBoundValue = invokeMethod(obj == null ? methObj : obj, false);
         }
         catch (Throwable exc) {
            System.err.println("*** Error applying binding: " + this + " with value: " + value + " :" + exc.toString());
            exc.printStackTrace();
         }
      }

      if (!direction.doForward())
         return false;

      // If we are not activated, we need to call applyBinding on the parent.  When it activates us, we'll be re-validated.
      boolean valueChanged = !activated || !equalObjects(newBoundValue, boundValue);
      if (refresh || !valid || valueChanged) {
         if (refresh && !valueChanged)
            return false;
         if (activated) {
            boundValue = newBoundValue;
            if (isDefinedObject(boundValue))
               valid = true;
         }

         if (dstObj != dstProp) {
            if (newBoundValue == UNSET_VALUE_SENTINEL)
               newBoundValue = null;

            //if (trace)
            //   System.out.println(toString("<" + direction.getOperatorString()));
         }

         if (newBoundValue != PENDING_VALUE_SENTINEL) {
            applyChangedValue(newBoundValue);
            return true;
         }
      }
      return false;
   }

   protected void applyPendingValue(Object pendingResult) {
      boundValue = pendingResult;
      if (activated)
         valid = true;
      if (dstProp == null) {
         if (dstObj instanceof DestinationListener)
            ((DestinationListener) dstObj).applyPendingChildValue(pendingResult, this);
         else
            System.err.println("*** Unhandled case in applyPendingValue");
      }
      else if (direction.doForward()) {
         applyChangedValue(boundValue);
      }
   }

   protected void applyChangedValue(Object newBoundValue) {
      Bind.applyBinding(dstProp == dstObj ? null : dstObj, dstProp, newBoundValue, this);
   }

   protected boolean equalObjects(Object v1, Object v2) {
      return DynUtil.equalObjects(v1, v2);
   }

   public void applyReverseBinding(Object obj, Object value, Object src) {
      boolean endLogIndent = false;
      try {
         if (Bind.trace || (flags & Bind.TRACE) != 0)
            endLogIndent = Bind.logBindingMessage("reverse", this, obj, value, src);

         // Have to set boundValue here, not after invokeReverseMethod because that can in turn retrigger lots of stuff like
         // the removal of the listener etc.
         boundValue = value;
         invokeReverseMethod(obj, value); // used to be methObj here but we need the accurate bound value here, not the cached one from last time
      }
      finally {
         if (endLogIndent)
            Bind.endPropMessage();
      }
      //if (trace && dstProp != dstObj)
      //   System.out.println(toString(direction.getOperatorString() + ">"));
   }

   public boolean valueValidated(Object srcObject, Object srcProp, Object eventDetail, boolean apply) {
      // Added the listener to methObj to detect any events which should invalidate the listeners on the method object
      if (direction.doForward() && !direction.doReverse()) {
         if (srcProp == null) {
            if (apply)
               applyBinding(null, null, null, false, false);
         }
         return true;
      }
      // If either the default event on the IChangeable, or it's the dest property changing
      else if (direction.doReverse()) {
         if ((dstObj == srcObject && PBindUtil.equalProps(dstProp, srcProp))) {
            Object currentValue = PBindUtil.getPropertyValue(srcObject, dstProp, false, false);

            boolean isChangeable = boundValue instanceof IChangeable;

            // IChangeable objects can change even when the object instance itself does not change.
            if (!equalObjects(currentValue, boundValue) || isChangeable) {

               if (useReverseListener()) {
                  if (currentValue != boundValue) {
                     // We also listen for any events this object sends on itself if it is marked with IChangeable
                     if (isChangeable)
                        Bind.removeListener(boundValue, null, this, VALUE_CHANGED_MASK);
                     if (currentValue instanceof IChangeable)
                        Bind.addListener(currentValue, null, this, VALUE_CHANGED_MASK);
                  }
               }
               boundValue = currentValue;

               if (apply)
                  applyReverseBinding(methObj, currentValue, srcProp);
               return true;
            }
         }
         else if (srcProp == null) {
            if (apply) {
               if (direction.doForward())
                  applyBinding(null, null, null, false, false);
               applyReverseBinding(methObj, boundValue, srcProp);
            }
            return true;
         }
         return false;
      }
      else
         throw new UnsupportedOperationException();
   }

   public boolean valueInvalidated(Object srcObject, Object srcProp, Object eventDetail, boolean apply) {
      // Added the listener to methObj to detect any events which should invalidate the listeners on the method object
      if (direction.doForward() && !direction.doReverse()) {
         if (srcProp == null) {
            valid = false;
            invalidateBinding(null, false, false);
         }
         return true;
      }
      else if (direction.doReverse()) {
         if ((dstObj == srcObject && PBindUtil.equalProps(dstProp, srcProp))) {
            Object currentValue = PBindUtil.getPropertyValue(srcObject, srcProp, false, false);

            if (!equalObjects(currentValue, boundValue) || boundValue instanceof IChangeable) {
               return true;
            }
         }
         else if (srcProp == null) {
            return true;
         }
         return false;
      }
      else
         throw new UnsupportedOperationException();
   }

   public boolean isConstant() {
      return constant;
   }

   public void activate(boolean state, Object obj, boolean chained) {
      if (state == activated)
         return;
      super.activate(state, obj, chained);
      for (IBinding bp:boundParams)
         bp.activate(state, obj, true);

      if (!state)
         valid = false;
      else if (!valid)
         reactivate(obj);
   }

   public IBinding[] getBoundParams() {
      return boundParams;
   }

   protected boolean useParens() {
      return true;
   }

   public StringBuilder toBindingString(boolean displayValue) {
      StringBuilder sb = new StringBuilder();
      if (useParens())
         sb.append("(");
      if (displayValue) {
         for (int i = 0; i < paramValues.length; i++) {
            if (i != 0)
               sb.append(",");
            if (boundParams[i] instanceof ArithmeticBinding)
               sb.append(((ArithmeticBinding) boundParams[i]).toBindingString(true));
            else
               sb.append(DynUtil.getInstanceName(paramValues[i]));
         }
         //sb.append(DynUtil.arrayToInstanceName(paramValues));
      }
      else
         sb.append(Bind.arrayToString(boundParams));
      if (useParens())
         sb.append(")");
      return sb;
   }

   protected boolean isRefreshDisabled() {
      return false;
   }

   public int refreshBinding() {
      if (!activated)
         return 0;

      if (direction.doForward() && !direction.doReverse() && !isRefreshDisabled()) {
         invalidateBinding(null, false, true);
         if (applyBinding(null, null, null, true, false))
            return 1;
         return 0;
      }
      return 0;
   }

   public void accessBinding() {
      super.accessBinding();
      if (!activated)
         return;
      if (direction.doForward() && !direction.doReverse() && !isRefreshDisabled()) {
         accessBindingInternal(null);
         return;
      }
   }

   void accessBindingInternal(Object obj) {
      if (activated) {
         try {
            // When we don't have a method object and there is no current value, we can't apply the method
            // or it will get an RTE.
            if (obj == null && (needsMethodObj() && methObj == null))
               return;
            else {
               Object toAccess = obj == null ? methObj : obj;
               if (toAccess != null) {
                  accessObj(toAccess);

                  if (paramValues != null) {
                     for (int i = 0; i < paramValues.length; i++) {
                        IBinding boundParam = boundParams[i];
                        if (boundParam instanceof DestinationListener)
                           ((DestinationListener) boundParam).accessBinding();
                        Object paramValue = paramValues[i];
                        if (paramValue != null && paramValue != PENDING_VALUE_SENTINEL && paramValue != UNSET_VALUE_SENTINEL) {
                           accessObj(paramValue);
                        }
                     }
                  }
               }
            }
         }
         catch (Throwable exc) {
            System.err.println("*** Error accessing binding: " + this + " exception: " + exc.toString());
            exc.printStackTrace();
         }
      }

      // If we are not activated, we need to call applyBinding on the parent.  When it activates us, we'll be re-validated.
   }

   // Methods are not receiving values from reverse-only bindings.
   public boolean isReversible() {
      return false;
   }

   // A method nested inside of this one has a pending result.
   protected void applyPendingChildValue(Object pendingResult, IBinding src) {
      for (int i = 0; i < paramValues.length; i++) {
         if (boundParams[i] == src) {
            // assert paramValues[i] == PENDING_VALUE_SENTINEL
            paramValues[i] = pendingResult;
            if (!hasPendingParams())
               applyBinding(null, null, null, false, true);
            break;
         }
      }
   }

   protected Object getBoundValueForChild(IBinding child) {
      return UNSET_VALUE_SENTINEL;  // In this case, the methObj in the child should have been set to the this object when it was created.
   }

   protected Object[] cleanParamValues() {
      Object[] pvs = paramValues;
      if (hasUnsetParams()) { // Need to convert any unset parameters to null here because we are calling the method anyway
         pvs = new Object[pvs.length];
         for (int p = 0; p < pvs.length; p++) {
            pvs[p] = paramValues[p];
            if (pvs[p] == UNSET_VALUE_SENTINEL)
               pvs[p] = null;
         }
      }
      return pvs;
   }

   protected boolean hasPendingParams() {
      for (int i = 0; i < paramValues.length; i++) {
         if (paramValues[i] == PENDING_VALUE_SENTINEL)
            return true;
      }
      return false;
   }

   protected boolean hasUnsetParams() {
      for (int i = 0; i < paramValues.length; i++) {
         if (paramValues[i] == UNSET_VALUE_SENTINEL)
            return true;
      }
      return false;
   }

   protected boolean hasNullParams() {
      for (int i = 0; i < paramValues.length; i++) {
         if (paramValues[i] == UNSET_VALUE_SENTINEL || paramValues[i] == null)
            return true;
      }
      return false;
   }

   protected void initFlagsOnChildren(int flags) {
      super.initFlagsOnChildren(flags);
      if (boundParams != null) {
         for (IBinding bp:boundParams)
            if (bp instanceof DestinationListener)
               ((DestinationListener) bp).initFlagsOnChildren(flags);
      }
   }
}
