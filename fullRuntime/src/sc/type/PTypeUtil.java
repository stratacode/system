/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.type;

import sc.bind.MethodBinding;
import sc.dyn.DynRemoteMethod;
import sc.dyn.IReverseMethodMapper;
import sc.js.JSSettings;
import sc.util.StringUtil;
import sc.util.WeakIdentityHashMap;
import sc.dyn.DynUtil;
import sc.dyn.RDynUtil;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.locks.Lock;

/** This is the version of the PTypeUtil utilities that is used in the full runtime, with Java reflection etc.  */
@JSSettings(jsLibFiles="js/scdyn.js")
public class PTypeUtil {
   public static ThreadLocal<Map<String,Object>> threadLocalMap = new ThreadLocal<Map<String,Object>>();
   // Slot 0 is reserved for object/class value listeners.
   public final static int MIN_PROPERTY = 1;

   // Flags which will mirror the system values on both client and server
   public static boolean verbose, testMode, testVerifyMode;

   public static int numClassesCached = 0;
   public static int numFieldsCached = 0;
   public static int numMethodsCached = 0;
   public static int numInterfacePropsCached = 0;
   public static int numPropsInherited = 0;

   // Old setting - set to true to debug errors encountered trying to resolve properties from types, e.g. properties discarded because of conflicting return types.
   public static boolean trace = false;

   public static ClassLoader defaultClassLoader = null;

   public static IBeanMapper getListConverter(BeanMapper mapper, Class propertyClass, Class valueClass) {
      // Converts List<?> to List<String>
      if (propertyClass == String.class) {
         return new BeanMapper(mapper) {
            public void setPropertyValue(Object parent, Object value) {
               List oldList = (List) value;
               ArrayList newList = new ArrayList(oldList.size());
               for (Object e:oldList)
                   newList.add(e.toString());
               TypeUtil.setProperty(parent, setSelector, newList);
            }
            public String toString() {
               return "List component toString converter for " + getPropertyName();
            }
         };
      }
      return null;
   }

   public static IBeanMapper getBeanConverterMapper(final BeanMapper mapper, Class propertyClass, Class valueClass) {
      if (propertyClass == String.class) {
         return new BeanMapper(mapper) {
            public void setPropertyValue(Object parent, Object value) {
               if (value != null)
                   value = value.toString();
               TypeUtil.setProperty(parent, setSelector, value);
            }
            public String toString() {
               return "toString converter for " + getPropertyName();
            }
         };
      }
      else if (propertyClass == List.class) {
         /* If this is an array we perform the array to value mapping */
         if (valueClass.isArray()) {
            return new BeanMapper(mapper) {
               public Object getPropertyValue(Object parent, boolean getField, boolean pendingChild) {
                  List<?> lval = (List<?>) TypeUtil.getPropertyValue(parent, getSelector, getField);
                  return lval.toArray((Object[]) Array.newInstance(mapper.getComponentType(), lval.size()));
               }
               public void setPropertyValue(Object parent, Object value) {
                  int len = Array.getLength(value);
                  ArrayList l = new ArrayList(len);
                  for (int i = 0; i < len; i++) {
                     l.add(Array.get(value, i));
                  }
                  mapper.setPropertyValue(parent, l);
               }
               public String toString() {
                  return "arrayToList for " + getPropertyName();
               }
            };
         }
         else {
            /* Allow a list of length 1 to be treated as a scalar */
            return new BeanMapper(mapper) {
               public Object getPropertyValue(Object parent, boolean getField, boolean pendingChild) {
                  // Undo the conversion here.  If we use a slot mapping to wrap a list on the way in
                  // we need to undo it on the way out so that we get the right data type for generation
                  // purposes.
                  Object pval = TypeUtil.getPropertyValue(parent, getSelector, getField);
                  if (pval instanceof List) {
                     List lval = (List) pval;
                     if (lval.size() == 1)
                        return lval.get(0);
                  }
                  return pval;
               }

               public boolean isScalarToList() {
                  return true;
               }

               public void setPropertyValue(Object parent, Object value) {
                  if (value != null)
                     value = Collections.singletonList(value);
                  TypeUtil.setProperty(parent, setSelector, value);
               }
               public String toString() {
                  return "toSingletonList converter for " + getPropertyName();
               }
            };
         }
      }
      else if (propertyClass == Set.class) {
         /* If this is an array we perform the array to value mapping */
         if (valueClass.isArray()) {
            return new BeanMapper(mapper) {
               public Object getPropertyValue(Object parent, boolean getField, boolean pendingChild) {
                  Set<?> lval = (Set<?>) TypeUtil.getPropertyValue(parent, getSelector, getField);
                  return lval.toArray((Object[])Array.newInstance(mapper.getComponentType(), lval.size()));
               }
               public void setPropertyValue(Object parent, Object value) {
                  int len = Array.getLength(value);
                  LinkedHashSet l = new LinkedHashSet(len);
                  for (int i = 0; i < len; i++) {
                     l.add(Array.get(value, i));
                  }
                  mapper.setPropertyValue(parent, l);
               }
               public String toString() {
                  return "arraySet for " + getPropertyName();
               }
            };
         }
      }
      /*
       * For setting boolean's any non-null value sets it to true.  This is convenient for the semantic mapping
       * done in the parser but not if it is a boolean false.
       */
      else if (propertyClass == Boolean.class || propertyClass == Boolean.TYPE) {
         return new BeanMapper(mapper) {
            public void setPropertyValue(Object parent, Object value) {
               TypeUtil.setProperty(parent, setSelector, value != null && (!(value instanceof Boolean) || ((Boolean) value)));
            }
            public String toString() {
               return "boolean converter for " + getPropertyName();
            }
         };
      }
      else if (propertyClass == Integer.class || propertyClass == Integer.TYPE) {
         return new BeanMapper(mapper) {
            public void setPropertyValue(Object parent, Object value) {
               Integer intVal = null;
               if (value != null) {
                  String val = value.toString();
                  try {
                     intVal = Integer.parseInt(val);
                  }
                  catch (NumberFormatException exc) {
                     throw new IllegalArgumentException("Invalid integer for set property of: " + parent + "." + getPropertyName() + " value: '" + val + "' is not an integer" + exc);
                  }
               }
               TypeUtil.setProperty(parent, setSelector, intVal);
            }
            public String toString() {
               return "boolean converter for " + getPropertyName();
            }
         };
      }
      if (IValueConverter.class.isAssignableFrom(valueClass)) {
         return new BeanMapper() {
            BeanMapper superMapper = mapper;
            public Object getPropertyValue(Object parent, boolean getField, boolean pendingChild) {
               Object propVal = superMapper.getPropertyValue(parent, getField, false);
               // TODO: create an AbstractLiteral here somehow from the value.
               return propVal;
            }

            public void setPropertyValue(Object parent, Object value) {
               Object cvtVal = ((IValueConverter) value).getConvertedValue();
               superMapper.setPropertyValue(parent, cvtVal);
            }
         };
      }
      return null;
   }

