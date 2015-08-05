/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.type;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Member;

public class ArrayLengthBeanMapper extends BeanMapper implements Cloneable {

   // This field is not used - it is a dummy so we can return something from getMember which has the right
   // modifiers
   public int length;

   public static Field DUMMY_LENGTH_FIELD;
   static {
      try {
         DUMMY_LENGTH_FIELD = ArrayLengthBeanMapper.class.getField("length");
      }
      catch (NoSuchFieldException exc) {}
   }

   public static ArrayLengthBeanMapper INSTANCE = new ArrayLengthBeanMapper();

   public ArrayLengthBeanMapper() {
   }

   public Object getPropertyValue(Object parent) {
      return Array.getLength(parent);
   }

   public void setPropertyValue(Object parent, Object value) {
      throw new IllegalArgumentException("Array length is read-only");
   }

   public Field getField() {
      return DUMMY_LENGTH_FIELD;
   }

   public boolean hasAccessorMethod() {
      return false;
   }

   public boolean hasSetterMethod() {
      return false;
   }

   public String getPropertyName()
   {
      return "length";
   }

   public Class getPropertyType()
   {
      return Integer.TYPE;
   }

   public java.lang.reflect.Type getGenericType()
   {
      return Integer.TYPE;
   }

   public Member getPropertyMember() {
      return DUMMY_LENGTH_FIELD;
   }

   public int getPropertyPosition() {
      return 0;
   }

   public int getPropertyPosition(Object obj) {
      return 0;
   }

   public boolean equals(Object other) {
      if (other instanceof ArrayLengthBeanMapper)
         return true;
      return false;
   }

   public int hashCode() {
      return getPropertyName().hashCode();
   }

   public ArrayLengthBeanMapper clone() {
      return this;
   }
}
