/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.type;

import sc.dyn.DynUtil;
import sc.dyn.IDynObject;

import java.lang.reflect.*;
import java.lang.reflect.Modifier;

public class BeanMapper extends AbstractBeanMapper {
   /** if the bean has both get/set and fields, this is set to the field */
   public Field field;

   // TODO: performance - keep track of the type of each of up front these so we can just call "invoke" directly
   // without the instance of test in TypeUtil.setPropertyValue each time.
   protected Object getSelector, setSelector;

   // TODO: performance: use bit masks and only store one position?
   public int instPosition = -1;
   public int staticPosition = -1;
   public boolean getIsField;
   public boolean setIsField;
   // Metadata can be used to mark the property as a constant
   public boolean constant = false;
   public boolean isPrimitive = false;

   // Set for static properties.  The static position is defined only when you retrieve the static properties from
   // the owner type.  In Java, you technically inherit static properties but each type does not get their own copy.
   public Object ownerType;

   public BeanMapper() {
   }

   public BeanMapper(BeanMapper base) {
      setGetSelector(base.getSelector);
      setSetSelector(base.setSelector);
      field = base.field;
      instPosition = base.instPosition;
      staticPosition = base.staticPosition;
   }

   public BeanMapper(Object get, Object set, Field fld) {
      setGetSelector(get);
      setSetSelector(set);
      field = fld;
   }


   public Object getPropertyValue(Object parent, boolean getField, boolean pendingChild) {
      if (parent == null && staticPosition == -1)
         throw new IllegalArgumentException("Attempt to get instance property: " + this + " without object");
      try {
         if (getField && field != null)
            return field.get(parent);

         if (getSelector == null)
            throw new IllegalArgumentException("Attempt to get value of write only property: " + getPropertyName() + " on value: " + parent);

         if (getIsField)
            return ((Field) getSelector).get(parent);
         else
            return ((Method) getSelector).invoke(parent);
      }
      catch (InvocationTargetException ite) {
         System.err.println("*** Error getting: " + getSelector + " on: " + parent + " threw: " + ite);
         ite.printStackTrace();
      }
      catch (IllegalArgumentException exc) {
         System.err.println("*** Error getting: " + getSelector + " on: " + parent + " threw: " + exc);
         exc.printStackTrace();
      }
      catch (IllegalAccessException exc) {
         System.err.println("*** Error getting: " + getSelector + " on: " + parent + " threw: " + exc);
      }
      catch (NullPointerException exc) {
         if (TypeUtil.trace)
            System.err.println("*** Error getting: " + getSelector + " on: " + parent + " threw: " + exc);
         throw exc;
      }
      return null;
   }

   public void setPropertyValue(Object parent, Object value) {
      // For primitive types, if you try to set the value to null, we need to convert this automatically to the
      // default wrapper type.
      if (isPrimitive && value == null) {
         value = sc.type.Type.get(getPropertyType()).getDefaultObjectValue();
      }
      if (setSelector == null) {
         throw new IllegalArgumentException("Attempt to modify a read-only property: " + this);
      }
      try {
         if (setIsField)
            ((Field) setSelector).set(parent, value);
         else {
            if (!(setSelector instanceof Method)) {
               System.out.println(" Internal error trying to set property value: " + this + " setSelector is not a method: " + setSelector);
            }
            else {
               try {
                  ((Method) setSelector).invoke(parent, value);
               }
               catch (NullPointerException exc) {
                  System.err.println("****" + exc);
                  exc.printStackTrace();
               }
            }
         }
      }
      catch (IllegalArgumentException exc) {
         System.err.println("*** Error setting: " + setSelector + " on: " + parent + " value: " + value + " detailed error: " + exc);
         exc.printStackTrace();
      }
      catch (InvocationTargetException ite) {
         if (TypeUtil.trace || !(ite.getCause() instanceof RuntimeException)) {
            System.err.println("*** Exception invoking setter: " + setSelector + " on: " + parent + " value: " + value + " threw: " + ite.getCause());
            ite.getCause().printStackTrace();
         }

         if (ite.getCause() instanceof RuntimeException)
            throw (RuntimeException) ite.getCause();
      }
      catch (IllegalAccessException exc) {
         System.err.println("*** Error setting: " + setSelector + " on: " + parent + " value: " + value + " threw: " + exc);
      }
      catch (NullPointerException exc) {
         if (TypeUtil.trace)
            System.err.println("*** Error setting: " + setSelector + " on: " + parent + " value: " + value + " threw: " + exc);
         throw exc;
      }
      catch (ClassCastException exc) {
         if (TypeUtil.trace)
            System.err.println("*** Error setting: " + setSelector + " on: " + parent + " value: " + value + " threw: " + exc);
         throw exc;
      }
   }

   public Field getField() {
      return field;
   }

   public boolean hasAccessorMethod() {
      return getSelector instanceof Method;
   }

