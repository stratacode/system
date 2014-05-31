/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.dyn.DynUtil;
import sc.dyn.IDynObject;
import sc.type.IBeanMapper;
import sc.type.AbstractBeanMapper;
import sc.type.TypeUtil;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** This gets created for any property which is implemented by a dynamic field or dynamic get/set methods */
public class DynBeanMapper extends AbstractBeanMapper {
   Object field, getSelector, setSelector, ownerType;

   private final static int GET_IS_DYN = 2;
   private final static int SET_IS_DYN = 4;
   private final static int SET_IS_FIELD = 8;
   private final static int GET_IS_FIELD = 16;
   private final static int IS_STATIC = 32;
   private final static int IS_CONSTANT = 64;

   int attMask;
   // TODO: prob could keep only one of these since we use atts but keeping right now for consistency with BeanMapper
   public int instPosition = -1;
   public int staticPosition = -1;


   public DynBeanMapper() {
   }

   public DynBeanMapper(Object field, Object getSelector, Object setSelector) {
      this.setField(field);
      this.setGetSelector(getSelector);
      this.setSetSelector(setSelector);
   }

   /** Used for creating a dynamic object property */
   public DynBeanMapper(BodyTypeDeclaration innerObj) {
      if (innerObj.isStaticInnerClass())
         attMask = IS_STATIC | GET_IS_DYN | GET_IS_FIELD;
      else
         attMask = GET_IS_DYN | GET_IS_FIELD;
      field = getSelector = innerObj;
      setSelector = null;
   }

   public DynBeanMapper(IBeanMapper base) {
      setGetSelector(base.getGetSelector());
      setSetSelector(base.getSetSelector());
      field = base.getField();
      instPosition = base.getPropertyPosition();
      staticPosition = base.getStaticPropertyPosition();
   }

   public void setPropertyValue(Object parent, Object value) {
      try {
         if ((attMask & IS_STATIC) == 0) {
            if ((attMask & SET_IS_FIELD) != 0) {
               if ((attMask & SET_IS_DYN) != 0) {
                  IDynObject dynObj = (IDynObject) parent;
                  dynObj.setProperty(getPropertyName(), value, true); // TODO: should cache the offset someplace to avoid the proeprty name/lookup
               }
               else
                  ((Field) setSelector).set(parent, value);
            }
            else {
               if ((attMask & SET_IS_DYN) != 0)
                  ((AbstractMethodDefinition) setSelector).callVirtual(parent, value);
               else {
                  ((Method) setSelector).invoke(parent, value);
               }
            }
         }
         else  {
            if ((attMask & SET_IS_FIELD) != 0) {
               if ((attMask & SET_IS_DYN) != 0) {
                  // TODO: need to deal with class here?
                  ((BodyTypeDeclaration)ownerType).setDynStaticField(getPropertyName(), value);
               }
               else {
                  ((Field) setSelector).set(null, value);
               }
            }
            else {
               if ((attMask & SET_IS_DYN) != 0) {
                  ((AbstractMethodDefinition) setSelector).callStatic(ownerType, value);
               }
               else {
                  ((Method) setSelector).invoke(null, value);
               }
            }
         }
      }
      catch (IllegalArgumentException exc) {
         System.err.println("*** Error setting: " + setSelector + " on: " + parent + " value: " + value + " detailed error: " + exc);
         exc.printStackTrace();
      }
      catch (InvocationTargetException ite) {
         System.err.println("*** Error setting: " + setSelector + " on: " + parent + " value: " + value + " threw: " + ite);
         ite.printStackTrace();
      }
      catch (IllegalAccessException exc) {
         System.err.println("*** Error setting: " + setSelector + " on: " + parent + " value: " + value + " threw: " + exc);
      }
      catch (NullPointerException exc) {
         if (TypeUtil.trace)
            System.err.println("*** Error setting: " + setSelector + " on: " + parent + " value: " + value + " threw: " + exc);
         throw exc;
      }
   }

