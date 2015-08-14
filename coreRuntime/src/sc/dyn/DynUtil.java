/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.dyn;

import sc.bind.Bind;
import sc.bind.MethodBinding;
import sc.obj.IAltComponent;
import sc.obj.IComponent;
import sc.obj.IObjectId;
import sc.sync.SyncManager;
import sc.type.*;
import sc.util.IdentityWrapper;

import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Static utility methods used by runtime sc applications.  This class serves as a bridge from the runtime
 * world to the dynamic/interpreted world.  When the interpreter is enabled, dynamicSystem is set and
 * that is used for managing operations on dynamic objects.  When that is null, only the compile time features are
 * available.   Compiled classes can link against this class and then run either with or without the interpreter.
 */
@sc.js.JSSettings(jsLibFiles="js/scdyn.js", prefixAlias="sc_", dependentJSFiles = "js/jvsys.js")
public class DynUtil {
   private DynUtil() {
   }

   public static IDynamicSystem dynamicSystem = null;

   static int traceCt = 0;

   static Map<Object,Integer> traceIds = (Map<Object,Integer>) PTypeUtil.getWeakHashMap();

   // A table for each type name which lists the current unique integer for that type that we use to automatically generate semi-unique ids for each instance
   static Map<String,Integer> typeIdCounts = new HashMap<String,Integer>();

   // A table which stores the automatically assigned id for a given object instance.
   //static Map<Object,String> objectIds = (Map<Object,String>) PTypeUtil.getWeakHashMap();
   static Map<Object,String> objectIds = new HashMap<Object,String>();

   static Map<Class, DynType> mappingCache = new HashMap<Class, DynType>();

   public static void clearObjectIds() {
      objectIds.clear();
   }

   public static void flushCaches() {
      mappingCache.clear();
   }

   public static DynType getPropertyCache(Class beanClass) {
      return mappingCache.get(beanClass);
   }

   public static String getTypeName(Object type, boolean includeDims) {
      if (dynamicSystem == null) {
         if (type instanceof Class)
            return TypeUtil.getTypeName((Class) type, includeDims);
         else
            throw new IllegalArgumentException("Attempt to use dynamic type with no registered dynamic model");
      }
      else
         return dynamicSystem.getTypeName(type, includeDims);
   }

   public static int getNumInnerTypeLevels(Object obj) {
      if (dynamicSystem == null) {
         throw new IllegalArgumentException("Unable to determine object structure with no dynamic system");
      }
      else
         return dynamicSystem.getNumInnerTypeLevels(obj);
   }

   public static int getNumInnerObjectLevels(Object obj) {
      if (dynamicSystem == null) {
         throw new IllegalArgumentException("Unable to determine object structure with no dynamic system");
      }
      else
         return dynamicSystem.getNumInnerObjectLevels(obj);
   }

   public static String getInnerTypeName(Object type) {
      if (dynamicSystem == null) {
         if (type instanceof Class)
            return TypeUtil.getInnerTypeName((Class) type);
         else
            throw new IllegalArgumentException("Attempt to use dynamic type with no registered dynamic model");
      }
      else
         return dynamicSystem.getInnerTypeName(type);
   }

   public static IBeanMapper getPropertyMapping(Object type, String dstPropName) {
      if (dynamicSystem == null) {
         if (type instanceof Class)
            return PTypeUtil.getPropertyMapping((Class) type, dstPropName);
         else if (type instanceof DynType)
            return ((DynType) type).getPropertyMapper(dstPropName);
         else
            throw new IllegalArgumentException("Attempt to use dynamic mapping with no registered dynamic model");
      }

      return dynamicSystem.getPropertyMapping(type, dstPropName);
   }

   public static IBeanMapper getConstantPropertyMapping(Object type, String dstPropName) {
      if (dynamicSystem == null) {
         if (type instanceof Class)
            return TypeUtil.getConstantPropertyMapping((Class) type, dstPropName);
         else if (type instanceof DynType)
            return ((DynType) type).getPropertyMapper(dstPropName);
         else
            throw new IllegalArgumentException("Attempt to use dynamic mapping with no registered dynamic model");
      }

      return dynamicSystem.getConstantPropertyMapping(type, dstPropName);
   }

   public static IBeanMapper getArrayLengthPropertyMapping(Object type, String dstPropName) {
      return PTypeUtil.getArrayLengthBeanMapper();
   }

   /** Like getPropertyMapping but prints an error if the property is not found. */
   public static IBeanMapper resolvePropertyMapping(Object type, String dstPropName) {
      IBeanMapper res;
      if (dynamicSystem == null) {
         if (type instanceof Class)
            return TypeUtil.resolvePropertyMapping((Class) type, dstPropName);
         else if (type instanceof DynType) {
            res = ((DynType) type).getPropertyMapper(dstPropName);
            if (res != null)
               return res;
         }
         else
            throw new IllegalArgumentException("Attempt to use dynamic mapping with no registered dynamic model");
      }
      else
         res = dynamicSystem.getPropertyMapping(type, dstPropName);
      if (res == null) {
         System.err.println("No property: " + dstPropName + " for: " + type);
      }
      return res;
   }

   public static Object getReturnType(Object dynMethod) {
      if (dynamicSystem != null)
         return dynamicSystem.getReturnType(dynMethod);
      else
         return PTypeUtil.getReturnType(dynMethod);
   }

   public static Object[] getParameterTypes(Object dynMethod) {
      if (dynamicSystem != null)
         return dynamicSystem.getParameterTypes(dynMethod);
      else
         return PTypeUtil.getParameterTypes(dynMethod);
   }

