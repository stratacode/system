/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import sc.js.JSSettings;

@JSSettings(jsLibFiles = "js/scbind.js", prefixAlias="sc_")
public interface IBinding {
   /**
    * Retrieves the current value of the binding given the current object.
    * Use getField = true to force use of the field, rather than the getX method if one exists.
    * Use pendingChild = true for a special case where we are getting the property value of a
    * binding that is part of a reverse binding but where the cached value is up-to-update because
    * we are updating a child remote property.
    */
   Object getPropertyValue(Object parent, boolean getField, boolean pendingChild);

   void addBindingListener(Object eventObject, IListener listener, int event);

   void removeBindingListener(Object eventObject, IListener listener, int event);

   void invalidateBinding(Object object, boolean sendEvent, boolean invalidateParams);

   boolean applyBinding(Object obj, Object value, IBinding src, boolean refresh, boolean pendingChild);

   Object performCast(Object value);

   void applyReverseBinding(Object obj, Object value, Object src);

   void removeListener();

   Object initializeBinding();

   void setBindingParent(IBinding parent, BindingDirection dir);

   boolean isConstant();

   // True for variables and invertible expressions - where you can propagate the value through
   boolean isReversible();

   /**
    * Called from the VariableBinding when the parent's value has changed.  E.g. for "a.b",
    * b.parentBindingChanged() is called when we detect changes on 'a'.
    */
   void parentBindingChanged();

   /** Called to deactive/re-activate a child binding for a condition or ternary expression */
   void activate(boolean state, Object obj, boolean chained);
}