   public static IBeanMapper getPropertyMapping(Class beanClass, String propName) {
      DynType cache = TypeUtil.getPropertyCache(beanClass);
      return cache.getPropertyMapper(propName);
   }

   /** Abstraction around thread local cause GWT does not implement it */
   public static Object setThreadLocal(String key, Object value) {
      Map<String,Object> localMap = threadLocalMap.get();
      if (localMap == null)
         threadLocalMap.set(localMap = new HashMap<String,Object>());
      return localMap.put(key, value);
   }

   public static void clearThreadLocal(String key) {
      Map<String,Object> localMap = threadLocalMap.get();
      if (localMap == null)
         return;
      localMap.remove(key);
      if (localMap.size() == 0)
         threadLocalMap.remove();
   }

   public static Object getThreadLocal(String key) {
      Map<String,Object> localMap = threadLocalMap.get();
      if (localMap == null)
         return null;
      return localMap.get(key);
   }

   public static Map getWeakHashMap() {
      return new WeakHashMap();
   }

   public static Map getWeakIdentityHashMap() {
      return new WeakIdentityHashMap();
   }

   public static int getModifiers(Object def) {
      if (def instanceof Member)
         return ((Member) def).getModifiers();
      else if (def instanceof Class)
         return ((Class) def).getModifiers();
      else if (def instanceof DynRemoteMethod) {
         DynRemoteMethod dynMeth = (DynRemoteMethod) def;
         int res = Modifier.PUBLIC;
         if (dynMeth.isStatic)
            res |= Modifier.STATIC;
         return res;
      }
      throw new UnsupportedOperationException();
   }

   public static boolean hasModifier(Object def, String s) {
      int modifiers = getModifiers(def);
      switch (s.charAt(0)) {
         case 'p':
            switch (s.charAt(2)) {
               case 'b':
                  return Modifier.isPublic(modifiers);
               case 'o':
                  return Modifier.isProtected(modifiers);
               case 'i':
                  return Modifier.isPrivate(modifiers);
               default:
                  throw new UnsupportedOperationException();
            }
         case 'a':
            return Modifier.isAbstract(modifiers);
         case 's':
            switch (s.charAt(2)) {
               case 'a':
                  return Modifier.isStatic(modifiers);
               case 'n':
                  return Modifier.isSynchronized(modifiers);
               case 'r':
                  return Modifier.isStrict(modifiers);
               default:
                  throw new UnsupportedOperationException();
            }
         case 'f':
            return Modifier.isFinal(modifiers);
         case 't':
            return Modifier.isTransient(modifiers);
         case 'v':
            return Modifier.isVolatile(modifiers);
         case 'n':
            return Modifier.isNative(modifiers);
         case 'i':
            return Modifier.isInterface(modifiers);
         // Java8 only
         case 'd': // default - applied to methods only when they are the default method
            return isDefaultMethod(def);
      }
      throw new UnsupportedOperationException();
   }

   //static IBeanMapper methodDefaultMapper = getPropertyMapping(Method.class, "default");

   public static boolean isDefaultMethod(Object meth) {
      //return meth instanceof Method && ((Method) meth).isDefault();
      if (meth instanceof Method) {
         return ((Method) meth).isDefault();
         // Handle Java8 dependency using reflection so we can run on Java6 as well
         //if (methodDefaultMapper != null) {
         //   return (Boolean) methodDefaultMapper.getPropertyValue(meth, false, false);
         //}
         //return false;
      }
      throw new UnsupportedOperationException();
   }

   public static void setProperty(Object parent, Object selector, Object value) {
      try {
         //ToParameterizedType change body of created methods use File | Settings | File Templates.
         if (selector instanceof Method)
            ((Method) selector).invoke(parent, value);
         else if (selector instanceof Field)
            ((Field) selector).set(parent, value);
         else
            throw new IllegalArgumentException("Bad selector type to setProperty");
      }
      catch (InvocationTargetException ite) {
         System.err.println("*** Error setting: " + selector + " on: " + parent + " value: " + value + " threw: " + ite);
         ite.printStackTrace();
      }
      catch (IllegalAccessException exc) {
         System.err.println("*** Error setting: " + selector + " on: " + parent + " value: " + value + " threw: " + exc);
      }
   }

   public static Object getProperty(Object parent, Object mapping, boolean getField) {
      try {
         if (mapping instanceof Field) {
            Field f = (Field) mapping;
            /*
            if (parent != null && !f.getDeclaringClass().isInstance(parent))
               System.err.println("**** Invalid mapping field: " + f + " not for value: " + parent.getClass());
            else
            */
            return ((Field) mapping).get(parent);
         }
         else if (mapping instanceof Method)
            return ((Method) mapping).invoke(parent);
         else
            throw new IllegalArgumentException("Bad selector type to getProperty");
      }
      catch (InvocationTargetException ite) {
         System.err.println("*** Error getting: " + mapping + " on: " + parent + " threw: " + ite);
         ite.printStackTrace();
      }
      catch (IllegalAccessException exc) {
         System.err.println("*** Error getting: " + mapping + " on: " + parent + " threw: " + exc);
      }
      return null;
   }