   public static boolean instanceOf(Object obj, Object type) {
      if (obj == null)
         return true;

      Object type1 = DynUtil.getType(obj);
      if (type1 instanceof Class && type instanceof Class)
         return PTypeUtil.isAssignableFrom((Class) type, (Class) type1);
      else if (dynamicSystem != null)
         return dynamicSystem.isAssignableFrom(type, type1);
      else
         throw new IllegalArgumentException("Unrecognized type: " + type1);
   }

   public static boolean isAssignableFrom(Object type1, Object type2) {
      if (type1 instanceof Class && type2 instanceof Class)
         //return TypeUtil.isAssignableFromParameter((Class) type1, (Class) type2); // TODO: is this right?  shouldn't it be the Class.isAssinableFrom?
         return PTypeUtil.isAssignableFrom((Class) type1, (Class) type2);
      else if (dynamicSystem != null)
         return dynamicSystem.isAssignableFrom(type1, type2);
      else
         throw new IllegalArgumentException("Unrecognized type: " + type1);
   }

   public static Class getCompiledClass(Object type) {
      if (type instanceof Class)
         return (Class) type;
      else if (dynamicSystem != null)
         return dynamicSystem.getCompiledClass(type);
      else
         throw new IllegalArgumentException("Unrecognized type to getCompiledClass: " + type);
   }

   public static Object invokeMethod(Object obj, Object method, Object... paramValues) {
      if (dynamicSystem != null) {
         return dynamicSystem.invokeMethod(obj, method, paramValues);
      }
      else if (method instanceof IMethodMapper) {
         return ((IMethodMapper) method).invoke(obj, paramValues);
      }
      else
        return TypeUtil.invokeMethod(obj, method, paramValues);
   }

   public static RemoteResult invokeRemote(Object obj, Object method, Object... paramValues) {
      return SyncManager.invokeRemote(obj, DynUtil.getMethodName(method), paramValues);
   }

   /** In Java this is the same method but in Javascript they are different */
   public static Object resolveStaticMethod(Object type, String methodName, String paramSig) {
      return resolveMethod(type, methodName, paramSig);
   }

   public static Object resolveRemoteMethod(Object type, String methodName, String paramSig) {
      DynRemoteMethod rm = new DynRemoteMethod();
      rm.type = type;
      rm.methodName = methodName;
      rm.paramSig = paramSig;
      return rm;
   }

   public static Object resolveRemoteStaticMethod(Object type, String methodName, String paramSig) {
      DynRemoteMethod rm = new DynRemoteMethod();
      rm.type = type;
      rm.methodName = methodName;
      rm.paramSig = paramSig;
      rm.isStatic = true;
      return rm;
   }

   public static Object resolveMethod(Object type, String methodName, String paramSig) {
      if (type instanceof Class)
         return PTypeUtil.resolveMethod((Class) type, methodName, paramSig);
      else if (type instanceof DynType)
         return ((DynType) type).getMethod(methodName, paramSig);
      else if (dynamicSystem != null)
         return dynamicSystem.resolveMethod(type, methodName, paramSig);
      else
         throw new IllegalArgumentException("Unrecognized type to resolveMethod: " + type);
   }

   public static String getMethodName(Object method) {
      if (method instanceof DynRemoteMethod)
         return ((DynRemoteMethod) method).methodName;
      if (dynamicSystem != null)
         return dynamicSystem.getMethodName(method);
      else
         return PTypeUtil.getMethodName(method);
   }

   public static Object evalCast(Object o, Object value) {
      if (o instanceof Class)
         return evalCast((Class) o, value);
      else if (dynamicSystem != null)
         return dynamicSystem.evalCast(o, value);
      else
         throw new IllegalArgumentException("Unrecognized type to evalCast: " + o);
   }

   /**
    * StrataCode uses some objects to implement dynamic types.  This method returns true if the given object is either
    * a plain old Class type or a dynamic type.
    */
   public static boolean isType(Object obj) {
      if (obj instanceof Class)
         return true;
      if (dynamicSystem != null)
         return dynamicSystem.isTypeObject(obj);
      return false;
   }

   /**
    * StrataCode uses some objects to implement dynamic types.  This method returns true if the given object is either
    * a plain old Class type or a dynamic type.
    */
   public static boolean isSType(Object obj) {
      if (obj instanceof Class)
         return true;
      if (dynamicSystem != null)
         return dynamicSystem.isSTypeObject(obj);
      return false;
   }

   /** Returns true if this is a TypeDeclaration, not a Class */
   public static boolean isNonCompiledType(Object obj) {
      if (dynamicSystem == null)
         return false;
      return dynamicSystem.isNonCompiledType(obj);
   }

   /** Like the above but does not check if obj is itself a class.  That breaks when we try to use ITypeDeclaration as an object instance. */
   public static Object getSType(Object obj) {
      if (dynamicSystem == null) {
         return obj.getClass();
      }
      else {
         if (obj instanceof IDynObject)
            return ((IDynObject) obj).getDynType();
         else {
            return obj.getClass();
         }
      }
   }

   public static Object getType(Object obj) {
      if (dynamicSystem == null) {
         if (obj instanceof Class)
            return obj;
         return obj.getClass();
      }
      else {
         if (dynamicSystem.isTypeObject(obj))
            return obj;
         else if (obj instanceof IDynObject)
            return ((IDynObject) obj).getDynType();
         else {
            return obj.getClass();
         }
      }
   }

   public static boolean hasModifier(Object def, String modifier) {
      if (dynamicSystem == null)
         return PTypeUtil.hasModifier(def, modifier);
      else {
         return dynamicSystem.hasModifier(def, modifier);
      }
   }

   public static int getStaticPropertyCount(Object cl) {
      if (cl instanceof Class)
         return TypeUtil.getStaticPropertyCount((Class) cl);
      else if (dynamicSystem != null)
         return dynamicSystem.getStaticPropertyCount(cl);
      else
         throw new IllegalArgumentException("Unrecognized typed: " + cl);
   }

