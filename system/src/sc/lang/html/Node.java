/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.dyn.IDynObject;
import sc.lang.DynObject;
import sc.lang.INamedNode;
import sc.lang.ISrcStatement;
import sc.lang.IUserDataNode;
import sc.lang.java.JavaSemanticNode;
import sc.parser.Language;
import sc.type.TypeUtil;

import java.util.List;

/**
 * Node extends the IDynObject interface and optionally have a "dynObj" instance for dynamic behavior.
 * This is an optimization to avoid the need for dynamic stubs to interpret template pages
 */
public abstract class Node extends JavaSemanticNode implements IDynObject, ISrcStatement, INamedNode, IUserDataNode {
   protected sc.lang.DynObject dynObj;
   public transient Object[] errorArgs;

   transient Object userData = null;  // A hook for user data - specifically for an IDE to store its instance for this node

   public Node() {
   }
   public Node(sc.lang.java.TypeDeclaration concreteType)  {
      dynObj = new sc.lang.DynObject(concreteType);
   }

   public boolean hasDynObject() {
      return dynObj != null;
   }

   public Object getProperty(String propName, boolean getField) {
      if (dynObj == null) {
         return TypeUtil.getPropertyValueFromName(this, propName, getField);
      }
      return dynObj.getPropertyFromWrapper(this, propName, getField);
   }
   public Object getProperty(int propIndex, boolean getField) {
      if (dynObj == null)
         return null;
      return dynObj.getPropertyFromWrapper(this, propIndex, getField);
   }

   // TODO: fix this name conflict.  Probably the setProperty and getProperty in semanticNode and/or the dynObject should change to be something else.  Both of them are mixins to other object name spaces so should be named to avoid conflicts?
   public void setSemanticProperty(Object selector, Object value) {
      super.setProperty(selector, value);
   }

   public void setProperty(String propName, Object value, boolean setField) {
      if (dynObj == null)
         TypeUtil.setPropertyFromName(this, propName, value);
      else {
         dynObj.setPropertyFromWrapper(this, propName, value, setField);
      }
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
      if (dynObj != null)
         dynObj.setTypeFromWrapper(this, typeObj);
   }
   public <_TPROP> _TPROP getTypedProperty(String propName, Class<_TPROP> propType) {
      if (dynObj == null)
         return null;
      return (_TPROP) dynObj.getPropertyFromWrapper(this, propName, false);
   }
   public void addProperty(Object propType, String propName, Object initValue) {
      dynObj.addProperty(propType, propName, initValue);
   }

   public ISrcStatement getSrcStatement(Language lang) {
      return this; // TODO: may need this if we are ever generating elements or attributes from code and so need to propagate source location
   }

   public ISrcStatement findFromStatement(ISrcStatement st) {
      // If the src statement has a part of our origin statement we are a match.
      if (st.getNodeContainsPart(this))
         return this;
      return null;
   }

   public void addBreakpointNodes(List<ISrcStatement> result, ISrcStatement st) {
      // TODO: generating elements
   }

   public ISrcStatement getFromStatement() {
      return null;
   }

   public void setUserData(Object v)  {
      userData = v;
   }

   public Object getUserData() {
      return userData;
   }

   public String getNodeErrorText() {
      if (errorArgs != null) {
         StringBuilder sb = new StringBuilder();
         for (Object arg:errorArgs)
            sb.append(arg.toString());
         sb.append(this.toString());
         return sb.toString();
      }
      return null;
   }

   public void displayError(String...args) {
      if (errorArgs == null) {
         super.displayError(args);
         errorArgs = args;
      }
   }

   public boolean displayTypeError(String...args) {
      if (errorArgs == null) {
         errorArgs = args;
         return super.displayTypeError(args);
      }
      return false;
   }


   public void stop() {
      super.stop();
      errorArgs = null;
   }
}
