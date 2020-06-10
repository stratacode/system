/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.type;

import sc.dyn.IDynObject;

public class CompBeanMapper extends AbstractBeanMapper {

   public DynType type;
   public String name;
   public int position;
   public boolean isStatic = false;
   boolean constant = false;

   public CompBeanMapper(DynType type, String name, int position, boolean isStatic, boolean isConstant) {
      this.type = type;
      this.name = name;
      this.position = position;
      this.isStatic = isStatic;
      this.constant = isConstant;
   }

   @Override
   public Object performCast(Object val) {
      return val;
   }

   @Override
   public boolean isConstant() {
      return constant;
   }

   public void setPropertyValue(Object obj, Object value) {
      if (obj instanceof IDynObject) {
         IDynObject dobj = (IDynObject) obj;
         if (!isStatic)
            dobj.setProperty(position, value, false);
         else {
            DynType type = (DynType) dobj.getDynType();
            type.setStaticProperty(position, value);
         }
      }
      else
         throw new UnsupportedOperationException();
   }

   public Object getPropertyValue(Object obj, boolean getField, boolean pendingChild) {
      if (obj instanceof IDynObject) {
         IDynObject dobj = (IDynObject) obj;
         if (!isStatic)
            return dobj.getProperty(position, getField);
         else {
            DynType type = (DynType) dobj.getDynType();
            return type.getStaticProperty(position);
         }
      }
      else
         throw new UnsupportedOperationException();
   }

   public String getPropertyName() {
      return name;
   }

   public Object getPropertyType() {
      throw new UnsupportedOperationException();
   }

   public Object getField() {
      return null;
   }

   public boolean hasAccessorMethod() {
      return true;
   }

   public boolean hasSetterMethod() {
      return true;
   }

   public boolean isPropertyIs() {
      return false;
   }

   public Object getPropertyMember() {
      throw new UnsupportedOperationException();
   }

   public int getPropertyPosition() {
      return isStatic ? -1 : position;
   }

   public int getPropertyPosition(Object obj) {
      return getPropertyPosition();
   }

   public int getStaticPropertyPosition() {
      return isStatic ? position : -1;
   }

   public Object getGenericType() {
      throw new UnsupportedOperationException();
   }

   public String getGenericTypeName(Object resultType, boolean includeDims) {
      throw new UnsupportedOperationException();
   }

   public Object getGetSelector() {
      throw new UnsupportedOperationException();
   }

   public Object getSetSelector() {
      throw new UnsupportedOperationException();
   }

   public Object getValidateMethod() {
      throw new UnsupportedOperationException();
   }

   public void setConstant(boolean val) {
      constant = val;
   }

   public Object getOwnerType() {
      return type; // TODO: need to deal with static inheritance here?
   }
}
