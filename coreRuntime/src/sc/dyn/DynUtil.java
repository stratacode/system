/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.dyn;

import sc.bind.Bind;
import sc.bind.BindingContext;
import sc.bind.MethodBinding;
import sc.obj.*;
import sc.sync.SyncDestination;
import sc.sync.SyncManager;
import sc.type.*;
import sc.util.IdentityWrapper;

import java.lang.reflect.Modifier;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Static utility methods used by runtime sc applications.  This class serves as a bridge from the runtime
 * world to the dynamic/interpreted world.  When the interpreter is enabled, dynamicSystem is set and
 * that is used for managing operations on dynamic objects.  When that is null, only the compile time features are
 * available.   Compiled classes can link against this class and then run either with or without the interpreter.
 */
@sc.js.JSSettings(jsLibFiles="js/scdyn.js", prefixAlias="sc_", usesJSFiles = "js/jvsys.js")
public class DynUtil {

   private DynUtil() {
   }

   public static IDynamicSystem dynamicSystem = null;

   static int traceCt = 0;

   // Stores the actual mapping cache.  Init this before referencing PTypeUtil since it uses this to create
   // it's types
   static Map<Class, DynType> mappingCache = new HashMap<Class, DynType>();

   static Map<Object,Integer> traceIds = (Map<Object,Integer>) PTypeUtil.getWeakHashMap();

   static Map<Object,String> scopeNames = (Map<Object,String>) PTypeUtil.getWeakHashMap();

   // A table for each type name which lists the current unique integer for that type that we use to automatically generate semi-unique ids for each instance
   static Map<String,Integer> typeIdCounts = new HashMap<String,Integer>();

   // Like typeIdCounts but used for building more readable traceIds for logging.  Unlike typeIdCounts, this one can be cleared without affecting the application
   static Map<Object,Integer> traceTypeIdCounts = (Map<Object,Integer>) PTypeUtil.getWeakHashMap();

   // A table which stores the automatically assigned id for a given object instance.
   //static Map<Object,String> objectIds = (Map<Object,String>) PTypeUtil.getWeakHashMap();
   // This used to be WeakHashMap but we use IdentityWrapper as a key most of the time which causes this to get gc'd too quickly
   static Map<Object,String> objectIds = new HashMap<Object,String>();

   public static void clearObjectIds() {
      objectIds.clear();
   }

   public static void flushCaches() {
      mappingCache.clear();
      scopeNames.clear();
   }