   public Object getPropertyValue(Object parent) {
      try {
         if ((attMask & IS_STATIC) == 0) {
            if ((attMask & GET_IS_FIELD) != 0) {
               // TODO: should cache the offset someplace to avoid the proeprty name/lookup
               if ((attMask & GET_IS_DYN) != 0) {
                  if (!(parent instanceof IDynObject))
                     System.out.println("*** not a dynamic object");
                  IDynObject dynObj = (IDynObject) parent;
                  return dynObj.getProperty(getPropertyName());
               }
               else
                  return ((Field) getSelector).get(parent);
            }
            else {
               if ((attMask & GET_IS_DYN) != 0)
                  return ((AbstractMethodDefinition) getSelector).callVirtual(parent);
               else {
                  return ((Method) getSelector).invoke(parent);
               }
            }
         }
         else {
            if ((attMask & GET_IS_FIELD) != 0) {
               if ((attMask & GET_IS_DYN) != 0) {
                  // TODO: need to deal with class here?
                  return ((BodyTypeDeclaration)ownerType).getDynStaticField(getPropertyName());
               }
               else
                  return ((Field) getSelector).get(null);
            }
            else {
               if ((attMask & GET_IS_DYN) != 0) {
                  return ((AbstractMethodDefinition) getSelector).callStatic(ownerType);
               }
               else {
                  return ((Method) getSelector).invoke(null);
               }
            }
         }
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

   public String getPropertyName() {
      return ModelUtil.getPropertyName(getSelector != null ? getSelector : (setSelector != null ? setSelector : null));
   }

   public Object getPropertyType() {
      if (getSelector != null)
         return ModelUtil.getVariableTypeDeclaration(getSelector);
      else
         return ModelUtil.getSetMethodPropertyType(setSelector);
   }

   public Object getField() {
      return field;
   }

   public boolean hasAccessorMethod() {
      return (attMask & GET_IS_FIELD) == 0;
   }

   public boolean hasSetterMethod() {
      return (attMask & SET_IS_FIELD) == 0;
   }

   public boolean isPropertyIs() {
      return ((attMask & GET_IS_FIELD) == 0) && ModelUtil.getMethodName(getSelector).startsWith("is");
   }

   public Object getPropertyMember() {
      if (getSelector != null) {
         return getSelector;
      }
      return setSelector;
   }

   public int getPropertyPosition() {
      return instPosition;  //To change body of implemented methods use File | Settings | File Templates.
   }

   public int getPropertyPosition(Object obj) {
      if (instPosition == IBeanMapper.DYNAMIC_LOOKUP_POSITION) {
         IBeanMapper instMapper = DynUtil.getPropertyMapping(DynUtil.getType(obj), getPropertyName());
         if (instMapper == null) {
            System.err.println("*** Failed to find mapping in obj property for interface");
            return -1;
         }
         else
            return instMapper.getPropertyPosition();
      }
      return instPosition;
   }

   public int getStaticPropertyPosition() {
      return staticPosition;  //To change body of implemented methods use File | Settings | File Templates.
   }

   public Object getGenericType() {
      if (getSelector != null) {
         if ((attMask & GET_IS_DYN) != 0)
            return getSelector; // dynamic type descriptors have param info already
         else {
            if (getSelector instanceof Field)
               return ((Field) getSelector).getGenericType();
            else if (getSelector instanceof Method)
               return ((Method) getSelector).getGenericReturnType();
         }
      }
      else if (setSelector != null) {
         if ((attMask & SET_IS_DYN) != 0)
            return setSelector;
         else
            return ((Method)setSelector).getGenericParameterTypes()[0];
      }
      return null;
   }

   public String getGenericTypeName(Object resultType, boolean includeDims) {
      return ModelUtil.getGenericTypeName(resultType, getGenericType(), includeDims);
   }

   public Object getGetSelector() {
      return getSelector;
   }

   public Object getSetSelector() {
      return setSelector;
   }

   public void setGetSelector(Object gs) {
      boolean isDyn;
      if (gs instanceof MethodDefinition) {
         isDyn = ModelUtil.isDynamicType(gs);
         if (!isDyn) {
            Object rm =  ((MethodDefinition) gs).getRuntimeMethod();
            if (rm != null)
               gs = rm;
         }
      }
      else if (gs instanceof VariableDefinition) {
         isDyn = ModelUtil.isDynamicType(gs);
         if (!isDyn) {
            Object rf = ((VariableDefinition) gs).getRuntimeField();
            if (rf != null)
               gs = rf;
         }
      }
      else
         isDyn = false;
      boolean isStatic = gs != null && ModelUtil.hasModifier(gs, "static");
      attMask = (attMask & ~(GET_IS_FIELD | GET_IS_DYN)) |
              (isDyn ? GET_IS_DYN : 0) |
              (isStatic ? IS_STATIC : 0) |
              (ModelUtil.isField(gs) ? GET_IS_FIELD : 0);
      getSelector = gs;
   }

   public void setSetSelector(Object ss) {
      boolean isDyn;
      if (ss instanceof MethodDefinition) {
         isDyn = ModelUtil.isDynamicType(ss);
         if (!isDyn) {
            Object rm = ((MethodDefinition) ss).getRuntimeMethod();
            if (rm != null) // Interface methods won't have a runtime thing
               ss = rm;
         }
      }
      else if (ss instanceof VariableDefinition) {
         isDyn = ModelUtil.isDynamicType(ss);
         if (!isDyn) {
            Object rf = ((VariableDefinition) ss).getRuntimeField();
            if (rf != null)
               ss = rf;
         }
      }
      else if (ss instanceof BodyTypeDeclaration)
         isDyn = true;
      else
         isDyn = false;
      boolean isStatic = ss != null && ModelUtil.hasModifier(ss, "static");
      attMask = (attMask & ~(SET_IS_FIELD | SET_IS_DYN)) |
              (isDyn ? SET_IS_DYN : 0) |
              (isStatic ? IS_STATIC : 0) |
              (ModelUtil.isField(ss) ? SET_IS_FIELD : 0);
      setSelector = ss;
   }

   public void setField(Object ss) {
      boolean isDyn;
      if (ss instanceof VariableDefinition) {
         isDyn = ModelUtil.isDynamicType(ss);
         if (!isDyn) {
            Object rf = ((VariableDefinition) ss).getRuntimeField();
            if (rf != null) // Interface fields are not dynamic but won't have a runtime description - just use the VarDef.
               ss = rf;
         }
      }
      else
         isDyn = false;
      attMask = (attMask & ~(SET_IS_DYN)) | (isDyn ? SET_IS_DYN : 0);
      setSelector = ss;
   }

   /**
    * Called before the apply reverse binding.  Here we need to do Number type conversion since there
    * is no way to specify this cast statically.  The value passed to applyReverse binding will be this
    * value.  To prevent unnecessary firings, we need to save the converted value in the parent.
    */
   public Object performCast(Object val) {
      return ModelUtil.evalCast(getPropertyType(), val);
   }

   /** Explicitly marked properties final fields and object types are constant */
   public boolean isConstant() {
      return ((attMask & IS_CONSTANT) != 0) || (setSelector == field && (field != null && ModelUtil.hasModifier(field, "final"))) || field instanceof BodyTypeDeclaration;
   }

   public boolean isWritable() {
      // For Object types in the dynamic type system the field is really the type declaration.  We can't change it with a set property value
      // so need a way in the code to avoid that.
      return super.isWritable() && !(field instanceof ITypeDeclaration);
   }

   public void setConstant(boolean val) {
      if (val)
         attMask |= IS_CONSTANT;
      else
         attMask &= ~IS_CONSTANT;
   }

   public Object getOwnerType() {
      return ownerType;
   }

   public DynBeanMapper clone() {
      try {
         return (DynBeanMapper) super.clone();
      }
      catch (CloneNotSupportedException exc) {}
      return null;
   }

}
