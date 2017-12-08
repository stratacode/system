/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import static sc.bind.Bind.info;
import static sc.bind.Bind.trace;
import sc.dyn.DynUtil;
import sc.dyn.IReverseMethodMapper;
import sc.dyn.RemoteResult;
import sc.type.IResponseListener;
import sc.type.PTypeUtil;

/** Represents a binding expression for a method invocation.   If this is a forward binding, the binding fires whenever
 * any of the nested bindings fire.
 * */
public class MethodBinding extends AbstractMethodBinding implements IResponseListener {
   Object method;
   boolean methodIsStatic;

   IReverseMethodMapper reverseMethodMapper;

   boolean requireValidParameters = false;

   Object[] paramTypes;

   /** A method binding which is nested inside of another binding */
   public MethodBinding(Object meth, IBinding[] parameterBindings) {
      super(parameterBindings);
      setMethod(meth);
   }

   /** A method binding which is nested inside of another binding */
   public MethodBinding(Object methObj, Object meth, IBinding[] parameterBindings) {
      super(parameterBindings);
      setMethObj(methObj);
      methObjSet = methObj != null;
      setMethod(meth);
   }

   /** A top level method binding */
   public MethodBinding(Object dstObject, IBinding dstBinding, Object methObj, Object meth, IBinding[] parameterBindings, BindingDirection dir, int flags, BindOptions opts) {
      super(dstObject, dstBinding, methObj, parameterBindings, dir, flags, opts);
      setMethod(meth);
   }

   private void setMethod(Object meth) {
      method = meth;
      if (method == null)
         System.err.println("*** Unable to resolve method in binding: " + this);
      else {
         paramTypes = DynUtil.getParameterTypes(method);
         methodIsStatic = DynUtil.hasModifier(method, "static");
      }
   }

   public Object getMethod() {
      return method;
   }

   protected void setMethObj(Object newMethObj) {
      if (methObj == newMethObj)
         return;
      if (direction != null)
         updateMethObj(newMethObj, true);
      else
         super.setMethObj(newMethObj);
   }

   private void updateMethObj(Object newMethObj, boolean doRemove) {
      if (direction.doForward()) {
         if (doRemove && methObj != null && methObj != dstObj)
            Bind.removeListener(methObj, null, this, VALUE_CHANGED_MASK);
         super.setMethObj(newMethObj);
         if (newMethObj != null && newMethObj != dstObj)
            Bind.addListener(newMethObj, null, this, VALUE_CHANGED_MASK);
      }
   }

   public Object initializeBinding() {
      if (method == null)
         throw new IllegalArgumentException("Invalid binding: " + this);
      updateMethObj(methObj, false);
      initReverseMethod();
      return super.initializeBinding();
   }

   private void initReverseMethod() {
      // We treat purely reverse bindings differently than bi-directional ones.
      // it just means execute the method in the reverse direction.
      if (direction.doReverse() && direction.doForward() && reverseMethodMapper == null) {
         reverseMethodMapper = DynUtil.getReverseMethodMapper(this);
      }
   }

   public void setBindingParent(IBinding parent, BindingDirection dir) {
      super.setBindingParent(parent, dir);
      updateMethObj(methObj, false);
      // We treat purely reverse bindings differently than bi-directional ones.
      // it just means execute the method in the reverse direction.
      initReverseMethod();
   }

   private boolean updateParams(Object obj) {
      boolean valid = true;

      for (int i = 0; i < paramValues.length; i++) {
         Object val, type;
         Class typeClass;
         val = boundParams[i].getPropertyValue(obj);
         if (val == UNSET_VALUE_SENTINEL) {
            valid = false;
            val = null;
         }
         if (val == PENDING_VALUE_SENTINEL)
            valid = false;
         paramValues[i] = val;
         // Do the wrapping of "null" to primitive types here
         if (val == null && paramTypes != null && ((type = paramTypes[i]) instanceof Class) && PTypeUtil.isPrimitive(typeClass = (Class) type)) {
            paramValues[i] = PTypeUtil.getDefaultObjectValue(typeClass);
         }
      }

      return valid;
   }

   boolean propagateReverse(int ix) {
      initReverseMethod();
      return reverseMethodMapper != null && reverseMethodMapper.propagateReverse(ix);
   }

   protected boolean needsMethodObj() {
      return !methodIsStatic;
   }

