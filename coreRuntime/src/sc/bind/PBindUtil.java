/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import sc.dyn.DynUtil;
import sc.type.IBeanMapper;

@sc.js.JSSettings(replaceWith="sc_PBindUtil", jsLibFiles="js/scpbind.js")
public class PBindUtil {
   public static Object getPropertyValue(Object obj, Object prop, boolean getField, boolean pendingChild) {
      if (prop instanceof IBinding)
         return ((IBinding) prop).getPropertyValue(obj, getField, pendingChild);
      return DynUtil.getProperty(obj, (String) prop);
   }

   public static void setPropertyValue(Object obj, Object prop, Object val) {
      if (prop instanceof IBeanMapper)
         ((IBeanMapper) prop).setPropertyValue(obj, val);
      else if (prop instanceof String)
         DynUtil.setProperty(obj, (String) prop, val);
      else
         throw new UnsupportedOperationException();
   }

   public static void setIndexedProperty(Object obj, Object prop, int ix, Object val) {
      DynUtil.setIndexedProperty(obj, prop, ix, val);
      if (prop instanceof String)
         Bind.sendEvent(IListener.ARRAY_ELEMENT_CHANGED, obj, (String) prop, ix);
      else if (prop instanceof IBeanMapper)
         Bind.sendEvent(IListener.ARRAY_ELEMENT_CHANGED, obj, (IBeanMapper) prop, ix);
      else
         throw new UnsupportedOperationException();
   }

   public static void addBindingListener(Object obj, Object prop, IListener listener, int eventMask) {
      if (prop instanceof IBinding)
         ((IBinding)prop).addBindingListener(obj, listener, eventMask);
      else
         Bind.addListener(obj, prop, listener, eventMask);
   }

   public static void removeBindingListener(Object obj, Object prop, IListener listener, int eventMask) {
      if (prop instanceof IBinding)
         ((IBinding)prop).removeBindingListener(obj, listener, eventMask);
      else
         Bind.removeListener(obj, prop, listener, eventMask);
   }

   public static Object getBindings(Object obj) {
      return Bind.getBindings(obj);
   }

   /*
   public static void setBindings(Object obj, Object bindings) {
      Bind.setBindings(obj, bindings);
   }
   */

   public static boolean equalProps(Object obj1, Object obj2) {
      if (obj1 instanceof String) {
         if (obj2 instanceof String)
            return obj1.equals(obj2);
         else if (obj2 instanceof IBeanMapper)
            return obj1.equals(((IBeanMapper) obj2).getPropertyName());
         else if (obj2 == null)
            return false;
         else
            return false;
      }
      else if (obj1 instanceof IBeanMapper) {
         if (obj2 instanceof IBeanMapper)
            return obj1.equals(obj2);
         else if (obj2 instanceof String)
            return ((IBeanMapper) obj1).getPropertyName().equals(obj2);
         else if (obj2 == null)
            return false;
         else
            return false;
      }
      else if (obj1 == null)
         return obj1 == obj2;
      else
         return false;
   }

   public static void sendEvent(int event, Object obj, Object prop, Object detail) {
      if (prop instanceof String)
         Bind.sendEvent(event, obj, (String) prop, detail);
      else
         Bind.sendEvent(event, obj, (IBeanMapper) prop, detail);
   }

   public static void removeStatefulListener(Object obj) {
      if (obj instanceof IBinding)
         ((IBinding) obj).removeListener();
   }

   public static String getPropertyName(Object prop) {
      if (prop instanceof String)
         return (String) prop;
      else
         return ((IBeanMapper) prop).getPropertyName();
   }

   public static void printAllBindings() {
       Bind.printAllBindings();
   }

}
