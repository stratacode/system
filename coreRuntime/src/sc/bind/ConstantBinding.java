/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import sc.js.JSSettings;

@JSSettings(jsLibFiles = "js/scbind.js", prefixAlias="sc_")
public class ConstantBinding implements IBinding {
   Object value;

   public ConstantBinding(Object val) {
      value = val;
   }
   
   public Object getPropertyValue(Object parent, boolean getField, boolean pendingChild) {
      return value;
   }

   public void addBindingListener(Object eventObject, IListener listener, int event) {
   }

   public void removeBindingListener(Object eventObject, IListener listener, int event) {
   }

   public void invalidateBinding(Object object, boolean sendEvent, boolean includeParams) {
   }

   public boolean applyBinding(Object obj, Object value, IBinding src, boolean refresh, boolean pendingChild) {
      throw new UnsupportedOperationException("Can't apply binding to a constant: " + this);
   }

   /** This might happen if you have: foo =: (x ? null : null) - i.e. it implies evaluating the constant as an expression when the reverse event fires */
   public void applyReverseBinding(Object obj, Object value, Object src) {
   }

   public void removeListener() {
   }

   public Object initializeBinding() {
      return value;
   }

   public void setBindingParent(IBinding parent, BindingDirection dir) {
   }

   public boolean isConstant() {
      return true;
   }

   public void parentBindingChanged() {}

   public String toString() {
      if (value == null)
         return "null";
      return value.toString();
   }

   public Object performCast(Object val) {
      return val;
   }

   public void activate(boolean state, Object obj, boolean chained) {
   }

   public boolean isReversible() {
      return false;
   }
}
