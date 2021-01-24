/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.type;

import sc.dyn.DynUtil;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

public class BeanIndexMapper extends BeanMapper implements IBeanIndexMapper {
   Method setIndexMethod;
   Method getIndexMethod;

   public BeanIndexMapper() {
   }

   public BeanIndexMapper(BeanMapper orig) {
      super(orig);
   }

   public boolean hasIndexedAccessorMethod() {
      return getIndexMethod != null;
   }

   public Object getIndexedGetSelector() {
      return getIndexMethod;
   }

   public Object getIndexedSetSelector() {
      return setIndexMethod;
   }

   public boolean hasIndexedSetterMethod() {
      return setIndexMethod != null;
   }

   public void setIndexPropertyValue(Object parent, int index, Object value) {
      if (setIndexMethod == null)
         DynUtil.setArrayElement(getPropertyValue(parent, false, false), index, value);
      try {
         setIndexMethod.invoke(parent, index, value);
      }
      catch (InvocationTargetException ite) {
         System.err.println("*** Error setting: " + setIndexMethod + " on: " + parent + " threw: " + ite);
         ite.printStackTrace();
      }
      catch (IllegalArgumentException exc) {
         System.err.println("*** Error setting: " + setIndexMethod + " on: " + parent + " threw: " + exc);
         exc.printStackTrace();
      }
      catch (IllegalAccessException exc) {
         System.err.println("*** Error setting: " + setIndexMethod + " on: " + parent + " threw: " + exc);
      }
   }

   public Object getIndexPropertyValue(Object parent, int index) {
      if (getIndexMethod == null)
         return DynUtil.getArrayElement(getPropertyValue(parent, false, false), index);
      try {
         return getIndexMethod.invoke(parent, index);
      }
      catch (InvocationTargetException ite) {
         System.err.println("*** Error getting: " + getIndexMethod + " on: " + parent + " threw: " + ite);
         ite.printStackTrace();
      }
      catch (IllegalArgumentException exc) {
         System.err.println("*** Error getting: " + getIndexMethod + " on: " + parent + " threw: " + exc);
         exc.printStackTrace();
      }
      catch (IllegalAccessException exc) {
         System.err.println("*** Error getting: " + getIndexMethod + " on: " + parent + " threw: " + exc);
      }
      return null;
   }

   public Class getPropertyType() {
      if (getSelector != null || setSelector != null)
         return super.getPropertyType();
      Class elementType;
      if (getIndexMethod != null)
         elementType = getIndexMethod.getReturnType();
      else if (setIndexMethod != null)
         elementType = setIndexMethod.getParameterTypes()[1];
      else
         return null;
      return Type.get(elementType).getArrayClass(elementType, 1);
   }

   public java.lang.reflect.Type getGenericType() {
      java.lang.reflect.Type gtype = super.getGenericType();
      if (gtype != null)
         return gtype;
      if (getIndexMethod != null)
         return getIndexMethod.getGenericReturnType();
      else if (setIndexMethod != null)
         return setIndexMethod.getGenericParameterTypes()[0];
      else
         throw new UnsupportedOperationException();
   }

   public String getPropertyName() {
      if (getSelector != null || setSelector != null)
         return super.getPropertyName();
      return getIndexMethod != null ? RTypeUtil.getPropertyNameFromSelector(getIndexMethod) : RTypeUtil.getPropertyNameFromSelector(setIndexMethod);
   }

   public Member getPropertyMember() {
      Member regMember = super.getPropertyMember();
      if (regMember != null)
         return regMember;
      if (getIndexMethod != null) {
         return getIndexMethod;
      }
      if (setIndexMethod != null) {
         return setIndexMethod;
      }
      return null;
   }
}
