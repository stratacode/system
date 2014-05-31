/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.dyn.IDynObject;
import sc.lang.DynObject;
import sc.lang.java.JavaSemanticNode;
import sc.type.TypeUtil;

/** Nodes extend IDynObject to avoid the need for dybamic stubs to interpret template pages */
public abstract class Node extends JavaSemanticNode implements IDynObject {
   protected sc.lang.DynObject dynObj;
   public Node() {
   }
   public Node(sc.lang.java.TypeDeclaration concreteType)  {
      dynObj = new sc.lang.DynObject(concreteType);
   }

   public boolean hasDynObject() {
      return dynObj != null;
   }

   public Object getProperty(String propName) {
      if (dynObj == null) {
         return TypeUtil.getPropertyValueFromName(this, propName);
      }
      return dynObj.getPropertyFromWrapper(this, propName);
   }
   public Object getProperty(int propIndex) {
      if (dynObj == null)
         return null;
      return dynObj.getPropertyFromWrapper(this, propIndex);
   }

   // TODO: fix this name conflict.  Probably the setProperty and getProperty in semanticNode and/or the dynObject should change to be something else.  Both of them are mixins to other object name spaces so should be named to avoid conflicts?
   public void setSemanticProperty(Object selector, Object value) {
      super.setProperty(selector, value);
   }

   public void setProperty(String propName, Object value, boolean setField) {
      if (dynObj == null)
         TypeUtil.setPropertyFromName(this, propName, value);
      else
         dynObj.setPropertyFromWrapper(this, propName, value, setField);
   }
   public void setProperty(int propIndex, Object value, boolean setField) {
      if (dynObj == null) {
         if (propIndex == DynObject.OUTER_INSTANCE_SLOT) {
            // In this case parentNode should equal value.  It happens when we create a compiled DOM node class via the
            // dynamic runtime.  In this case, the parent node has already been defined via the compiled runtime.
            return;
         }
         else
            throw new IllegalArgumentException("No dynamic property: " + propIndex);
      }
      dynObj.setProperty(propIndex, value, setField);
   }
   public Object invoke(String methodName, String paramSig, Object... args) {
      return dynObj.invokeFromWrapper(this, methodName, paramSig, args);
   }
   public Object invoke(int methodIndex, Object... args) {
      return dynObj.invokeFromWrapper(this, methodIndex, args);
   }
   public Object getDynType() {
      return dynObj == null ? getClass() : dynObj.getDynType();
   }
   public void setDynType(Object typeObj) {
      dynObj.setTypeFromWrapper(this, typeObj);
   }
   public <_TPROP> _TPROP getTypedProperty(String propName, Class<_TPROP> propType) {
      if (dynObj == null)
         return null;
      return (_TPROP) dynObj.getPropertyFromWrapper(this, propName);
   }
   public void addProperty(Object propType, String propName, Object initValue) {
      dynObj.addProperty(propType, propName, initValue);
   }
}
