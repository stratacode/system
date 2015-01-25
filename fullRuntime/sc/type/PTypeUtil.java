/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.type;

import sc.bind.MethodBinding;
import sc.dyn.IReverseMethodMapper;
import sc.js.JSSettings;
import sc.util.WeakIdentityHashMap;
import sc.dyn.DynUtil;
import sc.dyn.RDynUtil;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;

@JSSettings(jsLibFiles="js/scdyn.js")
public class PTypeUtil {
   public static ThreadLocal<Map<String,Object>> threadLocalMap = new ThreadLocal<Map<String,Object>>();
   // Slot 0 is reserved for object/class value listeners.
   public final static int MIN_PROPERTY = 1;

   // Set to true to debug errors encountered trying to resolve properties from types, e.g. properties discarded because of conflicting return types.
   public static boolean trace = false;

   public static int numClassesCached = 0;
   public static int numFieldsCached = 0;
   public static int numMethodsCached = 0;
   public static int numInterfacePropsCached = 0;
   public static int numPropsInherited = 0;


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

   public static IBeanMapper getBeanMapper(final BeanMapper mapper, Class propertyClass, Class valueClass) {
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
               public Object getPropertyValue(Object parent) {
                  List<?> lval = (List<?>) TypeUtil.getPropertyValue(parent, getSelector);
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
               public Object getPropertyValue(Object parent) {
                  // Undo the conversion here.  If we use a slot mapping to wrap a list on the way in
                  // we need to undo it on the way out so that we get the right data type for generation
                  // purposes.
                  Object pval = TypeUtil.getPropertyValue(parent, getSelector);
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
               public Object getPropertyValue(Object parent) {
                  Set<?> lval = (Set<?>) TypeUtil.getPropertyValue(parent, getSelector);
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
      if (IValueConverter.class.isAssignableFrom(valueClass)) {
         return new BeanMapper() {
            BeanMapper superMapper = mapper;
            public Object getPropertyValue(Object parent) {
               Object propVal = superMapper.getPropertyValue(parent);
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
      return (BeanMapper) cache.getPropertyMapper(propName);
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

   public static Object getProperty(Object parent, Object mapping) {
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

   public static IBeanMapper getPropertyMappingConverter(Class beanClass, String propName, IBeanMapper mapper, Class valueClass, Class componentClass) {
      Class propType = (Class) mapper.getPropertyType();

      // If the types are not exact, need to check for a converter
      if (valueClass != null && !propType.isAssignableFrom(valueClass)) {
         IBeanMapper cvtMapper = PTypeUtil.getBeanMapper((BeanMapper) mapper, propType, valueClass);
         if (cvtMapper != null)
            return cvtMapper;

         // If reflection will do the conversion for us, go ahead and use it.
         if (!TypeUtil.isAssignableFromParameter(propType, valueClass))
            throw new IllegalArgumentException(beanClass + "." + propName + " has type: " + propType + " which cannot be set with: " + valueClass);
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
                     throw new IllegalArgumentException(beanClass + "." + propName + " has list component type: " + listType + " which cannot be set with: " + componentClass);
                  return setter;
               }
            }
         }
      }
      return mapper;
   }

   public static Object invokeMethod(Object thisObject, Object method, Object... argValues) {
      try {
         if (method instanceof Method) {
            return ((Method) method).invoke(thisObject, argValues);
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
         System.err.println("*** Invoke method failed: argument exception - method: " + method + " params: " + (argValues == null ? null : Arrays.asList(argValues)));
         throw exc;
      }
      catch (RuntimeException exc) {
         throw exc;
      }
   }

   public static Object resolveMethod(Class resultClass, String methodName, String paramSig) {
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
      while (i < len - 1 && Character.isJavaIdentifierPart(toStr.charAt(++i)))
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
         System.err.println("*** No constructor: " + resultClass + "(" + Arrays.asList(params) + ")");
         return null;
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
      return null;
   }

   public static boolean isPrimitive(Class type) {
      sc.type.Type t = sc.type.Type.get(type);
      return t.primitiveClass == type;
   }

   public static Object getReturnType(Object method) {
      if (method instanceof Method)
         return ((Method) method).getReturnType();
      else
         throw new IllegalArgumentException("Unrecognized type of method: " + method);
   }

   public static Object[] getParameterTypes(Object dynMethod) {
      if (dynMethod instanceof Method)
         return ((Method) dynMethod).getParameterTypes();
      else
         throw new IllegalArgumentException("Unrecognized method: " + dynMethod);
   }

   public static String getMethodName(Object method) {
      if (method instanceof Method)
         return ((Method) method).getName();
      throw new IllegalArgumentException("Unrecognized method: " + method);
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

         Method[] methods = resultClass.getDeclaredMethods();
         Field[] fields = resultClass.getDeclaredFields();
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
            field.setAccessible(true);

            String name = field.getName();
            // Keep this$0 etc because we use them for finding the outer object.  But other properties with $ get ommitted.
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

         for (int i = 0; i < methods.length; i++) {
            Method method = methods[i];
            method.setAccessible(true);
            String name = method.getName();
            Class[] ptypes;
            String propName;
            PropertyMethodType type = null;

            char c = name.charAt(0);
            switch (c) {
               case 's':
                  ptypes = method.getParameterTypes();
                  if (!name.startsWith("set"))
                     continue;
                  if (ptypes.length == 1)
                     type = PropertyMethodType.Set;
                  else if (ptypes.length == 2 && ptypes[0] == int.class)
                     type = PropertyMethodType.SetIndexed;
                  else
                     continue;
                  propName = CTypeUtil.decapitalizePropertyName(name.substring(3));
                  break;
               case 'g':
                  ptypes = method.getParameterTypes();
                  if (!name.startsWith("get"))
                     continue;
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
                  if (!name.startsWith("is") || ptypes.length != 0)
                     continue;
                  propName = CTypeUtil.decapitalizePropertyName(name.substring(2));
                  type = PropertyMethodType.Is;
                  break;
               default:
                  continue;
            }
            if (type == null || propName.length() == 0)
               continue;

            numMethodsCached++;

            boolean isStatic = Modifier.isStatic(method.getModifiers());
            mapper = (BeanMapper) cache.getPropertyMapper(propName);
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

               if (!isStatic && mapper.instPosition == -1)
                  mapper.instPosition = pos++;

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
                                 if (theMethod.getName().equals(getName) && theMethod.getParameterTypes().length == 0) {
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

   public static Object findType(String typeName) {
      try {
         return Class.forName(typeName);
      }
      catch (ClassNotFoundException exc) {
         System.err.println("*** findType: " + exc);
         return null;
      }
   }

   public static boolean isInvokeLaterSupported() {
      return false;
   }

   // Implemented in Javascript
   public static void invokeLater(Runnable toRun, long delay) {
      throw new UnsupportedOperationException();
   }

   public static void postHttpRequest(String url, String postData, String contentType, IResponseListener listener) {
      // TODO: maybe implement this or clean this API up.  Can we emulate XmlHttpRrequest in Java and so use that one API?
      throw new UnsupportedOperationException();
   }

   // TODO: implemented in JS
   public static boolean isObject(Object obj) {
      throw new UnsupportedOperationException();
   }

   // TODO: implemented in JS
   public static boolean isObjectType(Object obj) {
      throw new UnsupportedOperationException();
   }

   public static boolean isArray(Object cl) {
      return cl instanceof Class && ((Class) cl).isArray();
   }

   public static Object getAnnotationValue(Class cl, String annotName, String annotValue) {
      Class annotClass = RDynUtil.loadClass(annotName);
      if (annotClass == null)
         throw new IllegalArgumentException("No annotation class for getAnnotationValue: " + annotName);
      Annotation annotation = cl.getAnnotation(annotClass);
      if (annotation == null)
         return null;
      Method method = RTypeUtil.getMethod(annotation.getClass(), annotValue);
      return TypeUtil.invokeMethod(annotation, method, (Object[])null);
   }

   public static Object getInheritedAnnotationValue(Class cl, String annotName, String annotValue) {
      Class annotClass = RDynUtil.loadClass(annotName);
      if (annotClass == null)
         throw new IllegalArgumentException("No annotation class for getAnnotationValue: " + annotName);
      Annotation annotation = null;
      do {
         annotation = cl.getAnnotation(annotClass);
         if (annotation != null)
            break;
         cl = cl.getSuperclass();
      } while (cl != null);

      if (annotation == null)
         return null;

      Method method = RTypeUtil.getMethod(annotation.getClass(), annotValue);
      return TypeUtil.invokeMethod(annotation, method, (Object[])null);
   }

   public static Object getEnclosingType(Object typeObj, boolean instOnly) {
      if (typeObj instanceof Class) {
         if (instOnly && hasModifier(typeObj, "static"))
            return null;

         return ((Class) typeObj).getDeclaringClass();
      }
      throw new UnsupportedOperationException();
   }

   public static void setWindowId(int id) {
      setThreadLocal("windowId", id);
   }

   public static int getWindowId() {
      Integer res = (Integer) getThreadLocal("windowId");
      if (res == null)
         return -1;
      return res;
   }

   // not sure why this reports an error in IntelliJ's editor but ignore it!

}