   public static IBeanMapper getPropertyMappingConverter(IBeanMapper mapper, Class valueClass, Class componentClass) {
      Class propType = (Class) mapper.getPropertyType();

      // If the types are not exact, need to check for a converter
      if (valueClass != null && !propType.isAssignableFrom(valueClass)) {
         IBeanMapper cvtMapper = PTypeUtil.getBeanConverterMapper((BeanMapper) mapper, propType, valueClass);
         if (cvtMapper != null)
            return cvtMapper;

         // If reflection will do the conversion for us, go ahead and use it.
         if (!TypeUtil.isAssignableFromParameter(propType, valueClass))
            throw new IllegalArgumentException(mapper.getOwnerType() + "." + mapper.getPropertyName() + " has type: " + propType + " which cannot be set with: " + valueClass);
      }
      else if (List.class.isAssignableFrom(propType) && componentClass != null) {
         java.lang.reflect.Type genType = (java.lang.reflect.Type) mapper.getGenericType();
         if (genType instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) genType;
            java.lang.reflect.Type[] args = pt.getActualTypeArguments();
            if (args.length == 1 && args[0] instanceof Class) {
               Class listType = (Class) args[0];
               if (!listType.isAssignableFrom(componentClass)) {
                  IBeanMapper setter = PTypeUtil.getListConverter((BeanMapper) mapper, listType, componentClass);
                  if (setter == null)
                     throw new IllegalArgumentException(mapper.getOwnerType() + "." + mapper.getPropertyName() + " has list component type: " + listType + " which cannot be set with: " + componentClass);
                  return setter;
               }
            }
         }
      }
      return mapper;
   }

   public static Object invokeMethod(Object thisObject, Object method, Object... argValues) {
      // For this case in the IDE - we are compiling the LayerComponent into sys.core.build but it's not in the buildClassLoader so we end up creating a DynObject not a LayerFileComponent so this fails.  Not sure why for
      // the IDE case, not sure where sys.core.build (getCoreBuildLayer()) is not getting into the buildClassLoader chain in initSysClassLoader.
      /*
      if (thisObject != null && thisObject.toString() != null && thisObject.toString().equals("testFileProcWithField__0")) {
         if (method != null && method.toString().equals("public void sc.layer.LayerComponent.preInit()"))
            System.out.println("***");
      }
      */

      try {
         if (method instanceof Method) {
            Method meth = (Method) method;
            argValues = convertVarargValues(meth, argValues);
            return meth.invoke(thisObject, argValues);
         }
         else
            throw new IllegalArgumentException("Unrecognized type of method to TypeUtil.invokeMethod: " + method);
      }
      catch (IllegalAccessException exc) {
         System.err.println("*** Invoke method failed due to access exception: " + exc.toString());
         throw new IllegalArgumentException("Can't invoke method: " + method);
      }
      catch (InvocationTargetException exc) {
         Throwable target = exc.getTargetException();
         if (target instanceof RuntimeException)
           throw (RuntimeException) target;
         else if (target instanceof Error) {
            throw (Error) target;
         }
         else
            throw new IllegalArgumentException("Non runtime exc caught when invoking a method: " + target.toString());
      }
      catch (IllegalArgumentException exc) {
         System.err.println("*** Invoke method failed: argument exception - method: " + method + " params: " + (argValues == null ? null : Arrays.asList(argValues)) + " exc: " + exc.toString());
         throw exc;
      }
      catch (RuntimeException exc) {
         throw exc;
      }
   }

   public static Object resolveMethod(Class resultClass, String methodName, Object returnType, String paramSig) {
      return RTypeUtil.resolveMethod(resultClass, methodName, paramSig);
   }

   public static int getArrayLength(Object arrVal) {
      if (arrVal instanceof Collection)
         return ((Collection) arrVal).size();
      return Array.getLength(arrVal);
   }

   public static Object getArrayElement(Object arrVal, int dim) {
      if (arrVal instanceof List)
         return ((List) arrVal).get(dim);
      return Array.get(arrVal, dim);
   }

   public static void setArrayElement(Object arrVal, int dim, Object value) {
      if (arrVal instanceof List)
         ((List) arrVal).set(dim, value);
      else
         Array.set(arrVal, dim, value);
   }

   public static String getPropertyName(Object mapper) {
      if (mapper instanceof Member)
         return ((Member) mapper).getName();
      else if (mapper instanceof IBeanMapper)
         return ((IBeanMapper) mapper).getPropertyName();
      else
         throw new UnsupportedOperationException();
   }

   public static boolean useInstanceName(String toStr) {
      int len;
      // too small or too large is not useful
      if (toStr == null || toStr.length() > 60)
         return true;
      if ((len = toStr.length()) < 3)
         return false;

      if (!Character.isJavaIdentifierStart(toStr.charAt(0)))
         return false;

      int i = 0;
      char c;
      while (i < len - 1 && (Character.isJavaIdentifierPart(c = toStr.charAt(++i)) || c == '.'))
         ;

      if (i == len - 1)
         return false;

      if (toStr.charAt(i++) != '@')
         return false;

      while (i < len && Character.isLetterOrDigit(toStr.charAt(i++)))
         ;
      return i == len;
   }

   private static Class[] objectToClassArray(Object[] objs) {
      if (objs == null)
         return null;
      Class[] classes = new Class[objs.length];
      for (int i = 0; i < objs.length; i++)
         classes[i] = objs[i] == null ? null : objs[i].getClass();
      return classes;
   }

   public static Object createInstance(Class resultClass, String paramSig, Object... params) {
      Constructor ctor;
      if (paramSig == null)
         ctor = RTypeUtil.getConstructor(resultClass, objectToClassArray(params));
      else
         ctor = RTypeUtil.getConstructorFromTypeSignature(resultClass, paramSig);
      if (ctor == null) {
         String message = "*** No constructor: " + resultClass + "(" + (params == null ? "" : Arrays.asList(params)) + ")";
         System.err.println(message);
         throw new IllegalArgumentException(message);
      }
      try {
         return ctor.newInstance(params);
      }
      catch (InvocationTargetException ite) {
         Throwable th = ite.getCause();
         System.err.println("*** Exception in constructor creating: " + resultClass + " with: " + params + " root error: " + th);
         th.printStackTrace();
      }
      catch (InstantiationException exc) {
         System.err.println("*** Unable to instantiate constructor: " + ctor + " params: " + Arrays.asList(params) + ": " + exc);
      }
      catch (IllegalAccessException exc) {
         System.err.println("*** Unable to access constructor: " + ctor + " params: " + Arrays.asList(params) + ": " + exc);
      }
      catch (IllegalArgumentException exc) {
         System.err.println("*** Invalid arguments to constructor: " + ctor + " params: " + Arrays.asList(params) + ": " + exc);
      }
      catch (IndexOutOfBoundsException exc) {
         System.err.println("*** Index out of bounds invoking constructor: " + ctor + " params: " + Arrays.asList(params) + ": " + exc);
      }
      // TODO: not sure this is the right thing but good for debugging
      /*
      catch (NullPointerException exc) {
         System.err.println("***");
         System.err.println("*** NullPointerException invoking constructor: " + ctor + " params: " + Arrays.asList(params) + ": " + exc);
      }
      */
      return null;
   }

   public static boolean isPrimitive(Class type) {
      sc.type.Type t = sc.type.Type.get(type);
      return t.primitiveClass == type;
   }

   public static boolean isANumber(Class type) {
      sc.type.Type t = sc.type.Type.get(type);
      return t.isANumber();
   }

   public static boolean isStringOrChar(Class type) {
      Type t = Type.get(type);
      return t == Type.String || t == Type.Character;
   }

   public static Object getReturnType(Object method) {
      if (method instanceof Method)
         return ((Method) method).getReturnType();
      else if (method instanceof DynRemoteMethod)
         return ((DynRemoteMethod) method).returnType;
      else
         throw new IllegalArgumentException("Unrecognized type of method: " + method);
   }

   public static Object[] getParameterTypes(Object dynMethod) {
      if (dynMethod instanceof Method)
         return ((Method) dynMethod).getParameterTypes();
      else if (dynMethod instanceof DynRemoteMethod)
         return convertParamSigToTypes(((DynRemoteMethod) dynMethod).paramSig);
      else
         throw new IllegalArgumentException("Unrecognized method: " + dynMethod);
   }

   /** Given a signature string in the format used for remote methods, return the list of parameter types to that method */
   public static Object[] convertParamSigToTypes(String paramSig) {
      if (paramSig == null || paramSig.length() == 0)
         return null;
      String[] typeStrArr = StringUtil.split(paramSig, ';');
      int num = typeStrArr.length;
      if (num == 0)
         return null;
      num = num - 1; // Ignore the empty ; at the end
      Object[] res = new Object[num];
      for (int i = 0; i < num; i++) {
         String typeStr = typeStrArr[i];
         Object type;
         char c = typeStr.charAt(0);
         int numDims;
         if (c == '[') {
            numDims = 1;
            int j;
            for (j = 1; typeStr.length() > j && typeStr.charAt(j) == '['; j++)
               numDims++;
            c = typeStr.charAt(j);
         }
         else
            numDims = 0;
         switch (c) {
            case 'L':
               String typeName = typeStr.substring(1).replace('/', '.');
               type = DynUtil.findType(typeName);
               if (type == null)
                  throw new IllegalArgumentException("No type for param signature conversion: " + typeName);
               break;
            default:
               String code = String.valueOf(c);
               sc.type.Type t = sc.type.Type.getArrayType(code);
               if (t == null)
                  throw new IllegalArgumentException("Unrecognized type signature code: " + code);
               type = t.primitiveClass;
               break;
         }
         if (numDims == 0)
            res[i] = type;
         else {
            Class cclass = (Class) type;
            if (cclass.isPrimitive())
               res[i] = Type.get(cclass).getPrimitiveArrayClass(numDims);
            else
               res[i] = Type.get(cclass).getArrayClass(cclass, numDims);
         }
      }
      return res;
   }

   public static String getMethodName(Object method) {
      if (method instanceof Method)
         return ((Method) method).getName();
      throw new IllegalArgumentException("Unrecognized method: " + method);
   }

   public static Object getMethodType(Object method) {
      if (method instanceof Method)
         return ((Method) method).getDeclaringClass();
      throw new UnsupportedOperationException();
   }

   public static IReverseMethodMapper getReverseMethodMapper(MethodBinding binding) {
      return new RuntimeReverseMethodMapper(binding);
   }

   public static boolean compareBeanMappers(IBeanMapper thisP, IBeanMapper otherP) {
      Object type1, type2;
      return otherP == thisP || (thisP.getPropertyName().equals(otherP.getPropertyName()) &&
              (DynUtil.isAssignableFrom(type1 = RDynUtil.getEnclosingType(thisP.getPropertyMember(), false), type2 = RDynUtil.getEnclosingType(otherP.getPropertyMember(), false)) ||
                      DynUtil.isAssignableFrom(type2, type1)));
   }

   static DynType initPropertyCache(Class resultClass) {
      DynType cache;
      try {
         numClassesCached++;
         Method[] methods;
         Field[] fields;

         try {
            methods = resultClass.getDeclaredMethods();
         }
         catch (NoClassDefFoundError exc) {
            System.err.println("*** Missing class in classpath loading methods for: " + resultClass.getName() + ":" + exc);
            methods = new Method[0];
         }
         try {
            fields = resultClass.getDeclaredFields();
         }
         catch (NoClassDefFoundError exc) {
            System.err.println("*** Missing class in classpath loading fields for: " + resultClass.getName() + ":" + exc);
            fields = new Field[0];
         }

         BeanMapper mapper;
         int pos = MIN_PROPERTY, staticPos = MIN_PROPERTY;
         boolean isInterface = resultClass.isInterface();

         // First populate from the super-class so that all of our slot positions are consistent
         Class superClass = resultClass.getSuperclass();
         DynType superType = null;

         Class[] interfaces = resultClass.getInterfaces();
         DynType[] ifTypes = null;
         int iflen = 0;
         if (interfaces != null) {
            if (interfaces.length == 0)
               interfaces = null;
            else {
               // Count the number of properties contributed by the interfaces.  These will be dynamic properties
               ifTypes = new DynType[interfaces.length];
               for (int i = 0; i < interfaces.length; i++) {
                  DynType ifaceType = TypeUtil.getPropertyCache(interfaces[i]);
                  ifTypes[i] = ifaceType;
                  if (ifaceType.properties != null)
                     iflen += ifaceType.properties.size;
               }
            }
         }

         // Create the cache including enough space for super and interface properties
         if (superClass != null) {
            superType = TypeUtil.getPropertyCache(superClass);

            cache = new DynType(null, superType.propertyCount + superType.staticPropertyCount + methods.length + fields.length + iflen, 0);
         }
         else
            cache = new DynType(null, methods.length + fields.length + iflen, 0);

         if (superClass != null) {
            if (superType.properties != null) {
               for (int i = 0; i < superType.properties.keyTable.length; i++) {
                  if (superType.properties.keyTable[i] != null) {
                     BeanMapper inherit = (BeanMapper) superType.properties.valueTable[i];
                     //Member propertyMember = inherit.getPropertyMember();
                     // Now including static properties
                     //if (propertyMember == null || !Modifier.isStatic(propertyMember.getModifiers()) ) {
                        cache.addProperty((String) superType.properties.keyTable[i], inherit);
                        numPropsInherited++;
                        if (inherit.instPosition >= pos)
                           pos = inherit.instPosition + 1;

                        // Should we be inheriting static positions?  NO: up above, we eliminate them.  This code is probably
                        // not reached but we used to match the list and we
                        // do let you do "a.b" off of a static reference.  Otherwise, it seems we have to chain lookups.
                        // If we want to change this, we'd also have to not inherit static positions or we get conflicts.
                        if (inherit.staticPosition >= staticPos)
                           staticPos = inherit.staticPosition + 1;
                     /*} */
                  }
               }
            }
         }

         // Java does not let us reflect this in the normal way as a field so it is treated specially.
         if (resultClass.isArray() && (superClass == null || !superClass.isArray())) {
            // This guy is always at position 0 for array classes
            cache.addProperty("length", ArrayLengthBeanMapper.INSTANCE);
            if (pos == 0)
               pos++;
         }

         for (int i = 0; i < fields.length; i++) {
            Field field = fields[i];
            if ((field.getModifiers() & Modifier.PUBLIC) == 0) {
               try {
                  // TODO: I think only public fields should be properties now so we can
                  // probably get rid of this.
                  field.setAccessible(true);
               }
               catch (RuntimeException exc) {
                  continue;
               }
            }

            String name = field.getName();
            // Keep this$0 etc because we use them for finding the outer object.  But other properties with $ get omitted.
            if (name.contains("$") && !name.startsWith("this"))
               continue;
            boolean isStatic = Modifier.isStatic(field.getModifiers());
            mapper = new BeanMapper(field, field, field);
            BeanMapper oldMapper;
            // This happens when a field in a subclass overrides a get/set method in a superclass
            // We don't allocate a new position for this case because it creates a null slot if we ever
            // turn this into a property list.
            if ((oldMapper = (BeanMapper) cache.addProperty(name, mapper)) != null) {
               mapper.instPosition = oldMapper.instPosition;
               mapper.staticPosition = oldMapper.staticPosition;
               if (mapper.staticPosition != -1) {
                  mapper.ownerType = resultClass;
               }
            }
            else {
               if (isStatic) {
                  mapper.staticPosition = staticPos++;
                  mapper.ownerType = resultClass;
               }
               else
                  mapper.instPosition = pos++;
            }
            numFieldsCached++;
         }

         TreeMap<String,BeanMapper> pendingValidates = null;

         for (int i = 0; i < methods.length; i++) {
            Method method = methods[i];
            if ((method.getModifiers() & Modifier.PUBLIC) == 0) {
               try {
                  method.setAccessible(true);
               }
               catch(RuntimeException exc) {
                  continue;
               }
            }
            String name = method.getName();
            Class[] ptypes;
            String propName;
            PropertyMethodType type = null;

            char c = name.charAt(0);
            switch (c) {
               case 's':
                  if (!name.startsWith("set") || Modifier.isPrivate(method.getModifiers()))
                     continue;
                  ptypes = method.getParameterTypes();
                  if (ptypes.length == 1)
                     type = PropertyMethodType.Set;
                  else if (ptypes.length == 2 && ptypes[0] == int.class)
                     type = PropertyMethodType.SetIndexed;
                  else
                     continue;
                  propName = CTypeUtil.decapitalizePropertyName(name.substring(3));
                  break;
               case 'g':
                  if (!name.startsWith("get") || Modifier.isPrivate(method.getModifiers()))
                     continue;
                  ptypes = method.getParameterTypes();
                  if (ptypes.length == 0)
                     type = PropertyMethodType.Get;
                  else if (ptypes.length == 1 && ptypes[0] == int.class)
                     type = PropertyMethodType.GetIndexed;
                  else
                     continue;

                  propName = CTypeUtil.decapitalizePropertyName(name.substring(3));
                  break;
               case 'i':
                  ptypes = method.getParameterTypes();
                  if (!name.startsWith("is") || ptypes.length != 0 || Modifier.isPrivate(method.getModifiers()))
                     continue;
                  propName = CTypeUtil.decapitalizePropertyName(name.substring(2));
                  type = PropertyMethodType.Is;
                  break;
               case 'v':
                  if (!name.startsWith("validate") || Modifier.isPrivate(method.getModifiers()))
                     continue;
                  ptypes = method.getParameterTypes();
                  if (ptypes.length != 0 && ptypes.length != 1 || name.length() < 9)
                     continue;
                  Class retType = method.getReturnType();
                  if (retType != String.class)
                     continue;
                  propName = CTypeUtil.decapitalizePropertyName(name.substring(8));
                  type = PropertyMethodType.Validate;
                  break;
               default:
                  continue;
            }
            if (type == null || propName.length() == 0)
               continue;

            numMethodsCached++;

            boolean isStatic = Modifier.isStatic(method.getModifiers());
            mapper = (BeanMapper) cache.getPropertyMapper(propName);
            if (mapper == null && pendingValidates != null && type != PropertyMethodType.Validate) {
               mapper = pendingValidates.get(propName);
               if (mapper != null) {
                  cache.addProperty(propName, mapper);
                  pendingValidates.remove(propName);
               }
            }
            if (mapper == null) {
               mapper = new BeanMapper();
               if (isStatic) {
                  mapper.staticPosition = staticPos++;
                  mapper.ownerType = resultClass;
               }
               else if (!isInterface)
                  mapper.instPosition = pos++;
               else
                  mapper.instPosition = IBeanMapper.DYNAMIC_LOOKUP_POSITION;  // Interfaces need to do a dynamic lookup
               // This logic is to avoid adding a property with no get or set method but with a validate method
               if (type == PropertyMethodType.Validate) {
                  if (pendingValidates == null)
                     pendingValidates = new TreeMap<String,BeanMapper>();
                  pendingValidates.put(propName, mapper);
               }
               else
                  cache.addProperty(propName, mapper);
            }
            else {
               boolean cloned = false;
               if (superType != null && superType.getPropertyMapper(propName) == mapper) {
                  mapper = mapper.clone();
                  cloned = true;
               }
               // Tricky case - either the field or the other set method's
               // static does not match this one.  In this case, allocate the
               // new slot in the other scope.
               if (isStatic && mapper.staticPosition == -1) {
                  mapper.staticPosition = staticPos++;
                  if (mapper.ownerType == null)
                     mapper.ownerType = resultClass;
               }

               // For non-interfaces we need to store the absolute position for the property if we inherited the dynamic marker position.
               if (!isStatic) {
                  if (mapper.instPosition == -1) {
                     mapper.instPosition = pos++;
                  }
                  else if (!isInterface && mapper.instPosition == IBeanMapper.DYNAMIC_LOOKUP_POSITION) {
                     mapper.instPosition = pos++;
                     cache.dynLookupCount--;
                  }
               }

               // Do this after we've assigned the positions
               if (cloned)
                  cache.addProperty(propName, mapper);
            }
            switch (type) {
               case Set:
                  if (mapper.setSelector != null) {
                     // Always let us override the set method if it is a field
                     if (mapper.setSelector instanceof Field)
                        mapper.setSetSelector(method);
                        // Need to use property type to select the right setSelector method
                     else {
                        Method oldSetMethod = (Method) mapper.setSelector;
                        Class[] oldptypes = oldSetMethod.getParameterTypes();
                        if (oldptypes[0] == ptypes[0] || ptypes[0].isAssignableFrom(oldptypes[0]))
                           mapper.setSetSelector(method);
                        else {
                           Object getSelector = mapper.getSelector;
                           if (getSelector == null) {
                              String getName = "get" + CTypeUtil.capitalizePropertyName(propName);
                              for (int j = i; j < methods.length; j++) {
                                 Method theMethod = methods[j];
                                 if (theMethod.getName().equals(getName) && theMethod.getParameterTypes().length == 0 &&
                                     !Modifier.isPrivate(theMethod.getModifiers())) {
                                    getSelector = theMethod;
                                    break;
                                 }
                              }
                              if (getSelector == null) {
                                 if (trace)
                                    System.out.println("*** Warning: no getX method with multiple setX methods of incompatible types - invalid property: " + propName + " on class: " + resultClass);
                                 //mapper.setSetSelector(null);
                              }
                           }
                           if (getSelector != null) {
                              mapper.setGetSelector(getSelector);
                              Class propType = mapper.getPropertyType();
                              boolean newMatch = ptypes[0].isAssignableFrom(propType);
                              boolean oldMatch = oldptypes[0].isAssignableFrom(propType);
                              // Let the new one override if the old one is of a compatible type
                              // since the old one could be inherited.
                              if (newMatch && oldMatch)
                                 newMatch = oldptypes[0].isAssignableFrom(ptypes[0]);

                              if (newMatch)
                                 mapper.setSetSelector(method);
                           }
                        }
                     }
                  }
                  else
                     mapper.setSetSelector(method);
                  break;
               case Get:
               case Is:
                  mapper.setGetSelector(method);
                  break;
               case GetIndexed:
                  if (!(mapper instanceof BeanIndexMapper))
                     mapper = new BeanIndexMapper(mapper);
                  ((BeanIndexMapper) mapper).getIndexMethod = method;
                  cache.addProperty(propName, mapper);
                  break;
               case SetIndexed:
                  if (!(mapper instanceof BeanIndexMapper))
                     mapper = new BeanIndexMapper(mapper);
                  ((BeanIndexMapper) mapper).setIndexMethod = method;
                  cache.addProperty(propName, mapper);
                  break;
               case Validate:
                  mapper.setValidateMethod(method);
                  break;
            }
         }

         // Finally do the interfaces but do not add them if there is an existing definition.  Do not want to make
         // an instance property use a dynamic lookup unless it is defined in the interface.
         if (interfaces != null) {
            for (int ifix = 0; ifix < interfaces.length; ifix++) {
               DynType ifaceType = ifTypes[ifix];
               if (ifaceType.properties == null)
                  continue;
               for (int i = 0; i < ifaceType.properties.keyTable.length; i++) {
                  String pname = (String) ifaceType.properties.keyTable[i];
                  if (pname != null && cache.getPropertyMapper(pname) == null) {
                     BeanMapper inherit = (BeanMapper) ifaceType.properties.valueTable[i];

                     // Cannot inherit static positions from interfaces since that supports essentially multiple inheritance.
                     // just assign new positions for this mapper for this type.
                     if (inherit.staticPosition != -1) {
                        inherit = inherit.clone();
                        inherit.staticPosition = staticPos++;
                        // Not resetting ownerType here since this property really is owned by the type we are inheriting from
                     }

                     //Member propertyMember = inherit.getPropertyMember();
                     // Now including static properties
                     //if (propertyMember == null || !Modifier.isStatic(propertyMember.getModifiers()) )
                     cache.addProperty(pname, inherit);

                     numInterfacePropsCached++;
                  }
               }
            }
         }

         // We do not consistently set the mapper's position fields before adding them so
         // need to manually update these counts here.
         cache.propertyCount = pos;
         cache.staticPropertyCount = staticPos;
         DynUtil.addPropertyCache(resultClass, cache);
         return cache;
      }
      catch (Error e) {
         System.err.println("*** Error using reflection against class: " + resultClass + ": " + e);
         e.printStackTrace();
      }
      cache = new DynType(null, 0, 0);
      DynUtil.addPropertyCache(resultClass, cache);

      return cache;
   }

   public static boolean evalInstanceOfExpression(Object lhsVal, Class theClass) {
      if (theClass.isInstance(lhsVal))
         return Boolean.TRUE;
      return Boolean.FALSE;
   }

   public static IBeanMapper getArrayLengthBeanMapper() {
      return ArrayLengthBeanMapper.INSTANCE;
   }

   // Reflectively create an array.
   public static Object newArray(Class cl, int size) {
      return Array.newInstance(cl, size);
   }

   public static boolean isAssignableFrom(Class cl, Class other) {
      return cl.isAssignableFrom(other);
   }

   public static Object getDefaultObjectValue(Class type) {
      return Type.get(type).getDefaultObjectValue();
   }

   public static String getThreadName() {
      return Thread.currentThread().getName();
   }

   public static Object findType(String typeName) {
      try {
         return defaultClassLoader == null ? Class.forName(typeName) : Class.forName(typeName, true, defaultClassLoader);
      }
      catch (ClassNotFoundException exc) {
         return null;
      }
   }

   public static Object findTypeWithLoader(String typeName, ClassLoader loader) {
      try {
         if (loader == null)
            return findType(typeName);
         return Class.forName(typeName, true, loader);
      }
      catch (ClassNotFoundException exc) {
         return null;
      }
   }

   private static Timer timer;
   private static final Object timerLock = new Object();

   public static Object addScheduledJob(final Runnable toRun, long delay, boolean repeat) {
      if (timer == null) {
         synchronized (timerLock) {
            if (timer == null)
               timer = new Timer("PTypeUtil.addScheduledJob");
         }
      }
      TimerTask task = new TimerTask() {
         public void run() {
            toRun.run();
         }
      };
      if (repeat)
         timer.schedule(task, delay, delay);
      else
         timer.schedule(task, delay);
      return task;
   }

   public static void cancelScheduledJob(Object handle, boolean repeat) {
      ((TimerTask) handle).cancel();
   }

   /** On the server, do nothing.  On the client, run the job after the initial page has loaded and been refreshed.  */
   public static void addClientInitJob(Runnable r) {
   }

   /** Used in JS to differentiate between a site loaded from a file versus a server */
   public static String getServerName() {
      return null;
   }

   public static void postHttpRequest(String url, String postData, String contentType, IResponseListener listener) {
      // TODO: maybe implement this or clean this API up.  Can we emulate XmlHttpRrequest in Java and so use that one API?
      throw new UnsupportedOperationException();
   }

   // Same as postHttpRequest - this feature is part of newer browsers used to notify the server the browser is closing but
   // could be easily implemented in other runtimes using an exit listener a synchronous Http request
   public static void sendBeacon(String url, String postData) {
      throw new UnsupportedOperationException();
   }

   public static boolean isObject(Object obj) {
      return obj != null && isObjectType(obj.getClass());
   }

   public static boolean isObjectType(Object obj) {
      if (!(obj instanceof Class))
         throw new UnsupportedOperationException();
      Object annot = getAnnotationValue((Class) obj, "sc.obj.TypeSettings", "objectType");
      return annot != null && annot instanceof Boolean && ((Boolean)annot);
   }

   public static boolean isArray(Object cl) {
      return cl instanceof Class && ((Class) cl).isArray();
   }

   public static Object getComponentType(Object cl) {
      if (cl instanceof ParameterizedType) {
         ParameterizedType pt = (ParameterizedType) cl;
         java.lang.reflect.Type t = pt.getRawType();
         if (t instanceof Class && Collection.class.isAssignableFrom(((Class) t))) {
            java.lang.reflect.Type[] args = pt.getActualTypeArguments();
            if (args != null && args.length == 1 && args[0] instanceof Class)
               return args[0];
         }
         return null;
      }
      return cl instanceof Class ? ((Class) cl).getComponentType() : null;
   }

   public static Object getAnnotationValue(Class cl, String annotName, String annotValue) {
      Class annotClass = RDynUtil.loadClass(annotName);
      if (annotClass == null)
         throw new IllegalArgumentException("No annotation class for getAnnotationValue: " + annotName);
      Annotation annotation = cl.getAnnotation(annotClass);
      if (annotation == null)
         return null;
      return getValueFromAnnotation(annotation, annotValue);
   }

   public static Object getValueFromAnnotation(Object annotation, String annotValue) {
      if (annotation instanceof Annotation) {
         Method method = RTypeUtil.getMethod(annotation.getClass(), annotValue);
         return PTypeUtil.invokeMethod(annotation, method, (Object[]) null);
      }
      else
         throw new IllegalArgumentException("Invalid arg to getValueFromAnnotation");
   }

   public static Object getInheritedAnnotationValue(Class cl, String annotName, String annotValue) {
      Class annotClass = RDynUtil.loadClass(annotName);
      if (annotClass == null)
         throw new IllegalArgumentException("No annotation class for getAnnotationValue: " + annotName);
      Annotation annotation = null;
      do {
         annotation = cl.getAnnotation(annotClass);
         if (annotation != null) {
            Object res = getValueFromAnnotation(annotation, annotValue);
            if (res != null)
               return res;
         }
         cl = cl.getSuperclass();
      } while (cl != null);

      return null;
   }

   // Takes a def - either a Class, Method, Field and the annotNameOrType - either the String type name of the annot class itself.
   public static Object getAnnotation(Object def, Object annotNameOrType) {
      Class annotClass;
      if (annotNameOrType instanceof String) {
         annotClass = RDynUtil.loadClass((String) annotNameOrType);
      }
      else if (annotNameOrType instanceof Class) {
         annotClass = (Class) annotNameOrType;
      }
      else
         throw new IllegalArgumentException("Invalid type to getAnnotation");

      Annotation annotation = null;
      if (def instanceof Class) {
         Class cl = (Class) def;
         do {
            annotation = cl.getAnnotation(annotClass);
            if (annotation != null)
               break;
            cl = cl.getSuperclass();
         } while (cl != null);
      }
      else if (def instanceof AnnotatedElement) {
         return ((AnnotatedElement) def).getAnnotation(annotClass);
      }
      return annotation;
   }

   public static Object getEnclosingType(Object typeObj, boolean instOnly) {
      if (typeObj instanceof Class) {
         if (instOnly && hasModifier(typeObj, "static"))
            return null;

         return ((Class) typeObj).getDeclaringClass();
      }
      throw new UnsupportedOperationException();
   }

   /**
    * We used to use a Java/JS-generated class ScopeEnvironment for appId and this PTypeUtil 'native' approach for windowId because we need
    * the windowId during the object init code that runs when loading JS files.  That all happens before we can define the ScopeEnvironment
    * class so there's no easy way to generate the JS version.  Instead we're going 'native' for appId as well and probably tenantId or
    * any other 'ScopeEnvironment' variables we need to define.  TODO: should this become a native class ScopeEnvironment?
    */
   public static void setWindowId(int id) {
      setThreadLocal("windowId", id);
   }
   public static int getWindowId() {
      Integer res = (Integer) getThreadLocal("windowId");
      if (res == null)
         return -1;
      return res;
   }

   public static void setAppId(String id) {
      setThreadLocal("appId", id);
   }
   public static String getAppId() {
      return (String) getThreadLocal("appId");
   }

   public static Object clone(Object o) {
      Object meth = resolveMethod(o.getClass(), "clone", null, null);
      return invokeMethod(o, meth);
   }


   public static String getStackTrace(Throwable exc) {
      StringWriter sw = new StringWriter();
      PrintWriter out = new PrintWriter(sw);
      exc.printStackTrace(out);
      return sw.toString();
   }

   public static void acquireLocks(List<Object> locks, String traceInfo) {
      if (locks.size() == 0)
         return;
      long startTime = -1;
      if (traceInfo != null) {
         startTime = System.currentTimeMillis();
         System.out.println("Acquiring locks:" + traceInfo);
      }

      // Wait as normal to get the first lock
      ((Lock) locks.get(0)).lock();
      int fetchFrom = 1;
      int fetchTo = locks.size();
      int repeatTo = -1;
      boolean repeat;
      do {
         repeat = false;
         for (int i = fetchFrom; i < fetchTo; i++) {
            Lock lock = (Lock) locks.get(i);
            // If we can't immediately get the next lock
            if (!lock.tryLock()) {
               releaseLocks(locks, 0, i);
               if (traceInfo != null)
                  System.out.println("Waiting for lock: " + lock + ":" + traceInfo);
               // Wait now to get the contended lock to avoid a busy loop but we'll just immediately release it just to make the code simpler
               lock.lock();

               // We're going to finish this iteration of the loop to acquire
               repeat = true;
               fetchFrom = 0;
               repeatTo = i;
            }
         }
         // We'll either exit this loop with all of the locks (repeat=false) or repeat=true, from repeatTo=>size locks.  In the latter case we need to acquire then 0-repeatTo locks and repeat the loop once.
         if (repeat)
            fetchTo = repeatTo;
      } while (repeat);

      if (traceInfo != null) {
         long duration = System.currentTimeMillis() - startTime;
         if (duration > 100)
            System.out.println("Locks acquired after waiting: " + duration + " millis for:" + traceInfo);
      }
   }

   public static void releaseLocks(List<Object> locks, String traceInfo) {
      if (locks.size() == 0)
         return;
      if (traceInfo != null)
         System.out.println("Releasing locks:" + traceInfo);
      releaseLocks(locks, 0, locks.size());
   }

   static void releaseLocks(List<Object> locks, int from, int to) {
      for (int i = from; i < to; i++) {
         ((Lock) locks.get(i)).unlock();
      }
   }

   /** Hook for diagnostics for JS to compare against 'undefined' */
   public static boolean isUndefined(Object o) {
      return false;
   }

   public static String getPlatformOpenCommand() {
      String osName = System.getProperty("os.name");
      if (osName == null || osName.contains("Mac OS X"))
         return "open";
      else if (osName.contains("Windows"))
         return "start";
      else // Assuming linux
         return "xdg-open";
   }

   public static String getTimeDelta(long startTime, long now) {
      if (startTime == 0)
         return "<server not yet started!>";
      StringBuilder sb = new StringBuilder();
      long elapsed = now - startTime;
      sb.append("+");
      boolean remainder = false;
      if (elapsed > 60*60*1000) {
         long hrs = elapsed / (60*60*1000);
         elapsed -= hrs * 60*60*1000;
         if (hrs < 10)
            sb.append("0");
         sb.append(hrs);
         sb.append(":");
         remainder = true;
      }
      if (elapsed > 60*1000 || remainder) {
         long mins = elapsed / (60*1000);
         elapsed -= mins * 60*1000;
         if (mins < 10)
            sb.append("0");
         sb.append(mins);
         sb.append(":");
      }
      if (elapsed > 1000 || remainder) {
         long secs = elapsed / 1000;
         elapsed -= secs * 1000;
         if (secs < 10)
            sb.append("0");
         sb.append(secs);
         sb.append(".");
      }
      if (elapsed > 1000) // TODO: remove this - diagnostics only
         System.err.println("*** bad value in getTimeDelta!");
      if (elapsed < 10)
         sb.append("00");
      else if (elapsed < 100)
         sb.append("0");
      sb.append(elapsed);
      return sb.toString();
   }

   public static Object[] convertVarargValues(Method method, Object[] argValues) {
      int numParams = method.getParameterCount();
      int numArgs = argValues == null ? 0 : argValues.length;
      boolean isVarArgs = numParams != 0 && method.isVarArgs();
      if (isVarArgs) {
         Object[] repeatVal = null;
         boolean redoArgs = false;
         if (numArgs < numParams) {
            repeatVal = new Object[0];
            redoArgs = true;
         }
         else {
            int last = numParams - 1;
            Object lastVal = argValues == null ? null : argValues[last];
            if (numParams != numArgs || !(lastVal instanceof Object[])) {
               int numRepeat = numArgs + 1 - numParams;
               Parameter param = method.getParameters()[last];
               Class paramType = param.getType();
               Class cl = paramType.getComponentType();

               repeatVal = (Object[]) PTypeUtil.newArray(cl, numRepeat);
               for (int i = 0; i < numRepeat; i++) {
                  repeatVal[i] = argValues[last++];
               }
               redoArgs = true;
            }
         }
         if (redoArgs) {
            int last = numParams - 1;
            Object[] newArgValues = new Object[numParams];
            for (int i = 0; i < last; i++) {
               newArgValues[i] = argValues[i];
            }
            newArgValues[last] = repeatVal;
            argValues = newArgValues;
         }
      }
      return argValues;
   }
}