   public static DynType getPropertyCache(Class beanClass) {
      // There is a cycle initializing class variables - DynUtil
      if (mappingCache == null) {
         return null;
      }
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
         if (!(obj instanceof Class))
            throw new UnsupportedOperationException();
         Class cl = (Class) obj;
         int ct = 0;
         do {
            Class encl = cl.getEnclosingClass();
            if (encl == null || hasModifier(cl, "static")) {
               break;
            }
            ct++;
            cl = encl;
         } while (true);
         return ct;
      }
      else
         return dynamicSystem.getNumInnerTypeLevels(obj);
   }

   /** Walks up the object hierarchy until we hit a class or go off the top. */
   public static int getNumInnerObjectLevels(Object obj) {
      //if (!objectNameIndex.containsKey(obj))
      //   return 0; // Not an outer object
      Object outer = DynUtil.getOuterObject(obj);
      if (outer == null) {
         return 0; // Top level object - also not an inner object
      }
      return 1 + getNumInnerObjectLevels(outer);
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

   public static Object getPropertyType(Object objType, String propName) {
      IBeanMapper mapper = getPropertyMapping(objType, propName);
      return mapper == null ? null : mapper.getPropertyType();
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
         else if (type instanceof String) {
            Object resType = DynUtil.findType((String) type);
            if (resType != null)
               return resolvePropertyMapping(resType, dstPropName);
            throw new IllegalArgumentException("No type: " + type + " for resolvePropertyMapping of: " + dstPropName);
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
         return false;

      // Note: for the program editor, we use data binding expressions with TypeDeclaration... we can't have this returning 'obj' here as that will get confused as a type, not the instance we are actually using
      Object type1 = DynUtil.getSType(obj);
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
        return PTypeUtil.invokeMethod(obj, method, paramValues);
   }

   // TODO: Should this api itself be pluggable so we can use other RPC frameworks with data binding?  Or perhaps just make the sync framework itself pluggable for other RPC frameworks?
   /**
    * Here we are are invoking a generic remote method call.  The ScopeDefinition and ScopeContext can be null for defaults, but if specified select
    * a specific remote context in a specific remote process.  For example, a server running a method on a specific session or window that's connected.
    */
   public static RemoteResult invokeRemote(ScopeDefinition def, ScopeContext ctx, String destName, Object obj, Object method, Object... paramValues) {
      return SyncManager.invokeRemoteDest(def, ctx, destName, null, obj, DynUtil.getMethodType(method), DynUtil.getMethodName(method), DynUtil.getReturnType(method), DynUtil.getTypeSignature(method), paramValues);
   }

   public static Object invokeRemoteSync(ScopeDefinition def, ScopeContext ctx, String destName, long timeout, Object obj, Object method, Object... paramValues) {
      RemoteResult remoteRes = invokeRemote(def, ctx, destName, obj, method, paramValues);
      RemoteCallSyncListener listener = new RemoteCallSyncListener();
      remoteRes.responseListener = listener;

      long startTime = System.currentTimeMillis();

      // Need to run scopeChanged jobs here - so we notify the threads which will process the request
      DynUtil.execLaterJobs();

      CurrentScopeContext curScopeCtx = null;
      synchronized (listener) {
         try {
            if (!listener.complete) {
               curScopeCtx = CurrentScopeContext.getThreadScopeContext();
               if (curScopeCtx != null)
                  curScopeCtx.releaseLocks();
               listener.wait(timeout);
            }
         }
         catch (InterruptedException exc) {
            System.err.println("*** invokeRemoteSync of method: " + getMethodName(method) + " interrupted: " + exc);
         }
         finally {
            if (curScopeCtx != null)
               curScopeCtx.acquireLocks();
         }
      }
      Object evalRes = listener.result;
      long now = System.currentTimeMillis();
      if (listener.errorCode != null) {
         System.err.println("*** invokeRemoteSync of method: " + getMethodName(method) + " - returns error: " + listener.errorCode + ":" + listener.error + " after: " + (now - startTime) + " millis for: " + curScopeCtx);
         throw new IllegalArgumentException("invokeRemoteSync of method returns error: " + listener.errorCode + " for: " + curScopeCtx);
      }
      else if (!listener.success) {
         System.err.println("*** invokeRemoteSync of method: " + getMethodName(method) + "(" + Arrays.asList(paramValues) + ") - timed out after: " + (now - startTime) + " millis for: " + curScopeCtx);
         throw new IllegalArgumentException("invokeRemoteSync of method timed out: " + listener.errorCode + " for: " + curScopeCtx);
      }
      return evalRes;
   }

   /** In Java this is the same method but in Javascript they are different */
   public static Object resolveStaticMethod(Object type, String methodName, Object returnType, String paramSig) {
      return resolveMethod(type, methodName, returnType, paramSig);
   }

   public static Object resolveRemoteMethod(Object type, String methodName, Object retType, String paramSig) {
      DynRemoteMethod rm = new DynRemoteMethod();
      rm.type = type;
      rm.methodName = methodName;
      rm.returnType = retType;
      rm.paramSig = paramSig;
      return rm;
   }

   public static Object resolveRemoteStaticMethod(Object type, String methodName, Object retType, String paramSig) {
      DynRemoteMethod rm = new DynRemoteMethod();
      rm.type = type;
      rm.methodName = methodName;
      rm.returnType = retType;
      rm.paramSig = paramSig;
      rm.isStatic = true;
      return rm;
   }

   public static Object resolveMethod(Object type, String methodName, Object returnType, String paramSig) {
      if (type instanceof Class)
         return PTypeUtil.resolveMethod((Class) type, methodName, returnType, paramSig);
      else if (type instanceof DynType)
         return ((DynType) type).getMethod(methodName, paramSig);
      else if (dynamicSystem != null)
         return dynamicSystem.resolveMethod(type, methodName, returnType, paramSig);
      else if (type instanceof String) {
         Object resType = DynUtil.findType((String) type);
         if (resType != null)
            return resolveMethod(resType, methodName, returnType, paramSig);
         else
            throw new IllegalArgumentException("No type: " + type + " to resolveMethod for: " + methodName);
      }
      else {
         throw new IllegalArgumentException("Unrecognized type to resolveMethod: " + type);
      }
   }

   public static String getMethodName(Object method) {
      if (method instanceof DynRemoteMethod)
         return ((DynRemoteMethod) method).methodName;
      if (dynamicSystem != null)
         return dynamicSystem.getMethodName(method);
      else
         return PTypeUtil.getMethodName(method);
   }
   public static Object getMethodType(Object method) {
      if (method instanceof DynRemoteMethod)
         return ((DynRemoteMethod) method).type;
      if (dynamicSystem != null)
         return dynamicSystem.getDeclaringClass(method);
      else
         return PTypeUtil.getMethodType(method);
   }

   public static String getPropertyName(Object prop) {
      if (dynamicSystem != null)
         return dynamicSystem.getPropertyName(prop);
      else
         return PTypeUtil.getPropertyName(prop);
   }

   public static Object getPropertyType(Object prop) {
      if (prop instanceof IBeanMapper)
         return ((IBeanMapper) prop).getPropertyType();
      if (dynamicSystem != null)
         return dynamicSystem.getPropertyType(prop);
      else
         return PTypeUtil.getPropertyName(prop);
   }

   public static String getTypeSignature(Object method) {
      if (method instanceof DynRemoteMethod)
         return ((DynRemoteMethod) method).paramSig;
      if (dynamicSystem != null)
         return dynamicSystem.getMethodTypeSignature(method);
      else
         return null;
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
    * Unlike isType, treats TypeDeclaration's as non-types so they can be serialized across the wire as objects in
    * the dynamic runtime in some cases
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
      Object o = p.getProperty(propName, false);
      return o == null ? 0 : ((Number) o).intValue();
   }

   public static float floatPropertyValue(IDynObject p, String propName) {
      Object o = p.getProperty(propName, false);
      return o == null ? 0 : ((Number) o).floatValue();
   }

   public static double doublePropertyValue(IDynObject p, String propName) {
      Object o = p.getProperty(propName, false);
      return o == null ? 0 : ((Number) o).doubleValue();
   }

   public static long longPropertyValue(IDynObject p, String propName) {
      Object o = p.getProperty(propName, false);
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

   public static String[] getPropertyNames(Object typeObj) {
      IBeanMapper[] mappers = getProperties(typeObj);
      if (mappers == null)
         return null;

      int len = mappers.length;
      ArrayList<String> res = new ArrayList<String>(len);
      for (int i = 0; i < len; i++) {
         IBeanMapper mapper = mappers[i];
         if (mapper != null) {
            String name = mapper.getPropertyName();
            // Right now this is only used for user-visible properties so removing this one, but maybe it should be settable via EditorSettings to hide this property in some base class
            if (name.equals("class"))
               continue;
            res.add(name);
         }
      }
      return res.toArray(new String[res.size()]);
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

   public static Object getPropertyValue(Object object, String propertyName, boolean ignoreError) {
      try {
         return getPropertyValue(object, propertyName);
      }
      catch (IllegalArgumentException exc) {
         if (!ignoreError)
            throw exc;
      }
      return null;
   }

   public static Object getPropertyValue(Object object, String propertyName) {
      if (object instanceof IDynObject)
         return ((IDynObject) object).getProperty(propertyName, false);
      else if (object instanceof Map)
         return ((Map) object).get(propertyName);
      else
         return TypeUtil.getPropertyValue(object, propertyName);
   }

   public static Object getProperty(Object object, String propertyName) {
      return getProperty(object, propertyName, false);
   }

   /**
    * Like getPropertyValue this works for static properties, but breaks if the TypeDeclaration object is used as an object instance.
    * That case is where the type declaration represents the class for a static property.
    */
   public static Object getProperty(Object object, String propertyName, boolean getField) {
      if (object instanceof IDynObject)
         return ((IDynObject) object).getProperty(propertyName, getField);
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
         Object[] useParams;
         if (outerObj != null) {
            int len;
            if (params == null)
               len = 0;
            else
               len = params.length;
            useParams = new Object[len+1];
            useParams[0] = outerObj;
            if (params != null)
               System.arraycopy(params, 0, useParams, 1, params.length);
         }
         else
            useParams = params;
         return PTypeUtil.createInstance((Class) typeObj, constrSig, useParams);
      }
      else
         throw new IllegalArgumentException("Invalid dynamic type: " + typeObj);
   }

   public static Object newInnerInstance(Object typeObj, Object outerObj, String constrSig, Object...params) {
      if (dynamicSystem != null)
         return dynamicSystem.newInnerInstance(typeObj, outerObj, constrSig, params);
      else if (typeObj instanceof Class) {
         if (isComponentType(typeObj)) {
            String typeName = getTypeName(typeObj, false);
            String methodName = "new" + CTypeUtil.capitalizePropertyName(CTypeUtil.getClassName(typeName));
            Object newMeth = resolveMethod(typeObj, methodName, null, constrSig);
            if (newMeth !=  null) {
               return invokeMethod(outerObj, newMeth, params);
            }
            else
               throw new IllegalArgumentException("Missing new method for component type: " + typeName);
         }
         else {
            return createInnerInstance(typeObj, outerObj, constrSig, params);
         }
      }
      else
         throw new UnsupportedOperationException();
   }

   /** Use this method to create an instance that might be an @Component class - so that we call the init and start methods */
   public static Object newInnerComponent(Object typeObj, Object outerObj, String constrSig, Object...params) {
      Object res = newInnerInstance(typeObj, outerObj, constrSig, params);
      if (res == null)
         return res;
      if (isComponentType(typeObj)) {
         DynUtil.initComponent(res);
         DynUtil.startComponent(res);
      }
      return res;
   }

   public static void addDynObject(String typeName, Object instObj) {
      if (dynamicSystem != null)
         dynamicSystem.addDynObject(typeName, instObj);
   }

   public static void addDynInnerObject(String typeName, Object innerObj, Object outerObj) {
      if (dynamicSystem != null)
         dynamicSystem.addDynInnerObject(typeName, innerObj, outerObj);
   }

   public static String getObjectName(Object obj) {
      return getObjectName(obj, objectIds, typeIdCounts);
   }

   // In JS, this will return a property we store on the object.
   public static String getObjectName(Object obj, Map<Object,String> idMap, Map<String,Integer> typeIdCounts) {
      if (dynamicSystem != null)
         return dynamicSystem.getObjectName(obj, idMap, typeIdCounts);

      Object outer = DynUtil.getOuterObject(obj);
      if (outer == null) {
         Object typeObj = DynUtil.getSType(obj);
         if (DynUtil.isObjectType(typeObj))
            return DynUtil.getTypeName(typeObj, false);
         return DynUtil.getInstanceId(objectIds, typeIdCounts, obj);
      }
      else {
         String outerName = DynUtil.getObjectName(outer, idMap, typeIdCounts);
         String typeClassName = CTypeUtil.getClassName(DynUtil.getTypeName(DynUtil.getType(obj), false));
         String objTypeName = outerName + "." + typeClassName;
         if (DynUtil.isObjectType(DynUtil.getSType(obj))) {
            return objTypeName;
         }
         if (obj instanceof IObjectId)
            return outerName + "." + DynUtil.getInstanceId(objectIds, typeIdCounts, obj);

         // Let the parent provide the name for the child - used for repeating components or others that
         // will have dynamically created child objects
         if (outer instanceof INamedChildren) {
            String childName = ((INamedChildren) outer).getNameForChild(obj);
            if (childName != null)
               return outerName + "." + childName;
         }
         return DynUtil.getObjectId(obj, null, objTypeName, objectIds, typeIdCounts);
      }
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

   public static void addDynListener(IDynListener listener) {
      if (dynamicSystem != null) {
         dynamicSystem.addDynListener(listener);
      }
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
         return null;
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

   /** Returns a small but consistent id for each object - using the objectId if it's set just for consistency and ease of debugging */
   public static String getTraceId(Object obj) {
      Integer id;
      IdentityWrapper wrap = new IdentityWrapper(obj);
      if ((id = traceIds.get(wrap)) == null)
         traceIds.put(wrap, id = getTypeTraceCount(DynUtil.getType(obj)));

      return String.valueOf(id);
   }

   /** Gets the trace id without the identity wrapper, for things like session ids which should use 'equals' rather than object identity to match the same trace id */
   public static String getTraceObjId(Object obj) {
      Integer id;
      if ((id = traceIds.get(obj)) == null)
         traceIds.put(obj, id = getTypeTraceCount(DynUtil.getType(obj)));

      return String.valueOf(id);
   }

   private static Integer getTypeTraceCount(Object type) {
      Integer typeId = traceTypeIdCounts.get(type);
      if (typeId == null) {
         traceTypeIdCounts.put(type, 1);
         typeId = 0;
      }
      else {
         traceTypeIdCounts.put(type, typeId + 1);
      }
      return typeId;
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

   public static String getInstanceId(Object obj) {
      return getInstanceId(objectIds, typeIdCounts, obj);
   }

   /** Returns a unique id for the given object.  Types return the type name.  The id will otherwise by type-id__integer */
   public static String getInstanceId(Map<Object,String> idMap, Map<String,Integer> typeIdCounts, Object obj) {
      if (obj == null)
         return null;
      if (obj instanceof Class)
         return cleanTypeName(((Class) obj).getName());
      if (DynUtil.isType(obj)) {
         // Type declarations put in the layer after the type name.  Need to strip that out.
         String s = DynUtil.getInnerTypeName(obj);
         // NOTE: sc_type_ is hard-coded elsewhere - search for it to find all usages.  Need to turn this into an id because we may have mroe than one type for the same type name.
         return getObjectId(obj, obj, "sc_type_" + s.replace('.', '_'), idMap, typeIdCounts);
      }
      else {
         if (obj instanceof IObjectId) {
            return ((IObjectId) obj).getObjectId();
         }

         Object type = DynUtil.getType(obj);

         if (DynUtil.isEnumConstant(obj)) {
            // For Java classes, if there's a class for the enum instance it will be anonymous and like: EnumClass$1
            if (type instanceof Class) {
               Class cl = (Class) type;
               if (cl.isAnonymousClass())
                  type = cl.getEnclosingClass();
            }
            return DynUtil.getTypeName(type, false) + "." + obj.toString();
         }

         return getObjectId(obj, type, null, idMap, typeIdCounts);
      }
   }

   public static String getObjectId(Object obj, Object type, String typeName) {
      return getObjectId(obj, type, typeName, objectIds, typeIdCounts);
   }

   public static String getObjectId(Object obj, Object type, String typeName, Map<Object,String> idMap, Map<String,Integer> typeIdCount) {
      String objId = idMap.get(new IdentityWrapper(obj));
      if (objId != null)
         return objId;

      if (typeName == null)
         typeName = DynUtil.getTypeName(type, true);
      String typeIdStr = "__" + getTypeIdCount(typeName, typeIdCount);
      String id = cleanTypeName(typeName) + typeIdStr;
      idMap.put(new IdentityWrapper(obj), id);
      return id;
   }

   public static void setObjectId(Object obj, String name) {
      objectIds.put(new IdentityWrapper(obj), name);
   }

   public static Integer getTypeIdCount(String typeName, Map<String,Integer> typeIdCounts) {
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

   public static void updateTypeIdCount(String typeName, int val) {
      typeIdCounts.put(typeName, val);
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
            String res;
            if (PTypeUtil.isPrimitive(objClass) || (theType = Type.get(objClass)).isANumber() || theType.primitiveClass != null || objClass == Date.class)
               return obj.toString();
            else if (objClass == String.class)
               return "\"" + obj.toString() + "\"";
            else if (obj.getClass().isArray()) {
               return TypeUtil.getArrayName(obj);
            }
            else if (isObject(obj) && (res = getObjectName(obj, objectIds, typeIdCounts)) != null)
               return res;

            String objId = objectIds.get(new IdentityWrapper(obj));
            if (objId != null)
               return objId;

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
      if (arrVal == null)
         return 0;
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
         if (obj instanceof IStoppable)
            ((IStoppable) obj).stop();
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

      objectIds.remove(new IdentityWrapper(obj));
      scopeNames.remove(obj);
   }

   public static void disposeLater(Object component, boolean disposeChildren) {
      invokeLater(new Runnable() {
         public void run() {
            DynUtil.dispose(component, disposeChildren);
         }
      }, 0);
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
      return resolveName(name, create, true);
   }

   public static Object resolveName(String name, boolean create, boolean returnTypes) {
      if (dynamicSystem != null)
         return dynamicSystem.resolveRuntimeName(name, create, returnTypes);
      else {
         Object type = findType(name);
         if (type != null) {
            String propName = CTypeUtil.getClassName(name);
            propName = CTypeUtil.decapitalizePropertyName(propName);
            return getStaticProperty(type, propName);
         }
         return null;
      }
   }

   public static Object findType(String typeName) {
      Type type = Type.getPrimitiveType(typeName);
      if (type != null)
         return type.primitiveClass;
      if (dynamicSystem != null)
         return dynamicSystem.findType(typeName);
      return PTypeUtil.findType(typeName);
   }

   public static boolean isEvalSupported() {
      return false;
   }

   private static class RemoteCallSyncListener implements sc.type.IResponseListener {
      Object result = null;
      Object error = null;
      Integer errorCode = null;
      boolean success = false;
      boolean complete = false;
      public synchronized void response(Object response) {
         result = response;
         success = true;
         complete = true;
         notify();
      }
      public synchronized void error(int errorCode, Object error) {
         this.errorCode = errorCode;
         this.error = error;
         complete = true;
         notify();
      }
   }

   /** Executes the supplied java script by making an RPC call targeted towards all clients with the lifecycle identified by ScopeContext */
   public static Object evalRemoteScript(ScopeContext ctx, String script) {
      SyncDestination dest = SyncDestination.defaultDestination;
      return invokeRemoteSync(null, ctx, dest.name, dest.defaultTimeout, null, DynUtil.resolveRemoteStaticMethod(DynUtil.class, "evalScript", null, "Ljava/lang/String;"), script);
   }

   public static Object evalScript(String script) {
      return evalRemoteScript(null, script);
   }

   public static boolean applySyncLayer(String lang, String destName, String scopeName, String code, boolean applyRemoteReset, boolean allowCodeEval, BindingContext ctx) {
      if (dynamicSystem != null)
         return dynamicSystem.applySyncLayer(lang, destName, scopeName, code, applyRemoteReset, allowCodeEval, ctx);
      else
         throw new UnsupportedOperationException(("Attempt to evalCode without a dynamic runtime"));
   }

   public static boolean isObjectType(Object type) {
      if (dynamicSystem != null)
         return dynamicSystem.isObjectType(type);
      return PTypeUtil.isObjectType(type);
   }

   public static boolean isSingletonType(Object type) {
      String scopeName = DynUtil.getScopeNameForType(type);
      if (scopeName == null)
         return false;
      return true; // TODO: Any scopes which we should not treat as singletons?
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

   public static boolean isEnumType(Object type) {
      if (type instanceof Class) {
         Class cl = (Class) type;
         if (cl.isEnum())
            return true;
         boolean res = ((Class<?>) type).isAssignableFrom(Enum.class);
         if (res)
            System.out.println("*** weird return for isEnumType");
         return res;
      }
      if (dynamicSystem != null)
         return dynamicSystem.isEnumType(type);
      return false;
   }

   public static Object[] getEnumConstants(Object enumType) {
      if (enumType instanceof Class)
         return ((Class) enumType).getEnumConstants();
      else
         throw new UnsupportedOperationException();
   }

   public static Object getEnumConstant(Object typeObj, String enumConstName) {
      if (dynamicSystem != null)
         return dynamicSystem.getEnumConstant(typeObj, enumConstName);
      else {
         if (typeObj instanceof Class) {
            Class cl = (Class) typeObj;
            if (cl.isEnum()) {
               Object[] enumConsts = cl.getEnumConstants();
               for (Object econst:enumConsts) {
                  Enum e = (Enum) econst;
                  if (e.name().equals(enumConstName))
                     return e;
               }
               throw new IllegalArgumentException("*** Missing enumConstant: " + enumConstName + " for getEnumConstant on type: " + typeObj);
            }
         }
         throw new IllegalArgumentException("*** Invalid type for getEnumConstant");
      }
   }

   public static String getEnumName(Object enumObj) {
      return enumObj.toString();
   }

   public static IScheduler frameworkScheduler;
   public static ThreadLocal<IScheduler> threadScheduler = new ThreadLocal<IScheduler>();

   public static void setThreadScheduler(IScheduler sched) {
      threadScheduler.set(sched);
   }

   /**
    * Runs at the next opportunity as determined by the ThreadScheduler or the framework scheduler.
    * Higher priority jobs run before lower priority ones
    */
   public static ScheduledJob invokeLater(Runnable r, int priority) {
      IScheduler sched = threadScheduler.get();
      if (sched != null) {
         return sched.invokeLater(r, priority);
      }
      if (frameworkScheduler == null)
         throw new IllegalArgumentException("Must set DynUtil.frameworkScheduler before calling invokeLater");
      return frameworkScheduler.invokeLater(r, priority);
   }

   public static boolean clearInvokeLater(ScheduledJob job) {
      IScheduler sched = threadScheduler.get();
      if (sched != null) {
         return sched.clearInvokeLater(job);
      }
      if (frameworkScheduler == null)
         throw new IllegalArgumentException("Must set DynUtil.frameworkScheduler before calling invokeLater");
      return frameworkScheduler.clearInvokeLater(job);
   }

   public static void execLaterJobs() {
      execLaterJobs(IScheduler.NO_MIN, IScheduler.NO_MAX);
   }

   public static void execLaterJobs(int minPriority, int maxPriority) {
      IScheduler sched = threadScheduler.get();
      if (sched != null) {
         sched.execLaterJobs(minPriority, maxPriority);
         return;
      }
      if (frameworkScheduler == null)
         return;
      frameworkScheduler.execLaterJobs(minPriority, maxPriority);
   }

   public static boolean hasPendingJobs() {
      IScheduler sched = threadScheduler.get();
      if (sched != null)
         return sched.hasPendingJobs();
      return frameworkScheduler == null ? false : frameworkScheduler.hasPendingJobs();
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

   public static boolean hasAnnotation(Object typeObj, String annotName) {
      return getAnnotation(typeObj, annotName) != null;
   }

   public static Object getAnnotation(Object typeObj, String annotName) {
      if (typeObj instanceof Class) {
         return PTypeUtil.getAnnotation((Class) typeObj, annotName);
      }
      else if (dynamicSystem != null) {
         return dynamicSystem.getAnnotationByName(typeObj, annotName);
      }
      else throw new UnsupportedOperationException();
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

   public static Object getPropertyAnnotationValue(Object typeObj, String propName, String annotName, String attName) {
      if (dynamicSystem != null) {
         return dynamicSystem.getPropertyAnnotationValue(typeObj, propName, annotName, attName);
      }
      else if (typeObj instanceof Class) {
         Object prop = DynUtil.getPropertyMapping(typeObj, propName);
         if (prop != null) {
            Object annot = PTypeUtil.getAnnotation(prop, annotName);
            if (annot != null) {
               return PTypeUtil.getValueFromAnnotation(annot, attName);
            }
         }
      }
      throw new UnsupportedOperationException();
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
      if (scopeNames.containsKey(obj)) // might be null
         return scopeNames.get(obj);
      Object typeObj = DynUtil.getType(obj);
      String res = getScopeNameForType(typeObj);
      if (res != null)
         return res;
      Object outer = DynUtil.getOuterObject(obj);
      if (outer != null) {
         res = getScopeName(outer);
         // storing null if there's no scope name assigned
         scopeNames.put(obj, res);
         return res;
      }
      return null;
   }

   public static String getScopeNameForType(Object typeObj) {
      String scopeName = scopeNames.get(typeObj);
      if (scopeName != null)
         return scopeName;
      if (dynamicSystem != null) {
         // Need to check both the sc.obj.Scope and scope<name> in tandem so each can override the other
         scopeName = dynamicSystem.getInheritedScopeName(typeObj);
         if (scopeName != null) {
            scopeNames.put(typeObj, scopeName);
            return scopeName;
         }
      }
      else {
         Object scopeNameObj = getInheritedAnnotationValue(typeObj, "sc.obj.Scope", "name");
         if (scopeNameObj != null) {
            scopeName = (String) scopeNameObj;
            scopeNames.put(typeObj, scopeName);
            return scopeName;
         }
      }
      return null;
   }

   public static ScopeDefinition getScopeByName(String scopeName) {
      if (dynamicSystem != null)
         return dynamicSystem.getScopeByName(scopeName);
      return ScopeDefinition.getScopeByName(scopeName);
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
      String className = (String) getInheritedAnnotationValue(type, "sc.obj.CompilerSettings", "dynChildManager");
      if (className == null || className.length() == 0)
         return null;
      Object mgrType = findType(className);
      if (mgrType == null) {
         System.err.println("*** Missing dynChildManager class: " + className + " as set by CompilerSettings on: " + type);
         return null;
      }
      return (IDynChildManager) createInstance(mgrType, null);
   }

   public static void removeChild(Object parent, Object child) {
      Object type = DynUtil.getType(parent);
      IDynChildManager childMgr = getDynChildManager(type);
      if (childMgr != null)
         childMgr.removeChild(parent, child);
   }

   public static void addChild(Object parent, Object child) {
      Object type = DynUtil.getType(parent);
      IDynChildManager childMgr = getDynChildManager(type);
      if (childMgr != null)
         childMgr.addChild(parent, child);
   }

   public static void addChild(int ix, Object parent, Object child) {
      Object type = DynUtil.getType(parent);
      IDynChildManager childMgr = getDynChildManager(type);
      if (childMgr != null)
         childMgr.addChild(ix, parent, child);
   }

   public static int getLayerPosition(Object type) {
      if (dynamicSystem == null)
         return -1;
      return dynamicSystem.getLayerPosition(type);
   }

   /** Call this method to be notified when dynamic types change */
   public static void registerTypeChangeListener(ITypeChangeListener listener) {
      if (dynamicSystem != null)
         dynamicSystem.registerTypeChangeListener(listener);
   }

   public static boolean isComponentType(Object type) {
      if (dynamicSystem != null)
         return dynamicSystem.isComponentType(type);
      else if (type instanceof Class) {
         Class cl = (Class) type;
         return IComponent.class.isAssignableFrom(cl) || IAltComponent.class.isAssignableFrom(cl) || IDynComponent.class.isAssignableFrom(cl);
      }
      else
         throw new UnsupportedOperationException();
   }

   public static boolean isArray(Object type) {
      if (dynamicSystem != null)
         return dynamicSystem.isArray(type);
      if (type instanceof Class) {
         return PTypeUtil.isArray(type);
      }
      throw new UnsupportedOperationException();
   }

   public static Object getComponentType(Object type) {
      if (dynamicSystem != null)
         return dynamicSystem.getComponentType(type);
      if (type instanceof Class) {
         return PTypeUtil.getComponentType(type);
      }
      throw new UnsupportedOperationException();
   }

   public static boolean hasProperty(Object obj, String propName) {
      if (obj == null)
          return false;
      return getPropertyMapping(getType(obj), propName) != null;
   }

   public static Object getTypeOfObj(Object changedObj) {
      return DynUtil.isType(changedObj) ? changedObj.getClass() : DynUtil.getType(changedObj);
   }

   /** Don't put the ugly thread ids into the logs - normalize them with an incremending integer */
   public static String getCurrentThreadString() {
      return DynUtil.getTraceObjId(Thread.currentThread());
   }

   public static boolean needsSync(Object type) {
      if (dynamicSystem != null)
         return dynamicSystem.needsSync(type);
      else {
         // TODO: this should be possible using the SyncManager since all sync types will be
         // registered.  Will have to include nested types/instances like needsSync() does.
         System.err.println("*** Need to implement needsSync without a dynamic system");
         return true;
      }
   }

   public static Object findCommonSuperType(Object c1, Object c2) {
      Object o1 = c1;
      Object o2 = c2;

      if (o1 == null && o2 != null)
         return o2;
      if (o2 == null && o1 != null)
         return o1;

      while (o1 != null && o2 != null && !DynUtil.isAssignableFrom(o1, o2))
         o1 = DynUtil.getExtendsType(o1);

      while (c1 != null && o2 != null && !DynUtil.isAssignableFrom(o2, c1))
         o2 = DynUtil.getExtendsType(o2);

      return o1 != null && o2 != null && DynUtil.isAssignableFrom(o1, o2) ? o2 : o1;
   }

   public static int compare(Object o1, Object o2) {
      int res;
      if (o1 instanceof Comparable) {
         res = ((Comparable) o1).compareTo(o2);
      }
      else if (o2 instanceof Comparable) {
         res = -((Comparable) o2).compareTo(o1);
      }
      else {
         System.err.println("*** Unable to compare values: " + o1 + " to " + o2);
         res = 0;
      }
      return res;
   }

   public static void addSystemExitListener(ISystemExitListener sys) {
      if (dynamicSystem != null)
         dynamicSystem.addSystemExitListener(sys);
      else // TODO: add a hook for this so it can be implemented for each runtime
         throw new UnsupportedOperationException();
   }

   /** Parse dates in 8601 format (there's a Javascript version of this method for client/server code) */
   public static Date parseDate(String dateStr) {
      return Date.from(Instant.parse(dateStr));
   }

   /** Format dates in 8601 format (there's a JS version of this method too) */
   public static String formatDate(Date date) {
      return date.toInstant().atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
      /*
      TimeZone tz = TimeZone.getTimeZone("UTC");
      DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'"); // Quoted "Z" to indicate UTC, no timezone offset
      df.setTimeZone(tz);
      return df.format(date);
      */
   }

   public static boolean isImmutableObject(Object obj) {
      if (obj instanceof String || obj instanceof Number || obj instanceof Boolean)
         return true;
      return false;
   }

   public static Map<String,String> validateProperties(Object obj, List<String> propNames) {
      IBeanMapper[] props = DynUtil.getProperties(DynUtil.getType(obj));
      TreeMap<String,String> resMap = null;
      for (IBeanMapper prop: props) {
         if (prop == null)
            continue;
         String propName = prop.getPropertyName();
         if (propName == null)
            continue;
         if (propNames == null || propNames.contains(propName)) {
            Object validateMethod = prop.getValidateMethod();
            if (validateMethod == null) {
               if (propNames != null)
                  throw new IllegalArgumentException("Specified property: " + propName + " has no validate" + CTypeUtil.capitalizePropertyName(propName) + "() method");
               continue;
            }
            Object[] paramTypes = DynUtil.getParameterTypes(validateMethod);
            Object res;
            if (paramTypes.length == 0) {
               res = DynUtil.invokeMethod(obj, validateMethod);
            }
            else if (paramTypes.length == 1) {
               res = DynUtil.invokeMethod(obj, validateMethod, DynUtil.getProperty(obj, propName));
            }
            else {
               throw new IllegalArgumentException("Invalid validator: " + validateMethod + " - should have zero or one parameter");
            }
            if(res != null) {
               if (resMap == null)
                  resMap = new TreeMap<String,String>();
               resMap.put(propName, res.toString());
            }
         }
      }
      return resMap;
   }
}