   public static int getPropertyCount(Object obj) {
      if (dynamicSystem == null || !dynamicSystem.isDynamicObject(obj))
         return TypeUtil.getPropertyCount(obj);
      else if (dynamicSystem != null)
         return dynamicSystem.getPropertyCount(obj);
      else
         throw new IllegalArgumentException("Unrecognized obj in getPropertyCount: " + obj);
   }

   public static int intPropertyValue(IDynObject p, String propName) {
      Object o = p.getProperty(propName);
      return o == null ? 0 : ((Number) o).intValue();
   }

   public static float floatPropertyValue(IDynObject p, String propName) {
      Object o = p.getProperty(propName);
      return o == null ? 0 : ((Number) o).floatValue();
   }

   public static double doublePropertyValue(IDynObject p, String propName) {
      Object o = p.getProperty(propName);
      return o == null ? 0 : ((Number) o).doubleValue();
   }

   public static long longPropertyValue(IDynObject p, String propName) {
      Object o = p.getProperty(propName);
      return o == null ? 0 : ((Number) o).longValue();
   }
   public static IBeanMapper[] getProperties(Object typeObj) {
      if (typeObj instanceof Class)
         return TypeUtil.getProperties((Class) typeObj);
      else if (dynamicSystem != null)
         return dynamicSystem.getProperties(typeObj);
      else
         throw new IllegalArgumentException("Invalid dynamic type: " + typeObj);
   }

   public static IBeanMapper[] getStaticProperties(Object typeObj) {
      if (typeObj instanceof Class)
         return TypeUtil.getStaticProperties((Class) typeObj);
      else if (dynamicSystem != null)
         return dynamicSystem.getStaticProperties(typeObj);
      else
         throw new IllegalArgumentException("Invalid dynamic type: " + typeObj);
   }

   public static Object getPropertyPath(Object origObj, String origProperty) {
      Object currentObj = origObj;
      String propertyName = origProperty;
      do {
         int ix = propertyName.indexOf(".");
         String nextProp;
         if (ix == -1) {
            nextProp = propertyName;
            propertyName = null;
         }
         else {
            nextProp = propertyName.substring(0, ix);
            propertyName = propertyName.substring(ix+1);
         }
         Object nextObj = getProperty(currentObj, nextProp);
         if (nextObj == null) {
            if (propertyName != null)
               throw new IllegalArgumentException("Null property: " + nextProp + " resolving path: " + origProperty + " for obj: " + origObj);
            else
               return null;
         }
         if (propertyName == null)
            return nextObj;
         currentObj = nextObj;
      } while(true);
   }

   public static Object getPropertyValue(Object object, String propertyName) {
      if (object instanceof IDynObject)
         return ((IDynObject) object).getProperty(propertyName);
      else if (object instanceof Map)
         return ((Map) object).get(propertyName);
      else
         return TypeUtil.getPropertyValue(object, propertyName);
   }

   /** This variant works for static properties as well but breaks if the TypeDeclaration object is used as an arg */
   public static Object getProperty(Object object, String propertyName) {
      if (object instanceof IDynObject)
         return ((IDynObject) object).getProperty(propertyName);
      else if (dynamicSystem != null && dynamicSystem.isTypeObject(object))
         return dynamicSystem.getStaticProperty(object, propertyName);
      else if (object instanceof Map)
         return ((Map) object).get(propertyName);
      else
         return TypeUtil.getPropertyOrStaticValue(object, propertyName);
   }

   public static Object getStaticProperty(Object typeObj, String propertyName) {
      if (dynamicSystem != null)
         return dynamicSystem.getStaticProperty(typeObj, propertyName);
      else
         return TypeUtil.getStaticValue((Class) typeObj, propertyName);
   }

   public static void setStaticProperty(Object typeObj, String propertyName, Object valueToSet) {
      if (typeObj instanceof Class) {
         TypeUtil.setStaticValue((Class) typeObj, propertyName, valueToSet);
      }
      else if (dynamicSystem != null)
         dynamicSystem.setStaticProperty(typeObj, propertyName, valueToSet);
   }

   public static void setPropertyValue(Object object, String propertyName, Object valueToSet) {
      if (object instanceof IDynObject)
         ((IDynObject) object).setProperty(propertyName, valueToSet, false);
      else if (object instanceof Map)
         ((Map) object).put(propertyName, valueToSet);
      else
         TypeUtil.setProperty(object, propertyName, valueToSet);
   }

   public static void setProperty(Object object, String propertyName, Object valueToSet) {
      setProperty(object, propertyName, valueToSet, false);
   }

   public static void setProperty(Object object, String propertyName, Object valueToSet, boolean setField) {
      if (object instanceof IDynObject)
         ((IDynObject) object).setProperty(propertyName, valueToSet, setField);
      else if (dynamicSystem != null && dynamicSystem.isTypeObject(object))
         dynamicSystem.setStaticProperty(object, propertyName, valueToSet);
      else if (object instanceof Map)
         ((Map) object).put(propertyName, valueToSet);
      else
         TypeUtil.setPropertyOrStaticValue(object, propertyName, valueToSet);
   }

   public static Object createInstance(Object typeObj, String constrSig, Object...params) {
      if (dynamicSystem != null) {
         return dynamicSystem.createInstance(typeObj, constrSig, params);
      }
      else if (typeObj instanceof Class)
         return PTypeUtil.createInstance((Class) typeObj, constrSig, params);
      // In some cases, we pass in the direct constructor.  It will know its type and sig so just call it with the parameters
      else if (typeObj instanceof IMethodMapper) {
         return ((IMethodMapper) typeObj).invoke(null, params);
      }
      else
         throw new IllegalArgumentException("Invalid dynamic type: " + typeObj);
   }