   public void setGetSelector(Object gs) {
      getSelector = gs;
      getIsField = getSelector instanceof Field;
      if (getSelector != null && !getIsField && !(getSelector instanceof Method))
         System.err.println("*** Unknown getselector type!");
   }

   public Object getGetSelector() {
      return getSelector;
   }

   public Object getSetSelector() {
      return setSelector;
   }

   public void setSetSelector(Object gs) {
      setSelector = gs;
      setIsField = setSelector instanceof Field;
      if (setSelector != null && !setIsField && !(setSelector instanceof Method))
         System.err.println("*** Unknown setselector type!");
      if (gs != null) {
         isPrimitive = PTypeUtil.isPrimitive(setIsField ? ((Field) gs).getType() : ((Method) gs).getParameterTypes()[0]);
      }
   }

   public boolean isPropertyIs() {
      return getSelector instanceof Method ?
              (((Method) getSelector).getName()).startsWith("is") ? true : false : false;
   }

   public boolean hasSetterMethod() {
      return setSelector instanceof Method;
   }

   public String getPropertyName() {
      return RTypeUtil.getPropertyNameFromSelector(getSelector != null ? getSelector : setSelector);
   }

   public Class getPropertyType() {
      if (getSelector instanceof Field)
         return ((Field) getSelector).getType();
      if (getSelector instanceof Method)
         return ((Method) getSelector).getReturnType();
      // Last choice
      return ((Method) setSelector).getParameterTypes()[0];
   }

   public java.lang.reflect.Type getGenericType() {
      if (getSelector instanceof Field)
         return ((Field) getSelector).getGenericType();
      else if (getSelector instanceof Method)
         return ((Method) getSelector).getGenericReturnType();
      else if (setSelector instanceof Method)
         return ((Method)setSelector).getGenericParameterTypes()[0];
      return null;
   }

   public String getGenericTypeName(Object resultType, boolean includeDims) {
      return RTypeUtil.genericTypeToTypeName(getGenericType(), includeDims);
   }

   public Member getPropertyMember() {
      if (getSelector != null) {
         if (getSelector instanceof Member)
            return (Member) getSelector;
         else if (getSelector instanceof BeanMapper)
            return ((BeanMapper) getSelector).getPropertyMember();
      }
      if (setSelector != null) {
         if (setSelector instanceof Member)
            return (Member) setSelector;
         else if (setSelector instanceof BeanMapper)
            return ((BeanMapper) setSelector).getPropertyMember();
      }
      return null;
   }

   public Object getPropertySetter() {
      return setSelector;
   }

   public int getPropertyPosition() {
      return instPosition;
   }

   public int getPropertyPosition(Object obj) {
      if (instPosition == DYNAMIC_LOOKUP_POSITION) {
         // We can have a compiled interface which is implemented by a dynamic type
         IBeanMapper instMapper = obj instanceof IDynObject ? DynUtil.getPropertyMapping(((IDynObject) obj).getDynType(), getPropertyName()) :
                                                              TypeUtil.getPropertyMapping(obj.getClass(), getPropertyName());
         if (instMapper == null) {
            System.err.println("*** Failed to find mapping in obj class for interface");
            return -1;
         }
         else
            return instMapper.getPropertyPosition();
      }
      return instPosition;
   }

   public int getStaticPropertyPosition() {
      return staticPosition;
   }

   /**
    * Called before the apply reverse binding.  Here we need to do Number type conversion since there
    * is no way to specify this cast statically.  The value passed to applyReverse binding will be this
    * value.  To prevent unnecessary firings, we need to save the converted value in the parent.
    */
   public Object performCast(Object val) {
      return DynUtil.evalCast(getPropertyType(), val);
   }

   public boolean isConstant() {
      // TODO: maybe an annotation so we can detect the case you want a get/set method to be
      // considered a constant.  For now, any get/set means it is not constant.  Just a final field
      // is considered constant.
      return constant || (setSelector == field && (field != null && Modifier.isFinal(field.getModifiers())));
   }

   public void setConstant(boolean val) {
      constant = val;
   }

   public boolean isScalarToList() {
      return false;
   }

   /** Returns the type of the first type argument or java.lang.Object.class if none */
   public Class getComponentType() {
      java.lang.reflect.Type mem = getGenericType();

      if (mem instanceof ParameterizedType) {
         ParameterizedType pt = (ParameterizedType) mem;
         java.lang.reflect.Type[] args = pt.getActualTypeArguments();
         if (args.length == 1 && args[0] instanceof Class) {
            return (Class) args[0];
         }
      }
      return Object.class;
   }

   public BeanMapper clone() {
      try {
         return (BeanMapper) super.clone();
      }
      catch (CloneNotSupportedException exc) {}
      return null;
   }

   public Object getOwnerType() {
      return ownerType;
   }

}