   protected Object invokeMethod(Object obj) {
      if (obj == null && !methodIsStatic) {
         System.err.println("*** Attempt to invoke method: " + this + " with a null value");
         return null;
      }
      if (paramValues == null) {
         if (trace || (flags & Bind.TRACE) != 0)
            System.out.println("Null value for method invocation: " + this);
         return null;
      }

      // If we weren't able to evaluate one of our parameters, this value is unset
      if (!updateParams(obj) && requireValidParameters) {
         if (hasPendingParams())
            return PENDING_VALUE_SENTINEL;
         return UNSET_VALUE_SENTINEL;
      }

      if (methodIsStatic)
         obj = null;
      else if (methObjSet)
         obj = methObj;

      try {
         if (DynUtil.isRemoteMethod(method)) {
            if (boundValue == PENDING_VALUE_SENTINEL)
               return PENDING_VALUE_SENTINEL;
            RemoteResult remRes = DynUtil.invokeRemote(null, null, obj, method, paramValues);
            // When this listener fires, we call applyChangedValue(remRes.value)
            remRes.responseListener = this;
            return PENDING_VALUE_SENTINEL;
         }
         else {
            return DynUtil.invokeMethod(obj, method, paramValues);
         }
      }
      catch (RuntimeException exc) {
         if (info || trace || (flags & Bind.TRACE) != 0) {
            System.err.println("Runtime exception from method binding: " + this + ": " + exc);
            exc.printStackTrace();
         }
      }
      catch (Exception exc) {
         if (info || trace || (flags & Bind.TRACE) != 0) {
            System.err.println("Exception from method binding: " + this + ": " + exc);
            exc.printStackTrace();
         }
      }
      return null;
   }

   /** Called when reverse bindings fire */
   protected Object invokeReverseMethod(Object obj, Object value) {
      if (reverseMethodMapper != null) {
         // If we fire the reverse method before the forward one is valid, need to try at least to init the params
         if (!valid) {
            if (!updateParams(null) && requireValidParameters)
               return hasPendingParams() ? PENDING_VALUE_SENTINEL : UNSET_VALUE_SENTINEL;
         }

         if (value == UNSET_VALUE_SENTINEL)
            return UNSET_VALUE_SENTINEL;

         if (value == PENDING_VALUE_SENTINEL)
            return PENDING_VALUE_SENTINEL;

         return reverseMethodMapper.invokeReverseMethod(obj, value, paramValues);
      }
      // This is the case where we have only a reverse binding and no inverse method.  We treat
      // this case differently - it means invoke the method only on the reverse direction.
      else if (!direction.doForward())
         return invokeMethod(obj);
      else
         System.err.println("*** Reverse binding not firing - no reverse method: " + this);
      return null;
   }

   public String toString(String operation, boolean displayValue) {
      StringBuilder sb = new StringBuilder();
      if (displayValue && operation != null) {
         sb.append(operation);
         sb.append(" ");
      }
      sb.append(super.toString(operation, displayValue));
      if (dstObj != dstProp && displayValue) {
         sb.append(toBindingString(false));
         sb.append(" = ");
      }
      sb.append((CharSequence) toBindingString(displayValue));
      if (dstObj != dstProp && valid && displayValue)
         sb.append(" = " + DynUtil.getInstanceName(boundValue));
      return sb.toString();
   }

   public StringBuilder toBindingString(boolean displayValue) {
      StringBuilder sb = new StringBuilder();
      sb.append((method == null ? "<null>" : DynUtil.getMethodName(method)));
      sb.append(super.toBindingString(displayValue));
      return sb;
   }

   public void removeListener() {
      super.removeListener();
      if (direction.doForward()) {
         if (methObj != null && methObj != dstObj) {
            Bind.removeListener(methObj, null, this, VALUE_CHANGED_MASK);
            methObj = null;
         }
      }
   }

   public void response(Object value) {
      boundValue = value;
      valid = true;
      // Do not apply the return value when this is a top-level method on a reverse-only method.
      if ((direction.doForward() || !direction.doReverse()) || dstObj == dstProp)
         applyPendingValue(value);
      else if (trace || (flags & Bind.TRACE) != 0)
         System.out.println("Remote method returned value: " + objectToString(value) + " not used in binding: " + this);
   }

   public void error(int errorCode, Object error) {
      System.err.println("Error occurred in remote method: " + errorCode + ": " + error);
      boundValue = UNSET_VALUE_SENTINEL; // clear the pending flag.
      // TODO: any way to propagate this error?  Should it be the return value if the method returns a certain type?
      // TODO: should this boundValue be propagated so we clear out upstream "pending" values?
   }

   // TODO: should have an annotation that disables this as well, both on the property and on the method
   /** Some bindings should not be refreshed with the refreshBinding, such as a remote method call */
   protected boolean isRefreshDisabled() {
      return method != null && DynUtil.isRemoteMethod(method);
   }

   public void invalidateBinding(Object object, boolean sendEvent, boolean invalidateForRefresh) {
      if (invalidateForRefresh && isRefreshDisabled())
         return;
      super.invalidateBinding(object, sendEvent, invalidateForRefresh);
   }
}