   public static Object createInnerInstance(Object typeObj, Object outerObj, String constrSig, Object...params) {
      if (dynamicSystem != null) {
         return dynamicSystem.createInnerInstance(typeObj, outerObj, constrSig, params);
      }
      else if (typeObj instanceof Class) {
         Object[] newParams = new Object[params.length+1];
         newParams[0] = outerObj;
         System.arraycopy(params, 0, newParams, 1, params.length);
         return PTypeUtil.createInstance((Class) typeObj, constrSig, newParams);
      }
      else
         throw new IllegalArgumentException("Invalid dynamic type: " + typeObj);
   }

   public static void addDynObject(String typeName, Object instObj) {
      if (dynamicSystem != null)
         dynamicSystem.addDynObject(typeName, instObj);
   }

   public static void addDynInnerObject(String typeName, Object innerObj, Object outerObj) {
      if (dynamicSystem != null)
         dynamicSystem.addDynInnerObject(typeName, innerObj, outerObj);
   }

   // In JS, this will return a property we store on the object.
   public static String getObjectName(Object obj) {
      if (dynamicSystem != null)
         return dynamicSystem.getObjectName(obj);
      return null;
   }

   /**
    * Used to associate object instances with their type in order to support the liveDynamicTypes feature. 
    * You turn this on both globally and for compiled layers, as an option in the layer.  When enabled,
    * a call to register each instance with its type is put into the object creation template.  When you later
    * modify the object's type definition (such as through a PropertyAssignment), we can apply those changes to
    * all instances.  This gets turned on automatically for all types in dynamic layers.
    */
   public static void addDynInstance(String typeName, Object instObj) {
      if (dynamicSystem != null)
         dynamicSystem.addDynInstance(typeName, instObj);
   }

   public static void addDynInnerInstance(String typeName, Object innerObj, Object outerObj) {
      if (dynamicSystem != null)
         dynamicSystem.addDynInnerInstance(typeName, innerObj, outerObj);
   }

   public static Object[] resolveTypeGroupMembers(String typeGroupName) {
      if (dynamicSystem != null)
         return dynamicSystem.resolveTypeGroupMembers(typeGroupName);
      return null;
   }

   public static String[] getDynSrcDirs() {
      if (dynamicSystem == null)
         return null;
      return dynamicSystem.getDynSrcDirs();
   }

   public static String[] getDynSrcPrefixes() {
      if (dynamicSystem == null)
         return null;
      return dynamicSystem.getDynSrcPrefixes();
   }

   public static void refreshType(String typeName) {
      if (dynamicSystem == null)
         return;

      dynamicSystem.refreshType(typeName);
   }

   /** Returns the dynamic children objects for the given instance */
   public static Object[] getObjChildren(Object inst, String scopeName, boolean create) {
      if (dynamicSystem == null) {
         if (inst instanceof IObjChildren)
            return ((IObjChildren) inst).getObjChildren(create);
      }

      return dynamicSystem.getObjChildren(inst, scopeName, create);
   }

   public static Object[] getObjChildrenArray2DRange(Object[] srcArr, String scopeName, int startIx, int endIx) {
      int srcSize = srcArr.length;
      ArrayList<Object> res = new ArrayList<Object>(srcSize);
      for (int i = 0; i < srcSize; i++) {
         Object[] inner = getObjChildren(srcArr[i], scopeName, true);
         for (int j = startIx; j < endIx; j++) {
            if (j < inner.length)
               res.add(inner[j]);
         }
      }
      return res.toArray();
   }

   /** Returns tne property names of the object children for the given type object */
   public static String[] getObjChildrenNames(Object typeObj, String scopeName) {
      if (dynamicSystem == null)
         return null;

      return dynamicSystem.getObjChildrenNames(typeObj, scopeName);
   }

   /** Returns the type objects for child objects.  If scopeName is not null, only types with the given scope are returned. */
   public static Object[] getObjChildrenTypes(Object typeObj, String scopeName) {
      if (dynamicSystem == null)
         return null;

      return dynamicSystem.getObjChildrenTypes(typeObj, scopeName);
   }

   public static Object getReverseBindingMethod(Object method) {
      if (dynamicSystem == null)
         return null;

      return dynamicSystem.getReverseBindingMethod(method);

   }

   public static IReverseMethodMapper getReverseMethodMapper(MethodBinding binding) {
      return PTypeUtil.getReverseMethodMapper(binding);
   }

   public static boolean equalObjects(Object obj1, Object obj2) {
      return obj1 == obj2 || (obj1 != null && obj2 != null && obj1.equals(obj2)) || ((obj1 instanceof Number && obj2 instanceof Number) && closeEquals((Number) obj1, (Number) obj2));
   }

   private final static double FloatingPointEpsilon = 1e-10;

   // Conversion of properties from client to server requires that we do a close equals for numbers
   public static boolean closeEquals(Number v1, Number v2) {
      return Math.abs(v1.doubleValue() - v2.doubleValue()) < FloatingPointEpsilon;
   }

   public static boolean equalArrays(Object[] arr1, Object[] arr2) {
      if (arr1 == null && arr2 == null)
         return true;
      if (arr1 == null || arr2 == null)
         return false;
      if (arr1.length != arr2.length)
         return false;
      for (int i = 0; i < arr1.length; i++) {
         if (!equalObjects(arr1[i], arr2[i]))
            return false;
      }
      return true;
   }

   public static Object evalCast(Class theClass, Object value) {
      if (value == null) return null;
      Type castType = Type.get(theClass);
      return castType.evalCast(theClass, value);
   }

   /** Returns a small but consistent id for each object under the microscope */
   public static String getTraceId(Object obj) {
      Integer id;
      if ((id = traceIds.get(new IdentityWrapper(obj))) == null)
         traceIds.put(new IdentityWrapper(obj), id = new Integer(traceCt++));

      return String.valueOf(id);
   }

