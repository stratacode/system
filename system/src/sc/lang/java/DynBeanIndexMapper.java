/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.Bind;
import sc.bind.IListener;
import sc.dyn.DynUtil;
import sc.type.Type;
import sc.type.IBeanIndexMapper;
import sc.type.IBeanMapper;

public class DynBeanIndexMapper extends DynBeanMapper implements IBeanIndexMapper {
   Object setIndexMethod;
   Object getIndexMethod;

   public DynBeanIndexMapper() {
   }

   public DynBeanIndexMapper(IBeanMapper orig) {
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
         DynUtil.setArrayElement(getPropertyValue(parent, false), index, value);
      else {
         try {
            ModelUtil.callMethod(parent, setIndexMethod, index, value);
         }
         catch (IllegalArgumentException exc) {
            System.err.println("*** Error setting indexed property with: " + setIndexMethod + " on: " + parent + " threw: " + exc);
            exc.printStackTrace();
         }
      }
      // Dynamic properties always send the change events
      Bind.sendEvent(IListener.ARRAY_ELEMENT_CHANGED, parent, this, index);
   }

   public Object getIndexPropertyValue(Object parent, int index) {
      if (getIndexMethod == null)
         return DynUtil.getArrayElement(getPropertyValue(parent, false), index);
      try {
         return ModelUtil.callMethod(parent, getIndexMethod, index);
      }
      catch (IllegalArgumentException exc) {
         System.err.println("*** Error getting indexed property with: " + getIndexMethod + " on: " + parent + " threw: " + exc);
         exc.printStackTrace();
      }
      return null;
   }

   public Object getPropertyType() {
      if (getSelector != null || setSelector != null)
         return super.getPropertyType();
      Object elementType;
      if (getIndexMethod != null)
         elementType = ModelUtil.getReturnType(getIndexMethod, true);
      else if (setIndexMethod != null)
         elementType = ModelUtil.getParameterTypes(setIndexMethod)[1];
      else
         return null;
      if (elementType instanceof Class) {
         Class elClass = (Class) elementType;
         return Type.get(elClass).getArrayClass(elClass, 1);
      }
      else
         return ArrayTypeDeclaration.create(null, elementType, 1, getEnclosingType());
   }

   private ITypeDeclaration getEnclosingType() {
      if (getIndexMethod instanceof MethodDefinition)
         return ((MethodDefinition) getIndexMethod).getEnclosingType();
      else if (setIndexMethod instanceof MethodDefinition)
         return ((MethodDefinition) getIndexMethod).getEnclosingType();
      else
         return null;
   }

   public String getPropertyName() {
      if (getSelector != null || setSelector != null)
         return super.getPropertyName();
      return getIndexMethod != null ? ModelUtil.getPropertyName(getIndexMethod) : ModelUtil.getPropertyName(setIndexMethod);
   }
}
