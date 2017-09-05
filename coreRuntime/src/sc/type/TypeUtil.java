/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.type;

import sc.dyn.DynUtil;
import sc.dyn.IDynObject;
import sc.util.IntCoalescedHashMap;

import java.util.*;

/** Some static utilities for doing types.  These are split into 4 classes: TypeUtil, CTypeUtil (compiled to JS), PTypeUtil (ported to JS manually), and RTypeUtil - for dynamic stuff only.  This class runs on GWT. */
public class TypeUtil  {
   public static Integer ZERO = 0;

   public static boolean trace = false;
   public static boolean info = true;

   public static final Object[] EMPTY_ARRAY = new Object[0];   

   public static CharSequence getClassNameSeq(String dottedName) {
      int ix = dottedName.lastIndexOf(".");
      if (ix == -1)
         return dottedName;
      return dottedName.subSequence(ix+1, dottedName.length());
   }

   public static CharSequence getPackageNameSeq(String dottedName) {
      int ix = dottedName.lastIndexOf(".");
      if (ix == -1)
         return null;
      return dottedName.subSequence(0, ix);
   }


   public static Object resolveMethod(Class resultClass, String methodName, String paramSig) {
      return PTypeUtil.resolveMethod(resultClass, methodName, paramSig);
   }

   public static IBeanMapper resolvePropertyMapping(Class resultClass, String propName) {
      IBeanMapper mapper = PTypeUtil.getPropertyMapping(resultClass, propName);

      if (mapper == null) {
         System.err.println("Unable to resolve property: " + resultClass.getName() + "." + propName);
      }
      return mapper;
   }

   /** Use for comparing parameter types in methods to decide when one overrides the other */
   public static boolean isAssignableTypesFromOverride(Class from, Class to) {
      if (to == null)
         return true;
      while (from.isArray()) {
         if (!to.isArray())
            return false;
         from = from.getComponentType();
         to = to.getComponentType();
      }
      if (to.isArray())
         return false;
      Type fromType = Type.get(from);
      Type toType = Type.get(to);
      return fromType.isAssignableFromOverride(toType, from, to);
   }

   /** Used when determining if a value is compatible for a parameter slot */
   public static boolean isAssignableFromParameter(Class from, Class to) {
      if (to == null)
          return true;
      Type fromType = Type.get(from);
      Type toType = Type.get(to);
      return fromType.isAssignableFromParameter(toType, from, to);
   }

   /** Used when determining if a value is compatible for an assignment */
   public static boolean isAssignableFromAssignment(Class from, Class to) {
      if (to == null)
         return true;
      Type fromType = Type.get(from);
      Type toType = Type.get(to);
      return fromType.isAssignableFromAssignment(toType, from, to);
   }

   public static void setPropertyFromName(Object parent, String propName, Object value) {
      IBeanMapper mapper = getPropertyMapping(parent.getClass(), propName, null, null);
      if (mapper == null)
         throw new IllegalArgumentException("Unable to set property: " + propName + " on: " + DynUtil.getInstanceName(parent) + " property not found");
      TypeUtil.setProperty(parent, mapper, value);
   }

   public static void setProperty(Object parent, Object selector, Object value) {
      if (parent == null) {
         System.err.println("*** Error setting: " + selector + " on null parent: " + " value: " + value);
         return;
      }
      try {
         if (selector instanceof IBeanMapper)
            ((IBeanMapper) selector).setPropertyValue(parent, value);
         else if (selector instanceof String) {
            if (parent instanceof IDynObject)
               ((IDynObject) parent).setProperty((String) selector, value, false);
            else
               TypeUtil.setPropertyFromName(parent, (String) selector, value);
         }
         else
            PTypeUtil.setProperty(parent, selector, value);
      }
      catch (IllegalArgumentException exc) {
         System.err.println("*** Error setting: " + selector + " on: " + parent + " value: " + value + " detailed error: " + exc);
         exc.printStackTrace();
      }
   }