   public static String getTraceObjId(Object obj) {
      Integer id;
      if ((id = traceIds.get(obj)) == null)
         traceIds.put(obj, id = new Integer(traceCt++));

      return String.valueOf(id);
   }

   public static String cleanClassName(Class cl) {
      return CTypeUtil.getClassName((cl.getName().replace('$', '.')));
   }

   public static String cleanTypeName(String typeName) {
      return typeName.replace('$', '.');
   }

   public static String arrayToInstanceName(Object[] list) {
      if (list == null)
         return "";
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < list.length; i++) {
         if (i != 0)
            sb.append(", ");
         sb.append(getInstanceName(list[i]));
      }
      return sb.toString();
   }

   /** Returns a unique id for the given object.  Types return the type name.  The id will otherwise by type-id__integer */
   public static String getInstanceId(Object obj) {
      if (obj == null)
         return null;
      if (obj instanceof Class)
         return cleanTypeName(((Class) obj).getName());
      if (DynUtil.isType(obj)) {
         // Type declarations put in the layer after the type name.  Need to strip that out.
         String s = DynUtil.getInnerTypeName(obj);
         // NOTE: sc_type_ is hard-coded elsewhere - search for it to find all usages.  Need to turn this into an id because we may have mroe than one type for the same type name.
         return getObjectId(obj, obj, "sc_type_" + s.replace('.', '_'));
      }
      else {
         if (obj instanceof IObjectId) {
            return ((IObjectId) obj).getObjectId();
         }

         Object type = DynUtil.getType(obj);

         if (DynUtil.isEnumConstant(obj))
            return DynUtil.getTypeName(type, false) + "." + obj.toString();

         return getObjectId(obj, type, null);
      }
   }

   public static String getObjectId(Object obj, Object type, String typeName) {
      String objId = objectIds.get(new IdentityWrapper(obj));
      if (objId != null)
         return objId;

      if (typeName == null)
         typeName = DynUtil.getTypeName(type, true);
      String typeIdStr = "__" + getTypeIdCount(typeName);
      String id = cleanTypeName(typeName) + typeIdStr;
      objectIds.put(new IdentityWrapper(obj), id);
      return id;
   }

   public static Integer getTypeIdCount(String typeName) {
      Integer typeId = typeIdCounts.get(typeName);
      if (typeId == null) {
         typeIdCounts.put(typeName, 1);
         typeId = 0;
      }
      else {
         typeIdCounts.put(typeName, typeId + 1);
      }
      return typeId;
   }

   /**
    * Returns a nice short String to display for this object instance for debugging and logging purposes primarily.
    * The toString is inspected and if it makes a nice looking debug String it is used. If it's too long or has new lines it is
    * not used and the name__integer is used.
    */
   public static String getInstanceName(Object obj) {
      if (obj instanceof Class)
         return cleanClassName((Class) obj);
      else {
         String typeName;
         if (obj == null)
            return "null";
         if (obj instanceof Collection)
            return cleanClassName(obj.getClass()) + "[" + ((Collection) obj).size() + "]";
         if (obj instanceof IDynObject) {
            Object dynType = ((IDynObject) obj).getDynType();
            typeName = DynUtil.getTypeName(dynType, false);
         }
         else if (isType(obj))
            return obj.toString();
         else {
            Class objClass = obj.getClass();
            Type theType;
            if (PTypeUtil.isPrimitive(objClass) || (theType = Type.get(objClass)).isANumber() || theType.primitiveClass != null || objClass == Date.class)
               return obj.toString();
            else if (objClass == String.class)
               return "\"" + obj.toString() + "\"";
            else if (obj.getClass().isArray()) {
               return TypeUtil.getArrayName(obj);
            }
            else {
               String toStr = obj.toString();
               if (!PTypeUtil.useInstanceName(toStr))
                  return toStr;
            }

            typeName = cleanClassName(obj.getClass());
         }
         return CTypeUtil.getClassName(typeName).replace('$', '.') + "__" + getTraceId(obj);
      }
   }

   public static String getInstanceTraceName(Object obj) {
      String typeName;
      if (obj instanceof IDynObject) {
         typeName = ((IDynObject) obj).getDynType().toString();
         // Strip off the layer name if it is present in the type's toString
         int ix = typeName.indexOf('(');
         if (ix != -1)
            typeName = typeName.substring(0, ix-1);
      }
      else
         typeName = cleanClassName(obj.getClass());
      return CTypeUtil.getClassName(typeName).replace('$', '.') + "__" + getTraceId(obj);
   }

   public static String toString(Object obj) {
      if (obj == null)
         return "null";
      if (obj.getClass().isArray()) {
         return TypeUtil.getArrayName(obj);
      }
      return obj.toString();
   }

   public static int getArrayLength(Object arrVal) {
      if (arrVal instanceof Collection)
         return ((Collection) arrVal).size();
      return PTypeUtil.getArrayLength(arrVal);
   }

   public static Object getArrayElement(Object arrVal, int dim) {
      if (arrVal == null)
         throw new NullPointerException("Array access of null value: " + dim);
      if (arrVal.getClass().isArray()) {
         return PTypeUtil.getArrayElement(arrVal, dim);
      }
      else if (arrVal instanceof List) {
         return ((List) arrVal).get(dim);
      }
      else // TODO: no need to special case this right?
         return PTypeUtil.getArrayElement(arrVal, dim);
   }

   public static void setArrayElement(Object arrVal, int dim, Object value) {
      if (arrVal == null)
         throw new NullPointerException("Array access of null value: " + dim);
      if (arrVal.getClass().isArray()) {
         PTypeUtil.setArrayElement(arrVal, dim, value);
      }
      else if (arrVal instanceof List) {
         ((List) arrVal).set(dim, value);
      }
      else
         throw new IllegalArgumentException("Unable to set element of non-array: " + arrVal.getClass());
   }

   public static Object getIndexedProperty(Object obj, IBeanMapper prop, int ix) {
      if (prop instanceof IBeanIndexMapper)
         return ((IBeanIndexMapper) prop).getIndexPropertyValue(obj, ix);
      else {
         Object arrayValue = TypeUtil.getPropertyValue(obj, prop);
         return getArrayElement(arrayValue, ix);
      }
   }

   public static void setIndexedProperty(Object obj, Object prop, int ix, Object value) {
      // Use the indexed setter if it is there so that we trigger the event
      if (prop instanceof IBeanIndexMapper)
         ((IBeanIndexMapper) prop).setIndexPropertyValue(obj, ix, value);
      else {
         Object arrayValue = TypeUtil.getPropertyValue(obj, prop);
         setArrayElement(arrayValue, ix, value);
      }
   }

   public static Object evalArithmeticExpression(String operator, Class expectedType, Object lhsVal, Object rhsVal) {
      Type exprType;
      if (expectedType == null) {
         if (lhsVal != null)
         expectedType = lhsVal.getClass();
         exprType = Type.get(expectedType);
         // If the right hand side is a floating point and the left is not choose the right's type
         if (rhsVal != null) {
            Class rhsClass = rhsVal.getClass();
            Type rhsType = Type.get(rhsClass);
            // If the rhs type is an int, short, etc and the rhs is a double upgrade.  But if the right is a string
            // that overrides both of those rules.
            if (rhsType.isFloatingType() && exprType != Type.String)
               exprType = rhsType;
            // Obj, int, etc + "" -> String
            if (rhsType == Type.String)
               exprType = rhsType;
         }
      }
      else
        exprType = Type.get(expectedType);

      return exprType.evalArithmetic(operator, lhsVal, rhsVal);
   }

   public static Object evalPreConditionalExpression(String operator, Object lhsVal) {
      if (lhsVal == null)
         return null;
      Class argType = lhsVal.getClass();
      Type exprType = Type.get(argType);
      return exprType.evalPreConditional(operator, lhsVal);
   }

   public static Object evalConditionalExpression(String operator, Object lhsVal, Object rhsVal) {
      Class argType = lhsVal == null || rhsVal == null ? Object.class : lhsVal.getClass();
      Type exprType = Type.get(argType);
      return exprType.evalConditional(operator, lhsVal, rhsVal);
   }

   public static Object evalUnaryExpression(String operator, Class expectedType, Object val) {
      if (expectedType == null) {
         if (val == null)
            expectedType = Object.class;
         else
            expectedType = val.getClass();
      }
      Type exprType = Type.get(expectedType);
      return exprType.evalUnary(operator, val);
   }

   public static Object evalInverseUnaryExpression(String operator, Class expectedType, Object val) {
      switch (operator.charAt(0)) {
         case '!':
         case '~':
            return evalUnaryExpression(operator, expectedType, val);
         case '+':
            if (operator.equals("+"))
               return val;
         case '-':
            if (operator.equals("-"))
               return evalArithmeticExpression("-", expectedType, TypeUtil.ZERO, val);
         default:
            throw new IllegalArgumentException("Unsupported unary operator for inverse expression: " + operator);
      }
   }

   /** Used in code generation cases where we register this programmatically. */
   public static void addPropertyCache(Class newClass, DynType cache) {
      mappingCache.put(newClass, cache);
   }

   public static int intValue(Object o) {
      return o == null ? 0 : ((Number) o).intValue();
   }

   public static float floatValue(Object o) {
      return o == null ? 0 : ((Number) o).floatValue();
   }

   public static double doubleValue(Object o) {
      return o == null ? 0 : ((Number) o).doubleValue();
   }

   public static long longValue(Object o) {
      return o == null ? 0 : ((Number) o).longValue();
   }

   public static boolean booleanValue(Object o) {
      return o == null ? false : (Boolean) o;
   }

   public static char charValue(Object o) {
      return o == null ? '\0' : (Character) o;
   }

   public static Object setAndReturn(Object thisObj, String prop, Object value) {
      return setAndReturn(thisObj, PTypeUtil.getPropertyMapping(thisObj.getClass(), prop), value);
   }

   public static Object setAndReturnOrig(Object thisObj, String prop, Object value) {
      return setAndReturnOrig(thisObj, PTypeUtil.getPropertyMapping(thisObj.getClass(), prop), value);
   }

   public static Object setAndReturnStatic(Class thisObj, String prop, Object value) {
      return setAndReturnStatic(thisObj, PTypeUtil.getPropertyMapping(thisObj.getClass(), prop), value);
   }

   public static Object setAndReturnOrigStatic(Class thisObj, String prop, Object value) {
      return setAndReturnOrigStatic(thisObj, PTypeUtil.getPropertyMapping((Class) thisObj, prop), value);
   }

   public static Object setAndReturn(Object thisObj, IBeanMapper mapper, Object value) {
      TypeUtil.setProperty(thisObj, mapper, value);
      return value;
   }

   public static Object setAndReturnOrig(Object thisObj, IBeanMapper mapper, Object value) {
      Object origValue = TypeUtil.getPropertyValue(thisObj, mapper);
      TypeUtil.setProperty(thisObj, mapper, value);
      return origValue;
   }

   public static Object setAndReturnStatic(Class thisObj, IBeanMapper mapper, Object value) {
      TypeUtil.setStaticValue(thisObj, mapper, value);
      return value;
   }

   public static Object setAndReturnOrigStatic(Class thisObj, IBeanMapper mapper, Object value) {
      Object origValue = TypeUtil.getStaticValue(thisObj, mapper);
      TypeUtil.setStaticValue(thisObj, mapper, value);
      return origValue;
   }

   public static Number evalPropertyIncr(Object thisObj, String prop, int incr) {
      return evalPropertyIncr(thisObj, PTypeUtil.getPropertyMapping(thisObj.getClass(), prop), incr);
   }

   public static Number evalPropertyIncrOrig(Object thisObj, String prop, int incr) {
      return evalPropertyIncrOrig(thisObj, PTypeUtil.getPropertyMapping(thisObj.getClass(), prop), incr);
   }

   public static Number evalPropertyIncrStatic(Class thisObj, String prop, int incr) {
      return evalPropertyIncrStatic(thisObj, PTypeUtil.getPropertyMapping(thisObj.getClass(), prop), incr);
   }

   public static Number evalPropertyIncrOrigStatic(Class thisObj, String prop, int incr) {
      return evalPropertyIncrOrigStatic(thisObj, PTypeUtil.getPropertyMapping((Class) thisObj, prop), incr);
   }

   public static Number evalPropertyIncr(Object thisObj, IBeanMapper mapper, int incr) {
      Number origValue = (Number) TypeUtil.getPropertyValue(thisObj, mapper);
      Integer val;
      TypeUtil.setProperty(thisObj, mapper, val = origValue.intValue() + incr);
      return val;
   }

   public static Number evalPropertyIncrOrig(Object thisObj, IBeanMapper mapper, int incr) {
      Number origValue = (Number) TypeUtil.getPropertyValue(thisObj, mapper);
      TypeUtil.setProperty(thisObj, mapper, origValue.intValue() + incr);
      return origValue;
   }

   public static Number evalPropertyIncrStatic(Class thisObj, IBeanMapper mapper, int incr) {
      Number origValue = (Number) TypeUtil.getStaticValue(thisObj, mapper);
      Integer val;
      TypeUtil.setStaticValue(thisObj, mapper, val = origValue.intValue() + incr);
      return val;
   }

   public static Number evalPropertyIncrOrigStatic(Class thisObj, IBeanMapper mapper, int incr) {
      Number origValue = (Number) TypeUtil.getStaticValue(thisObj, mapper);
      TypeUtil.setStaticValue(thisObj, mapper, origValue.intValue() + incr);
      return origValue;
   }

   public static void dispose(Object obj) {
      dispose(obj, true);
   }

   /**
    * Called to remove the object from the dynamic type system.  Though we use weak storage for these instances,
    * it's faster to get rid of them when you are done.  Also removes the bindings.  Used the system dispose method
    * to just remove the instance from the instancesByType table.
    */
   public static void dispose(Object obj, boolean disposeChildren) {
      SyncManager.removeSyncInst(obj);
      Bind.removeBindings(obj, false);

      if (dynamicSystem != null)
         dynamicSystem.dispose(obj);
      else {
         // Even if it's dynamic we need to do super.stop() so call these first
         if (obj instanceof IComponent)
            ((IComponent) obj).stop();
         if (obj instanceof IAltComponent)
            ((IAltComponent) obj)._stop();
      }

      if (disposeChildren) {
         // We'll also dispose of the children objects
         // a declarative tree.
         Object[] children = DynUtil.getObjChildren(obj, null, false);
         if (children != null) {
            for (Object child:children) {
               if (child != null)
                  dispose(child);
            }
         }
      }
   }

   public static void initComponent(Object comp) {
      if (comp instanceof IComponent)
         ((IComponent) comp).init();
      else if (comp instanceof IAltComponent)
         ((IAltComponent) comp)._init();
   }

   public static void startComponent(Object comp) {
      if (comp instanceof IComponent)
         ((IComponent) comp).start();
      else if (comp instanceof IAltComponent)
         ((IAltComponent) comp)._start();
   }

   public static Object resolveName(String name, boolean create) {
      if (dynamicSystem != null)
         return dynamicSystem.resolveRuntimeName(name, create);
      else {
         // TODO: this is not right!
         Object type = findType(name);
         if (type != null) {
            String propName = CTypeUtil.getClassName(name);
            return getStaticProperty(type, propName);
         }
         return null;
      }
   }

   public static Object findType(String typeName) {
      if (dynamicSystem != null)
         return dynamicSystem.findType(typeName);
      return PTypeUtil.findType(typeName);
   }

   public static boolean isEvalSupported() {
      return false;
   }

   /** This is implemented only in the JS runtime */
   public static Object evalScript(String script) {
      throw new UnsupportedOperationException();
   }

   public static boolean isObjectType(Object type) {
      if (dynamicSystem != null)
         return dynamicSystem.isObjectType(type);
      return PTypeUtil.isObjectType(type);
   }

   public static boolean isObject(Object obj) {
      if (dynamicSystem != null)
         return dynamicSystem.isObject(obj);
      return PTypeUtil.isObject(obj);
   }

   public static boolean isRootedObject(Object obj) {
      if (dynamicSystem != null)
         return dynamicSystem.isRootedObject(obj);
      return false;
   }

   public static Object getOuterObject(Object srcObj) {
      if (dynamicSystem != null)
         return dynamicSystem.getOuterObject(srcObj);
      else {
         int ix = 0;
         Class cl = srcObj.getClass();
         // Static inner types do not have the this property.
         if ((cl.getModifiers() & Modifier.STATIC) != 0)
            return null;
         if (cl.isEnum())
            return null;
         Class encl;
         if ((encl = cl.getEnclosingClass()) == null)
            return null;
         while ((encl = encl.getEnclosingClass()) != null)
            ix++;
         return TypeUtil.getPropertyValue(srcObj, "this$" + ix);
      }
   }

   public static Object getRootType(Object typeObj) {
      do {
         Object nextObj = DynUtil.getEnclosingType(typeObj, false);
         if (nextObj == null)
            return typeObj;
         typeObj = nextObj;
      } while(true);
   }

   public static Object getEnclosingType(Object typeObj, boolean instOnly) {
      if (dynamicSystem != null)
         return dynamicSystem.getEnclosingType(typeObj, instOnly);
      return PTypeUtil.getEnclosingType(typeObj, instOnly);
   }

   public static boolean isEnumConstant(Object obj) {
      if (dynamicSystem != null)
         return dynamicSystem.isEnumConstant(obj);
      else
         return obj instanceof java.lang.Enum;
   }

   public static Object getEnumConstant(Object typeObj, String enumConstName) {
      if (dynamicSystem != null)
         return dynamicSystem.getEnumConstant(typeObj, enumConstName);
      else {
         return null;
      }
   }

   public static String getEnumName(Object enumObj) {
      return enumObj.toString();
   }

   public static IScheduler frameworkScheduler;

   public static void invokeLater(Runnable r, int priority) {
      if (frameworkScheduler == null)
         throw new IllegalArgumentException("Must set DynUtil.frameworkScheduler before calling invokeLater");
      frameworkScheduler.invokeLater(r, priority);
   }

   public static void execLaterJobs() {
      if (frameworkScheduler == null)
         return;
      frameworkScheduler.execLaterJobs();
   }

   public static String getPackageName(Object type) {
      if (type instanceof Class) {
         java.lang.Package pkg = ((Class) type).getPackage();
         if (pkg == null)
            return null;
         return pkg.getName();
      }
      if (dynamicSystem != null)
         return dynamicSystem.getPackageName(type);
      throw new UnsupportedOperationException();
   }

   public static Object getAnnotationValue(Object typeObj, String annotName, String attName) {
      if (typeObj instanceof Class) {
         return PTypeUtil.getAnnotationValue((Class) typeObj, annotName, attName);
      }
      else if (dynamicSystem != null) {
         return dynamicSystem.getAnnotationValue(typeObj, annotName, attName);
      }
      else throw new UnsupportedOperationException();
   }

   public static Object getInheritedAnnotationValue(Object typeObj, String annotName, String attName) {
      if (typeObj instanceof Class) {
         return PTypeUtil.getInheritedAnnotationValue((Class) typeObj, annotName, attName);
      }
      else if (dynamicSystem != null) {
         return dynamicSystem.getInheritedAnnotationValue(typeObj, annotName, attName);
      }
      else throw new UnsupportedOperationException();
   }

   public static String getScopeName(Object obj) {
      Object typeObj = DynUtil.getType(obj);
      if (dynamicSystem != null) {
         // Need to check both the sc.obj.Scope and scope<name> in tandem so each can override the other
         String scopeName = dynamicSystem.getInheritedScopeName(typeObj);
         if (scopeName != null)
            return scopeName;
      }
      else {
         Object scopeNameObj = getInheritedAnnotationValue(typeObj, "sc.obj.Scope", "name");
         if (scopeNameObj != null)
            return (String) scopeNameObj;
      }
      Object outer = DynUtil.getOuterObject(obj);
      if (outer != null)
         return getScopeName(outer);
      return null;
   }

   /** Returns the layered system class loader if there is one - null otherwise.  */
   public static ClassLoader getSysClassLoader() {
      if (dynamicSystem != null)
         return dynamicSystem.getSysClassLoader();
      return null;
   }

   public static Object getExtendsType(Object type) {
      if (dynamicSystem != null)
         return dynamicSystem.getExtendsType(type);
      if (type instanceof Class)
         return ((Class) type).getSuperclass();
      throw new UnsupportedOperationException();
   }

   public static boolean isRemoteMethod(Object method) {
      return method instanceof DynRemoteMethod;
   }

   public static Object[] getInstancesOfTypeAndSubTypes(String typeName) {
      if (dynamicSystem != null) {
         Object res = dynamicSystem.getInstancesOfTypeAndSubTypes(typeName);
         if (res instanceof Iterator) {
            ArrayList<Object> resList = new ArrayList<Object>();
            Iterator it = (Iterator) res;
            Object val;
            while (it.hasNext()) {
               resList.add(it.next());
            }
            return resList.toArray();
         }
         return (Object[]) res;
      }
      return null;
   }

   public static IDynChildManager getDynChildManager(Object type) {
      // TODO: these are right now only used on the client and here for the type systems to match.
      // There's a more efficient implementation though in BodyTypeDeclaration so we
      // could call through to the dynamicSystem and use that version if this does get used.
      String className = (String) getAnnotationValue(type, "sc.obj.CompilerSettings", "dynChildManager");
      if (className == null)
         return null;
      Object mgrType = findType(className);
      return (IDynChildManager) createInstance(mgrType, null);
   }

   public static void removeChild(Object parent, Object child) {
      Object type = DynUtil.getType(parent);
      IDynChildManager childMgr = getDynChildManager(type);
      if (childMgr == null)
         System.out.println("*** No DynChildManager registered for removeChild on parent type: " + type);
      else
         childMgr.removeChild(parent, child);
   }

   public static void addChild(Object parent, Object child) {
      Object type = DynUtil.getType(parent);
      IDynChildManager childMgr = getDynChildManager(type);
      if (childMgr == null)
         System.out.println("*** No DynChildManager registered for addChild on parent type: " + type);
      else
         childMgr.addChild(parent, child);
   }

   public static void addChild(int ix, Object parent, Object child) {
      Object type = DynUtil.getType(parent);
      IDynChildManager childMgr = getDynChildManager(type);
      if (childMgr == null)
         System.out.println("*** No DynChildManager registered for addChild on parent type: " + type);
      else
         childMgr.addChild(ix, parent, child);
   }

}