   public static void setDynamicProperty(Object object, String propertyName, Object value, boolean setField) {
      if (object instanceof IDynObject)
         ((IDynObject) object).setProperty(propertyName, value, setField);
      else if (object instanceof Map)
         ((Map) object).put(propertyName, value);
      else {
         IBeanMapper mapper = getPropertyMapping(object.getClass(), propertyName, value == null ? null : value.getClass(), null);
         if (mapper == null)
            throw new IllegalArgumentException("No property: " + propertyName + " for class: " + object.getClass());
         mapper.setPropertyValue(object, value);
      }
   }

   public static Object getDynamicProperty(Object object, String propertyName) {
      if (object instanceof Map)
         return ((Map) object).get(propertyName);
      else
         return getPropertyValue(object, propertyName);
   }

   public static DynType getPropertyCache(Class beanClass) {
      DynType cache = DynUtil.getPropertyCache(beanClass);
      if (cache == null)
         cache = PTypeUtil.initPropertyCache(beanClass);
      return cache;
   }

   public static int getPropertyCount(Object t) {
      return getPropertyCache(t.getClass()).propertyCount;
   }

   public static IBeanMapper[] getProperties(Class beanClass) {
      DynType cache = getPropertyCache(beanClass);
      return cache.getPropertyList();
   }

   public static IBeanMapper[] getStaticProperties(Class beanClass) {
      DynType cache = getPropertyCache(beanClass);
      return cache.getStaticPropertyList();
   }

   public static IBeanMapper[] getAllProperties(Class beanClass) {
      DynType cache = getPropertyCache(beanClass);

      IBeanMapper[] props = cache.getPropertyList();
      IBeanMapper[] staticProps = cache.getStaticPropertyList();
      if (staticProps == null || staticProps.length == 0)
         return props;
      if (props == null || props.length == 0)
         return staticProps;

      int len;
      ArrayList<IBeanMapper> res = new ArrayList<IBeanMapper>(len = props.length + staticProps.length);
      for (IBeanMapper p:props) {
         if (p != null)
            res.add(p);
         else
            len--;
      }
      for (IBeanMapper p:staticProps) {
         if (p != null)
            res.add(p);
         else
            len--;
      }
      return res.toArray(new IBeanMapper[len]);
   }

   public static IBeanMapper[] getProperties(Class beanClass, String modifier) {
      if (modifier != null && modifier.equals("static"))
         return getStaticProperties(beanClass);
      
      IBeanMapper[] props = getAllProperties(beanClass);
      if (modifier == null)
         return props;
      int ct = 0;
      for (int i = 0; i < props.length; i++) {
         if (PTypeUtil.hasModifier(props[i].getPropertyMember(), modifier))
            ct++;
      }
      if (ct == props.length)
         return props;
      if (ct == 0)
         return null;
      IBeanMapper[] newProps = new IBeanMapper[ct];
      ct = 0;
      for (int i = 0; i < props.length; i++) {
         if (PTypeUtil.hasModifier(props[i].getPropertyMember(), modifier))
            newProps[ct++] = props[i];
      }
      if (ct != newProps.length)
         System.err.println("*** Internal error retrieving properties for: " + beanClass);
      return newProps;
   }

   public static int getStaticPropertyCount(Class t) {
      return getPropertyCache(t).staticPropertyCount;
   }

   /**
    * This is for use in binding generated code when it knows it is dealing with a constant property.  Right now, we do not look
    * for the Constant annotation at runtime due to performance cost... instead, it is done at compile time by calling these two methods.
    * At some point we should have a method which does the lookup at init time and call that when we do not do it at compile time
    */
   public static IBeanMapper getConstantPropertyMapping(Class beanClass, String propName) {
      IBeanMapper mapper = PTypeUtil.getPropertyMapping(beanClass, propName);
      if (mapper == null)
         return null;
      mapper.setConstant(true);
      return mapper;
   }

   public static IBeanMapper getPropertyMapping(Class beanClass, String propName, Class valueClass, Class componentClass) {
      IBeanMapper mapper = PTypeUtil.getPropertyMapping(beanClass, propName);
      if (mapper == null)
         return null;

      mapper = PTypeUtil.getPropertyMappingConverter(beanClass, propName, mapper, valueClass, componentClass);
      return mapper;
   }

   public static Object getPropertyOrStaticValue(Object parent, Object mapping) {
      if (parent instanceof Class)
         return getStaticValue((Class) parent, mapping);
      else
         return getPropertyValue(parent, mapping);
   }

   public static void setPropertyOrStaticValue(Object parent, Object mapping, Object value) {
      if (parent instanceof Class)
         setStaticValue((Class) parent, mapping, value);
      else
         setProperty(parent, mapping, value);
   }

   /**
    * Unfortunately during name resolution, there are times we need to test if a property exists.  This may mean
    * trying to access a real property without an object etc.  In these cases we swallow the exception.   If this
    * becomes a performance issue, we just need to add a variant to not throw the exception.
    */
   public static Object getPossibleStaticValue(Class parent, Object mapping) {
      try {
         if (mapping instanceof String) {
            Object newMapping = getPropertyMapping(parent, (String) mapping, null, null);
            if (newMapping == null)
               return null;
         }
         return getStaticValue(parent, mapping);
      }
      catch (IllegalArgumentException exc) {}
      return null;
   }

   public static Object getStaticValue(Class parent, Object mapping) {
      if (mapping instanceof String) {
         Object newMapping = getPropertyMapping(parent, (String) mapping, null, null);
         if (newMapping == null)
            throw new IllegalArgumentException("No property: " + mapping + " for class: " + parent);
         mapping = newMapping;
      }

      if (mapping instanceof IBeanMapper)
         return ((IBeanMapper) mapping).getPropertyValue(null);
      else
         return PTypeUtil.getProperty(null, mapping);
   }

   public static void setStaticValue(Class parent, Object mapping, Object value) {
      if (mapping instanceof String) {
         Object newMapping = getPropertyMapping(parent, (String) mapping, null, null);
         if (newMapping == null)
            throw new IllegalArgumentException("No property: " + mapping + " for class: " + parent);
         mapping = newMapping;
      }

      if (mapping instanceof IBeanMapper)
         ((IBeanMapper) mapping).setPropertyValue(null, value);
      else
         PTypeUtil.setProperty(null, mapping, value);
   }

   public static boolean evalInstanceOfExpression(Object lhsVal, Class theClass) {
      return PTypeUtil.evalInstanceOfExpression(lhsVal, theClass);
   }

   public static Object getPropertyValueFromName(Object parent, String propName) {
      Object newMapping = getPropertyMapping(parent.getClass(), propName, null, null);
      // No mapping
      if (newMapping == null) {
         throw new IllegalArgumentException("No property: " + propName + " in class: " + parent.getClass() + " for instance: " + DynUtil.getInstanceName(parent));
      }
      return getPropertyValue(parent, newMapping);
   }

   public static Object getPropertyValue(Object parent, Object mapping) {
      if (mapping instanceof IBeanMapper)
         return ((IBeanMapper) mapping).getPropertyValue(parent);
      else if (parent instanceof IDynObject && mapping instanceof String)
         return ((IDynObject) parent).getProperty((String) mapping);
      else if (mapping instanceof String) {
         return getPropertyValueFromName(parent, (String) mapping);
      }
      else
         return PTypeUtil.getProperty(parent, mapping);
   }

   public static Object invokeMethod(Object thisObject, Object method, Object... argValues) {
      return PTypeUtil.invokeMethod(thisObject, method, argValues);
   }

   public static IBeanMapper resolveObjectPropertyMapping(Object dstObj, String dstPropName) {
      IBeanMapper map;
      if (!(dstObj instanceof Class)) {
         if (dstObj instanceof IDynObject) {
            IDynObject dynObj = (IDynObject) dstObj;
            Object type = dynObj.getDynType();
            return DynUtil.getPropertyMapping(type, dstPropName);
         }
         else {
            if (DynUtil.isNonCompiledType(dstObj))
               return DynUtil.getPropertyMapping(dstObj, dstPropName);
            map = PTypeUtil.getPropertyMapping(dstObj.getClass(), dstPropName);
            if (map == null)
               throw new IllegalArgumentException("No property: " + dstPropName + " for type: " + dstObj.getClass());
         }
      }
      else {
         map = PTypeUtil.getPropertyMapping((Class) dstObj, dstPropName);
         if (map == null) {
            throw new IllegalArgumentException("No static property: " + dstPropName + " for type: " + dstObj);
         }
      }
      return map;
   }

   public static IBeanMapper getObjectPropertyMapping(Object dstObj, String dstPropName) {
      if (!(dstObj instanceof Class))
         return PTypeUtil.getPropertyMapping(dstObj.getClass(), dstPropName);
       else
         return PTypeUtil.getPropertyMapping((Class) dstObj, dstPropName);
   }

   public static String[] binaryOperators = {"||", "&&", "|", "^", "&", "==", "!=", "<", ">", "<=", ">=", "<<", ">>>", ">>", "+", "-", "*", "/", "%"};
   private static int[] precedenceArray =   { 3,    4,    5,   6,   7,   8,    8,    9,  9,    9,    9,    10,   10,    10,  11,  11,  12,  12,  12};
   static IntCoalescedHashMap precedenceTable = new IntCoalescedHashMap(binaryOperators.length);
   static {
      for (int i = 0; i < binaryOperators.length; i++)
         precedenceTable.put(binaryOperators[i], precedenceArray[i]);
      // Special case - not in the array above cause the parser needs to treat it differently
      precedenceTable.put("instanceof", 9);
   }

   public static boolean operatorPrecedes(String opA, String opB) {
      int precA = precedenceTable.get(opA);
      int precB = precedenceTable.get(opB);
      return precA > precB;
   }

   public static String getTypeName(Class cl, boolean includeDims) {
      if (cl.isArray()) {
         StringBuilder sb = new StringBuilder();
         sb.append(cl.getComponentType().getName());
         if (includeDims) {
            String arrayClass = cl.getName().replace('$', '.');
            for (int i = 0; arrayClass.charAt(i) == '['; i++)
               sb.append("[]");
         }
         return sb.toString();
      }
      else {
         return cl.getName().replace('$', '.');
      }
   }

   public static String stripTypeParameters(String objectClassName) {
      int ix = objectClassName.indexOf("<");
      if (ix == -1)
         return objectClassName;
      return
         objectClassName.substring(0,ix);
   }

   public static boolean equalPropertySelectors(Object m1, Object m2) {
      if (m1 == m2) return true;

      if (m1 == null || m2 == null) return false;

      return getPropertyName(m1).equals(getPropertyName(m2));
   }

   public static String getPropertyName(Object mapper) {
      if (mapper instanceof IBeanMapper)
         return ((IBeanMapper) mapper).getPropertyName();
      if (mapper instanceof String)
         return (String) mapper;
      else
         return PTypeUtil.getPropertyName(mapper);
   }

   public static String getArrayName(Object obj) {
      StringBuilder sb = new StringBuilder();
      sb.append("{");
      int len = PTypeUtil.getArrayLength(obj);
      for (int i = 0; i < len; i++) {
         if (i != 0)
            sb.append(",");
         Object elem = PTypeUtil.getArrayElement(obj, i);
         sb.append(DynUtil.toString(elem));
      }
      sb.append("}");
      return sb.toString();
   }

   public static IBeanMapper getPropertyMapping(Class beanClass, String propName) {
      return PTypeUtil.getPropertyMapping(beanClass, propName);
   }

   /** Strips off just the package name part and returns the "A.B" name of any inner type */
   public static String getInnerTypeName(Class typeObj) {
      return CTypeUtil.getClassName(typeObj.getName()).replace('$', '.');
   }

   public static Object primArrayToObjArray(Object val) {
      int len = PTypeUtil.getArrayLength(val);
      Object[] res = new Object[len];
      for (int i = 0; i < len; i++) {
         res[i] = PTypeUtil.getArrayElement(val, i);
      }
      return res;
   }
}
