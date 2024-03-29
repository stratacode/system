/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.type;

import sc.dyn.DynUtil;
import sc.util.CoalescedHashMap;
import sc.util.PerfMon;
import sc.util.StringUtil;

import java.lang.reflect.*;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.*;

public class RTypeUtil {

   static Map<Class,Field[]> fieldCache = new WeakHashMap<Class,Field[]>();
   static Map<Class,CoalescedHashMap<String,Class>> innerClassIndex = new WeakHashMap<Class,CoalescedHashMap<String,Class>>();
   static Map<Class,CoalescedHashMap<String,Enum>> enumIndex = new WeakHashMap<Class,CoalescedHashMap<String,Enum>>();
   static Map<Class, MethodCache> methodCache = new HashMap<Class,MethodCache>();

   public static boolean verboseClasses = false;

   public static Map<ClassLoader,Map<String,Class>> loadedClasses = new HashMap<ClassLoader,Map<String,Class>>();
   public static Map<ClassLoader,Map<String,Class>> initializedClasses = new HashMap<ClassLoader,Map<String,Class>>();

   public static Set<String> systemClasses = new HashSet<String>();
   /* Generated from a code snippet above */
   public static String [] systemClassNames = {
           "CharSequence",
           "EnumConstantNotPresentException",
           "SecurityException",
           "Runnable",
           "Math",
           "Short",
           "Comparable",
           "InternalError",
           "Compiler",
           "IncompatibleClassChangeError",
           "Thread",
           "InterruptedException",
           "Long",
           "ProcessImpl",
           "Cloneable",
           "ProcessEnvironment",
           "UNIXProcess",
           "InstantiationError",
           "StackTraceElement",
           "Byte",
           "ClassNotFoundException",
           "Class",
           "IndexOutOfBoundsException",
           "Throwable",
           "Double",
           "NoSuchMethodException",
           "StackOverflowError",
           "Deprecated",
           "StringIndexOutOfBoundsException",
           "ThreadGroup",
           "NumberFormatException",
           "Object",
           "UnsatisfiedLinkError",
           "Float",
           "StringCoding",
           "IllegalStateException",
           "StringValue",
           "CharacterDataUndefined",
           "Character",
           "LinkageError",
           "Terminator",
           "VerifyError",
           "ClassCircularityError",
           "IllegalAccessError",
           "NoSuchFieldException",
           "ClassFormatError",
           "NoClassDefFoundError",
           "Enum",
           "Process",
           "UnsupportedClassVersionError",
           "TypeNotPresentException",
           "AbstractMethodError",
           "StringBuilder",
           "Shutdown",
           "Number",
           "Runtime",
           "SystemClassLoaderAction",
           "RuntimeException",
           "InheritableThreadLocal",
           "AbstractStringBuilder",
           "UnsupportedOperationException",
           "String",
           "ArithmeticException",
           "ThreadDeath",
           "ClassCastException",
           "IllegalThreadStateException",
           "Exception",
           "IllegalArgumentException",
           "ExceptionInInitializerError",
           "StrictMath",
           "Void",
           "CloneNotSupportedException",
           "VirtualMachineError",
           "Iterable",
           "CharacterData0E",
           "SecurityManager",
           "ConditionalSpecialCasing",
           "Package",
           "System",
           "ApplicationShutdownHooks",
           "ThreadLocal",
           "ClassLoader",
           "RuntimePermission",
           "ArrayStoreException",
           "AssertionError",
           "SuppressWarnings",
           "IllegalMonitorStateException",
           "AssertionStatusDirectives",
           "NegativeArraySizeException",
           "CharacterData02",
           "CharacterDataLatin1",
           "OutOfMemoryError",
           "CharacterData01",
           "CharacterData00",
           "Boolean",
           "ProcessBuilder",
           "Error",
           "NoSuchMethodError",
           "NullPointerException",
           "InstantiationException",
           "UnknownError",
           "IllegalAccessException",
           "Readable",
           "Override",
           "StringBuffer",
           "CharacterDataPrivateUse",
           "Integer",
           "NoSuchFieldError",
           "Appendable",
           "ArrayIndexOutOfBoundsException",
           "FunctionalInterface",
           "SafeVarargs",
           "ClassValue",
           "AutoCloseable",
           "BootstrapMethodError",
           "ReflectiveOperationException"
   };
   static {
      for (String cl:systemClassNames)
         systemClasses.add(cl);
   }


   public static void flushCaches() {
      fieldCache.clear();
      innerClassIndex.clear();
      enumIndex.clear();
      methodCache.clear();
      DynUtil.flushCaches();
      loadedClasses.clear();
      initializedClasses.clear();
   }

   public static void flushLoadedClasses() {
      // TODO - most of the time here we only need to remove the NullSentinels.   Maybe we should set a sequence number to invalidate the null the next time we check?
      loadedClasses.clear();
      initializedClasses.clear();
   }

   public static Field[] getFields(Class cl) {
      Field[] res = fieldCache.get(cl);
      if (res != null)
         return res;

      res = cl.getFields();
      if (res != null) {
         for (Field f:res) {
            try {
               f.setAccessible(true);
            }
            catch (RuntimeException exc) {
            }
         }
      }
      fieldCache.put(cl, res);
      return res;
   }

   public static Field[] getFields(Class cl, String modifier, boolean hasModifier) {
      Field[] res = getFields(cl);
      if (modifier == null || res == null)
         return res;

      int ct = 0;
      for (int i = 0; i < res.length; i++)
         if (PTypeUtil.hasModifier(res[i], modifier) == hasModifier)
            ct++;

      if (ct == res.length)
         return res;
      if (ct == 0)
         return null;

      Field[] newRes = new Field[ct];
      ct = 0;
      for (int i = 0; i < res.length; i++)
         if (PTypeUtil.hasModifier(res[i], modifier) == hasModifier)
            newRes[ct++] = res[i];

      return newRes;
   }

   private static CoalescedHashMap<String,Class> NO_INNER_CLASSES = new CoalescedHashMap<String,Class>(1);
   private static CoalescedHashMap<String,Enum> NO_ENUMS = new CoalescedHashMap<String,Enum>(1);

   private static CoalescedHashMap<String,Class> getInnerClassCache(Class outer) {
      CoalescedHashMap<String,Class> innerMap = innerClassIndex.get(outer);
      if (innerMap == null) {
         int superSize = 0;
         // Needs to get non-public classes as well so use declared
         Class[] innerClasses;
         try {
            innerClasses = outer.getDeclaredClasses();
         }
         catch (IllegalAccessError err) {
            System.err.println("IllegalAccessError loading inner classes for: " + outer.getName());
            innerClasses = null;
         }
         CoalescedHashMap<String,Class> superMap;

         Class superClass = outer.getSuperclass();
         int thisSize = 0;
         if (superClass != null) {
            superMap = getInnerClassCache(superClass);
            superSize = superMap.size;
         }
         else
            superMap = null;

         Class[] ifaces = outer.getInterfaces();
         if (ifaces != null) {
            for (Class iface:ifaces) {
               Class[] innerIfaces = iface.getDeclaredClasses();
               if (innerIfaces != null) {
                  thisSize += innerIfaces.length;
               }
            }
         }

         if (innerClasses != null && innerClasses.length > 0) {
            thisSize += innerClasses.length;
         }

         if (thisSize > 0) {
            innerMap = new CoalescedHashMap<String,Class>(thisSize + superSize);

            if (ifaces != null) {
               for (Class iface:ifaces) {
                  Class[] innerIfaces = iface.getDeclaredClasses();
                  for (Class innerIface:innerIfaces)
                     innerMap.put(innerIface.getSimpleName(), innerIface);
               }
            }

            if (innerClasses != null) {
               for (Class in:innerClasses)
                  innerMap.put(in.getSimpleName(), in);
            }

            if (superMap != null) {
               int smLen = superMap.keyTable.length;
               for (int i = 0; i < smLen; i++) {
                  String key = (String) superMap.keyTable[i];
                  if (key != null) {
                     if (innerMap.get(key) == null)
                        innerMap.put(key, (Class)superMap.valueTable[i]);
                  }
               }
            }
            innerClassIndex.put(outer, innerMap);
         }
         else {
            if (superMap != null)
               innerClassIndex.put(outer, innerMap = superMap);
            else
               innerClassIndex.put(outer, innerMap = NO_INNER_CLASSES);
         }
      }
      return innerMap;
   }

   /**
    * Returns the inner class of the provided class with the given name.
    */
   private static Class getSimpleInnerClass(Class outer, String name) {
      CoalescedHashMap<String,Class> innerMap = getInnerClassCache(outer);

      if (innerMap != NO_INNER_CLASSES) {
         Class inner = innerMap.get(name);
         if (inner != null)
            return inner;
      }
      Class superClass = outer.getSuperclass();
      if (superClass != null)
         return getSimpleInnerClass(superClass, name);
      return null;
   }

   public static Class getInnerClass(Class type, String innerTypeName) {
      int ix;
      do {
         Class nextClass;
         ix = innerTypeName.indexOf(".");
         String nextTypeName;
         if (ix != -1) {
            nextTypeName = innerTypeName.substring(0, ix);
            innerTypeName = innerTypeName.substring(ix+1);
         }
         else {
            nextTypeName = innerTypeName;
         }
         nextClass = getSimpleInnerClass(type, nextTypeName);
         if (nextClass == null)
            return null;
         type = nextClass;
      } while (ix != -1);
      return type;
   }

   public static Class[] getAllInnerClasses(Class type, String modifier) {
      CoalescedHashMap<String,Class> cache = getInnerClassCache(type);

      Object[] keyTable = cache.keyTable;
      Object[] valueTable = cache.valueTable;
      int len = keyTable.length;
      int ct = 0;
      ArrayList<Class> result = new ArrayList<Class>(len);
      for (int i = 0; i < len; i++) {
         if (keyTable[i] != null) {
            Class theClass = (Class) valueTable[i];
            if (modifier == null || PTypeUtil.hasModifier(theClass, modifier))
               result.add(theClass);
         }
      }
      return result.toArray(new Class[result.size()]);
   }

   public static String getPrimitiveWrapperName(String prim) {
      String name;
      switch (prim.charAt(0)) {
         case 'i':
            name = "Integer";
            break;
         case 'b':
            if (prim.charAt(1) == 'o')
               name = "Boolean";
            else
               name = "Byte";
            break;
         case 's':
            name = "Short";
            break;
         case 'd':
            name = "Double";
            break;
         case 'c':
            name = "Character";
            break;
         case 'f':
            name = "Float";
            break;
         case 'l':
            name = "Long";
            break;
         case 'v':
            name = "Void";
            break;
         default:
            throw new UnsupportedOperationException();
      }
      return name;
   }

   public static class MethodCache {
      public CoalescedHashMap<String,Method[]> methodsByName;
      // The the complete method list for each class.  This preserves the 'native class file order' of the class,
      // where super methods are before sub-methods unless they are overridden, in which case they occupy the same
      // spot in the table.
      public Method[] methodList;
   }

   static MethodCache initMethodNameCache(Class resultClass) {
      Method[] methods;
      Class superClass;
      try {
         methods = resultClass.getDeclaredMethods();
         superClass = resultClass.getSuperclass();
      }
      catch (NoClassDefFoundError err) {
         System.err.println("Class not found error resolving class: " + resultClass.getName() + ": " + err);
         methods = new Method[0];
         superClass = null;
      }
      MethodCache superCache = null;
      CoalescedHashMap<String,Method[]> superMethods = null, ifMethodList;
      MethodCache[] interfaceMethods = null;
      int tableSize = methods.length;

      if (superClass != null) {
         superCache = getMethodCache(superClass);
         superMethods = superCache.methodsByName;
         tableSize += superMethods.size;
      }

      Class[] superInterfaces = resultClass.getInterfaces();
      if (superInterfaces != null) {
         interfaceMethods = new MethodCache[superInterfaces.length];
         for (int i = 0; i < superInterfaces.length; i++) {
            Class superInt = superInterfaces[i];
            interfaceMethods[i] = getMethodCache(superInt);
            tableSize += interfaceMethods[i].methodsByName.size;
         }
      }

      MethodCache methodCacheEnt = new MethodCache();
      methodCacheEnt.methodsByName = new CoalescedHashMap<String,Method[]>(tableSize);
      CoalescedHashMap<String,Method[]> cache = methodCacheEnt.methodsByName;

      Method[] allMethodsList = null;

      if (superCache != null)
         allMethodsList = superCache.methodList;

      if (interfaceMethods != null) {
         for (int k = 0; k < interfaceMethods.length; k++) {
            ifMethodList = interfaceMethods[k].methodsByName;
            Object[] keys = ifMethodList.keyTable;
            Object[] values = ifMethodList.valueTable;
            for (int i = 0; i < keys.length; i++) {
               String key = (String) keys[i];
               if (key != null) {
                  Method[] oldList = cache.get(key);
                  Method[] newList = mergeMethodLists(oldList, (Method[]) values[i]);
                  cache.put(key, newList);
               }
            }
            allMethodsList = mergeMethodAllLists(interfaceMethods[k].methodList, allMethodsList);
         }
      }

      if (superMethods != null) {
         Object[] keys = superMethods.keyTable;
         Object[] values = superMethods.valueTable;
         for (int i = 0; i < keys.length; i++) {
            String key = (String) keys[i];
            if (key != null) {
               Method[] oldList = cache.get(key);
               Method[] newList = mergeMethodLists(oldList, (Method[]) values[i]);
               cache.put(key, newList);
            }
         }
      }

      methodCacheEnt.methodList = mergeMethodAllLists(methods, allMethodsList);

      for (int i = 0; i < methods.length; i++) {
         Method method = methods[i];
         if ((method.getModifiers() & 0x1000) != 0) {
            continue;
         }
         if ((method.getModifiers() & Modifier.VOLATILE) != 0) {
            continue;
         }
         try {
            if ((method.getModifiers() & Modifier.PUBLIC) == 0)
               method.setAccessible(true);
         }
         catch (RuntimeException exc) {
            continue; // In Java11 some methods cannot be made accessible
         }
         String methodName = method.getName();

         Method[] methodList = cache.get(methodName);
         if (methodList == null) {
            methodList = new Method[1];
            methodList[0] = method;
            cache.put(methodName, methodList);
         }
         else {
            Method[] superMethodList = superMethods != null ? superMethods.get(methodName) : null;
            Method[] ifaceMethodList = interfaceMethods != null ? getInterfaceMethods(interfaceMethods, methodName) : null;
            superMethodList = mergeMethodLists(superMethodList, ifaceMethodList);
            boolean addToList = true;
            if (superMethodList != null) {
               for (int j = 0; j < superMethodList.length; j++) {
                  if (overridesMethod(method, superMethodList[j])) {
                     addToList = false;
                     // Only override the method once for a given class.  Java has an annoying habit of returning
                     // interface methods that have different signatures from the class versions after the main
                     // methods in this list.  As long as we ignore those other methods, we get by ok.
                     if (j < methodList.length && superMethodList[j] == methodList[j]) {
                        // We start out sharing the array from our super class = make a copy on the
                        // first change only
                        if (superMethodList == methodList) {
                           // Dalvik don't grok: Arrays.copyOf(methodList, methodList.length);
                           Method[] newMethodList = new Method[methodList.length];
                           System.arraycopy(methodList, 0, newMethodList, 0, methodList.length);
                           methodList = newMethodList;
                           cache.put(methodName, methodList);
                        }
                        method = pickMoreSpecificMethod(method, superMethodList[j], null);
                        methodList[j] = method;
                     }
                     // We'd like to break but unfortunately Java returns methods with the same signature from
                     // getDeclared methods (e.g. AbstractStringBuilder.append(char)).   it also will not let you
                     // use those methods on an instance in this case.  So we need to replace all methods in the
                     // methodList that are overridden by this method.
                     //break;
                  }
               }
            }

            // New method - expand the list
            if (addToList) {
               Method[] newCachedList = new Method[methodList.length+1];
               System.arraycopy(methodList, 0, newCachedList, 0, methodList.length);
               newCachedList[methodList.length] = method;
               methodList = newCachedList;
               cache.put(methodName, methodList);
            }
            else {
               for (int j = 0; j < methodList.length; j++) {
                  Method otherMeth = methodList[j];
                  if (otherMeth != method && overridesMethod(method, otherMeth)) {
                     Method newMethod = pickMoreSpecificMethod(method, otherMeth, null);
                     if (newMethod == method) {
                        methodList[j] = method;
                        break;
                     }
                  }
               }
            }
         }
      }

      methodCache.put(resultClass, methodCacheEnt);
      return methodCacheEnt;
   }

   private static Method[] mergeMethodLists(Method[] subList, Method[] superList) {
      if (superList == null)
         return subList;
      else if (subList == null)
         return superList;

      int superLen = superList.length;
      int subLen = subList.length;
      ArrayList<Method> res = new ArrayList<Method>(superLen + subLen);
      for (int i = 0; i < subLen; i++) {
         Method subMeth = subList[i];
         res.add(subMeth);
      }
      for (int i = 0; i < superLen; i++) {
         Method superMeth = superList[i];
         int j;
         for (j = 0; j < subLen; j++) {
            if (overridesMethod(subList[j], superMeth)) {

               Method newMeth = pickMoreSpecificMethod(superMeth, subList[j], null);
               if (newMeth != res.get(j))
                  res.set(j, newMeth);
               break;
            }
         }
         if (j == subLen)
            res.add(superMeth);
      }
      return res.toArray(new Method[res.size()]);
   }

   private static Method[] mergeMethodAllLists(Method[] subList, Method[] superList) {
      if (superList == null)
         return subList;
      else if (subList == null)
         return superList;

      int superLen = superList.length;
      int subLen = subList.length;
      ArrayList<Method> res = new ArrayList<Method>(superLen + subLen);
      for (int i = 0; i < superLen; i++) {
         Method superMeth = superList[i];
         res.add(superMeth);
      }
      for (int i = 0; i < subLen; i++) {
         Method subMeth = subList[i];
         int j;
         for (j = 0; j < superLen; j++) {
            if (overridesMethod(superList[j], subMeth)) {

               Method newMeth = pickMoreSpecificMethod(subMeth, superList[j], null);
               if (newMeth != res.get(j))
                  res.set(j, newMeth);
               break;
            }
         }
         if (j == superLen)
            res.add(subMeth);
      }
      return res.toArray(new Method[res.size()]);
   }

   private static Method[] getInterfaceMethods(MethodCache[] interfaceMethods, String name) {
      Method[] res = null;
      for (MethodCache mc:interfaceMethods) {
         Method[] nextRes = mc.methodsByName.get(name);
         res = mergeMethodLists(res, nextRes);
      }
      return res;
   }

   public static boolean overridesMethod(Method subTypeMethod, Method superTypeMethod) {
      String subName = subTypeMethod.getName();
      String superName = superTypeMethod.getName();
      if (!subName.equals(superName))
         return false;

      Class[] subParamTypes = subTypeMethod.getParameterTypes();
      Class[] superParamTypes = superTypeMethod.getParameterTypes();
      int len;
      if ((len = subParamTypes.length) != superParamTypes.length)
         return false;

      for (int i = 0; i < len; i++) {
         if (!TypeUtil.isAssignableTypesFromOverride(superParamTypes[i], subParamTypes[i]))
            return false;
      }
      return true;
   }

   public static Method[] getMethodsWithModifier(Class cl, String modifier, boolean hasModifier) {
      MethodCache cache = getMethodCache(cl);
      return cache.methodList;
   }

   // TODO: maybe lazily cache these?  It will get called only for a few types but those types
   // may see this called over and over again.
   public static IBeanMapper[] getPersistProperties(Class theClass) {
      IBeanMapper[] props = TypeUtil.getProperties(theClass);
      ArrayList<IBeanMapper> perProps = new ArrayList<IBeanMapper>();
      for (int i = PTypeUtil.MIN_PROPERTY; i < props.length; i++) {
         IBeanMapper prop = props[i];
         if (isPersistentProperty(prop))
            perProps.add(prop);
      }
      return perProps.toArray(new IBeanMapper[perProps.size()]);
   }

   public static Constructor[] getConstructors(Class resultClass) {
      try {
         Constructor[] arr = resultClass.getDeclaredConstructors();
         for (Constructor cs : arr) {
            try {
               if ((cs.getModifiers() & Modifier.PUBLIC) == 0)
                  cs.setAccessible(true);
            }
            catch (RuntimeException exc) {
               continue; // In Java11 some methods cannot be made accessible
            }
         }
         return arr;
      }
      catch (NoClassDefFoundError exc) {
         System.err.println("*** Missing class in classpath loading constructors for: " + resultClass.getName() + ":" + exc);
         return new Constructor[0];
      }
   }

   /** Returns the list of methods with the given name */
   public static Method[] getMethods(Class resultClass, String methodName) {
      MethodCache cache = getMethodCache(resultClass);
      return cache.methodsByName.get(methodName);
   }

   public static Constructor getConstructorFromTypeSignature(Class methClass, String methodSig) {
      Constructor[] constrs = getConstructors(methClass);
      if (constrs == null)
         return null;

      for (Constructor constr:constrs) {
         if (StringUtil.equalStrings(getTypeSignature(constr), methodSig))
            return constr;
      }
      return null;
   }

   public static Method getMethodFromTypeSignature(Class methClass, String methodName, String methodSig) {
      Method[] meths = getMethods(methClass, methodName);
      if (meths == null)
         return null;

      for (Method meth:meths) {
         if (StringUtil.equalStrings(getTypeSignature(meth), methodSig))
            return meth;
      }
      return null;
   }

   public static Method[] getMethods(Class resultClass, String methodName, String modifier) {
      if (modifier == null)
         return getMethods(resultClass, methodName);
      MethodCache cache = getMethodCache(resultClass);
      Method[] methods = cache.methodsByName.get(methodName);
      if (methods == null)
         return null;
      int ct = 0;
      for (int i = 0; i < methods.length; i++)
         if (PTypeUtil.hasModifier(methods[i], modifier))
            ct++;
      if (ct == methods.length)
         return methods;
      else if (ct == 0)
         return null;
      else {
         Method[] newMethods = new Method[ct];
         ct = 0;
         for (int i = 0; i < methods.length; i++)
            if (PTypeUtil.hasModifier(methods[i], modifier))
               newMethods[ct++] = methods[i];
         return newMethods;
      }
   }

   public static MethodCache getMethodCache(Class resultClass) {
      MethodCache cache = methodCache.get(resultClass);
      if (cache == null)
         cache = initMethodNameCache(resultClass);

      return cache;
   }

   public static Constructor pickMoreSpecificConstructor(Constructor c1, Constructor c2, Class[] paramTypes) {
      if (c1 == null)
         return c2;
      if (c2 == null)
         return c1;

      Class[] c1Params = c1.getParameterTypes();
      Class[] c2Params = c2.getParameterTypes();

      for (int i = 0; i < paramTypes.length; i++) {
         Class arg = paramTypes[i];
         Class c1Arg = c1Params[i];
         Class c2Arg = c2Params[i];
         sc.type.Type c1Type = sc.type.Type.get(c1Arg);
         sc.type.Type c2Type = sc.type.Type.get(c2Arg);
         sc.type.Type argType = sc.type.Type.get(arg);

         if (c1Arg == c2Arg)
            continue;

         if (argType == c1Type)
            return c1;
         if (argType == c2Type)
            return c2;

         if (c1Arg.isAssignableFrom(c2Arg))
            return c2;
         return c1;
      }
      return c1;
   }

   // TODO: needs logic like the above to pick the most specific method... Java does this at compile time
   // not at reflection time but we should be following the compile time rules at runtime when interpreting code.
   /** Chooses the method with the more specific return type - as per the searchMethods method in java.lang.Class */
   public static Method pickMoreSpecificMethod(Method res, Method toCheck, Class[] argTypes) {
      if (res == null)
         return toCheck;

      if (argTypes != null) {

         Class[] resParams = res.getParameterTypes();
         Class[] tcParams = toCheck.getParameterTypes();

         for (int i = 0; i < resParams.length; i++) {
            Class arg = argTypes[i];
            Class resArg = resParams[i];
            Class tcArg = tcParams[i];
            sc.type.Type resType = sc.type.Type.get(resArg);
            sc.type.Type tcType = sc.type.Type.get(tcArg);
            sc.type.Type argType = sc.type.Type.get(arg);

            if (resArg == tcArg)
               continue;

            if (argType == resType)
               return res;
            if (argType == tcType)
               return toCheck;

            if (resArg.isAssignableFrom(tcArg))
               return toCheck;
            return res;
         }
      }

      if (toCheck.getReturnType().isAssignableFrom(res.getReturnType()))
         return res;
      return toCheck;
   }

   public static Field getField(Class resultClass, String fieldName) {
      try {
         return resultClass.getField(fieldName);
      }
      catch (NoSuchFieldException exc)
      {}
      return null;
   }

   public static Constructor getDeclaredConstructor(Class p) {
      try {
         return p.getDeclaredConstructor(p);
      }
      catch (NoSuchMethodException exc)
      {}
      return null;
   }

   public static int getNumMethods(Class cl) {
      return getMethodCache(cl).methodsByName.size;
   }

   /**
    * Sadly, Java's Class.getMethod requires exact argument types.  We need a more flexible mechanism
    * where we can look up the matching method for a given declaration.
    */
   public static Method getMethod(Class resultClass, String methodName, Class<?>... types) {
      Method[] list = getMethods(resultClass, methodName);

      int numTypeParams = types == null ? 0 : types.length;
      Method res = null;
      if (list != null) {
         for (int i = 0; i < list.length; i++) {
            Method toCheck = list[i];
            if (toCheck.getName().equals(methodName)) {
               Class[] parameterTypes = toCheck.getParameterTypes();
               int numParams = parameterTypes.length;
               if (numParams == 0 && numTypeParams == 0)
                  res = pickMoreSpecificMethod(res, toCheck, null);
                  // TODO: the logic here for repeating params is not right..
               else if (numParams == numTypeParams) {
                  int j;
                  for (j = 0; j < numTypeParams; j++) {
                     if (types[j] != null && !TypeUtil.isAssignableFromParameter(parameterTypes[j],types[j])) {
                        if (toCheck.isVarArgs() && j == numTypeParams - 1 && parameterTypes[j].isArray()) {
                           if (TypeUtil.isAssignableFromParameter(parameterTypes[j].getComponentType(), types[j]))
                              j++; // Success - this is the repeating parameter case
                        }
                        break;
                     }
                  }
                  if (j == numTypeParams)
                     res = pickMoreSpecificMethod(res, toCheck, types);
               }
            }
         }
      }
      return res;
   }

   public static Constructor getConstructor(Class resultClass, Class<?>... types) {
      Constructor[] list = getConstructors(resultClass);

      Constructor res = null;
      if (list != null) {
         for (int i = 0; i < list.length; i++) {
            Constructor toCheck = list[i];
            Class[] parameterTypes = toCheck.getParameterTypes();
            if ((parameterTypes == null || parameterTypes.length == 0) && (types == null || types.length == 0))
               res = pickMoreSpecificConstructor(toCheck, res, types);
            else if (parameterTypes != null && types != null) {
               // For some reason, the 0th parameter type is the class type so we have to skip it here.
               if (parameterTypes.length == types.length) {
                  int j;
                  for (j = 0; j < types.length; j++) {
                     if (types[j] != null && !TypeUtil.isAssignableFromParameter(parameterTypes[j],types[j])) {
                        if (j == types.length - 1 && parameterTypes[j].isArray()) {
                           if (TypeUtil.isAssignableFromParameter(parameterTypes[j].getComponentType(), types[j]))
                              j++; // Success - this is the repeating parameter case
                        }
                        break;
                     }
                  }
                  if (j == types.length)
                     res = pickMoreSpecificConstructor(toCheck, res, types);
               }
            }
         }
      }
      return res;
   }

   public static Object getEnum(Class classDecl, String name) {
      CoalescedHashMap<String,Enum> enumMap = enumIndex.get(classDecl);
      if (enumMap == null) {
         // Needs to get non-public classes as well so use declared
         Object[] enumConsts = classDecl.getEnumConstants();
         if (enumConsts != null) {
            enumMap = new CoalescedHashMap<String,Enum>(enumConsts.length);
            for (Object in:enumConsts)
               enumMap.put(in.toString(), (Enum)in);

            enumIndex.put(classDecl, enumMap);
         }
         else {
            enumIndex.put(classDecl, NO_ENUMS);
            return null;
         }
      }
      return enumMap.get(name);
   }

   public static Object getDeclaringClass(Object method) {
      if (method instanceof Method)
         return ((Method) method).getDeclaringClass();
      else
         throw new IllegalArgumentException("Invalid method type in getDeclaringClass");
   }

   public static boolean isPersistentProperty(IBeanMapper prop) {
      Object type = prop.getPropertyMember();

      // can't be static or transient
      if (PTypeUtil.hasModifier(type, "static") || PTypeUtil.hasModifier(type, "transient"))
         return false;

      // has to be public
      if (!PTypeUtil.hasModifier(type, "public"))
         return false;

      // needs to be read-write
      if (prop.getGetSelector() == null || prop.getSetSelector() == null)
         return false;

      return true;
   }

   public static boolean isGetMethod(Method method) {
      String name = method.getName();
      if (method.getReturnType() != Void.class && method.getParameterTypes().length == 0) {
         return (name.startsWith("get") && name.length() >= 4) ||
                (name.startsWith("is") && name.length() >= 3);
      }
      return false;
   }

   public static boolean isGetIndexMethod(Method method) {
      String name = method.getName();
      Class[] pTypes = method.getParameterTypes();

      if (method.getReturnType() != Void.class && pTypes.length == 1) {
         return (name.startsWith("get") && name.length() >= 4) && pTypes[0] == int.class;
      }
      return false;
   }

   public static boolean isSetIndexMethod(Method method) {
      String name = method.getName();
      Class[] pTypes = method.getParameterTypes();

      if (method.getReturnType() == Void.class && pTypes.length == 2) {
         return (name.startsWith("set") && name.length() >= 4) && pTypes[0] == int.class;
      }
      return false;
   }

   /** For set methods, we allow both void setX(int) and "? setX(int)".  Some do int setX(int) and others this setX(int) */
   public static boolean isSetMethod(Method method) {
      String name = method.getName();
      Class returnType;
      Class[] paramTypes;
      return name.startsWith("set") && (paramTypes = method.getParameterTypes()).length == 1 && name.length() >= 4;
              /*
              && ((returnType = method.getReturnType()) == Void.class ||
               returnType == paramTypes[0])
              */
   }

   public static Method resolveMethod(Class resultClass, String methodName, Class<?>... types) {
      Method meth = getMethod(resultClass, methodName, types);
      if (meth == null) {
         String typeName = DynUtil.getTypeName(resultClass, false);
         String methodAlias = getMethodAlias(typeName, methodName);
         if (methodAlias != null) {
            meth = resolveMethod(resultClass, methodAlias, types);
         }
      }
      if (meth == null)
         System.err.println("Unable to resolve method: " + resultClass.getName() + "." + methodName + " with types: " + Arrays.asList(types));
      return meth;
   }

   public static Object resolveMethod(Class resultClass, String methodName, String paramSig) {
      Object meth = getMethodFromTypeSignature(resultClass, methodName, paramSig);
      if (meth == null) {
         String typeName = DynUtil.getTypeName(resultClass, false);
         String methodAlias = getMethodAlias(typeName, methodName);
         if (methodAlias != null) {
            meth = resolveMethod(resultClass, methodAlias, paramSig);
         }
         /*
         if (meth == null) {
            System.err.println("Unable to resolve method: " + resultClass.getName() + "." + methodName + " with param signature: " + paramSig);
            meth = getMethodFromTypeSignature(resultClass, methodName, paramSig);
         }
         */
      }
      return meth;
   }

   public static String getTypeSignature(Constructor meth) {
      Class[] types = meth.getParameterTypes();
      if (types == null)
         return null;
      StringBuilder sb = new StringBuilder();
      for (Class pt:types) {
         sb.append(getSignature(pt));
      }
      return sb.toString();
   }

   public static String getTypeSignature(Method meth) {
      Class[] types = meth.getParameterTypes();
      if (types == null)
         return null;
      StringBuilder sb = new StringBuilder();
      for (Class pt:types) {
         sb.append(getSignature(pt));
      }
      return sb.toString();
   }

   public static String getParameterString(Method meth) {
      Class[] types = meth.getParameterTypes();
      if (types == null)
         return null;
      StringBuilder sb = new StringBuilder();
      boolean first = true;
      sb.append("(");
      for (Class pt:types) {
         if (!first)
            sb.append(", ");
         sb.append(CTypeUtil.getClassName(pt.getName()));
         first = false;
      }
      sb.append(")");
      return sb.toString();
   }

   public static String getSignature(Class theClass) {
      if (theClass.isArray()) {
         return "[" + getSignature(theClass.getComponentType());
      }
      if (theClass.isPrimitive())
         return String.valueOf(sc.type.Type.get(theClass).arrayTypeCode);
      else
        return "L" + theClass.getName().replace(".", "/") + ";";
   }

   public static Object getAnnotationValue(java.lang.annotation.Annotation annotation, String annotName) {
      return PTypeUtil.invokeMethod(annotation, getMethod(annotation.getClass(), annotName), (Object[])null);
   }

   public static Class findCommonSuperClass(Class c1, Class c2) {
      Class o1 = c1;
      Class o2 = c2;

      if (o1 == null && o2 != null)
         return o2;
      if (o2 == null && o1 != null)
         return o1;

      while (o1 != null && o2 != null && !o1.isAssignableFrom(o2))
         o1 = o1.getSuperclass();

      while (c1 != null && o2 != null && !o2.isAssignableFrom(c1))
         o2 = o2.getSuperclass();

      return o1 != null && o2 != null && o1.isAssignableFrom(o2) ? o2 : o1;
   }

   public static boolean isInstance(Class type, Object argValue) {
      return type.isInstance(argValue);
   }

   public static String getPropertyNameFromSelector(Object selector) {
      if (selector instanceof Field)
         return ((Field) selector).getName();
      else if (selector instanceof Method) {
         String name = ((Method) selector).getName();
         if (name.startsWith("is"))
            return CTypeUtil.decapitalizePropertyName(name.substring(2));
         else
            return CTypeUtil.decapitalizePropertyName(name.substring(3));
      }
      else if (selector instanceof String)
         return (String) selector;
      else if (selector instanceof IBeanMapper)
         return ((IBeanMapper) selector).getPropertyName();
      return null;
   }

   public static Class getPropertyDeclaringClass(Object selector) {
      if (selector instanceof Member)
         return ((Member) selector).getDeclaringClass();
      else if (selector instanceof BeanMapper)
         return ((BeanMapper) selector).getPropertyMember().getDeclaringClass();

      return null;
   }

   private static class NullSentinelClass {
   }

   private static Map<String,Class> getClassLoaderMap(ClassLoader cl) {
      Map<String,Class> clMap = loadedClasses.get(cl);
      if (clMap == null) {
         synchronized (loadedClasses) {
            clMap = loadedClasses.get(cl);
            if (clMap == null) {
               loadedClasses.put(cl, clMap = new HashMap<String, Class>());
            }
         }
      }
      return clMap;
   }

   private static Map<String,Class> getClassLoaderInitMap(ClassLoader cl) {
      Map<String,Class> clMap = initializedClasses.get(cl);
      if (clMap == null) {
         synchronized (initializedClasses) {
            clMap = initializedClasses.get(cl);
            if (clMap == null) {
               initializedClasses.put(cl, clMap = new HashMap<String, Class>());
            }
         }
      }
      return clMap;
   }

   /**
    * Returns an initialized class by name.
    */
   public static Class loadClass(String className) {
      Map<String,Class> clMap = null;
      try {
         Class res;
         clMap = getClassLoaderInitMap(null);
         res = clMap.get(className);
         if (res != null) {
            if (res == NullSentinelClass.class) {
               return null;
            }
            return res;
         }
         res = Class.forName(className);
         if (res != null) {
            clMap.put(className, res);
            if (verboseClasses) {
               System.out.println("Loaded class: " + className + " from default classLoader: " + res.getClassLoader());
            }
         }
         return res;
      }
      catch (ClassNotFoundException exc) {
         clMap.put(className, NullSentinelClass.class);
      }
      return null;
   }

   public static Class loadClass(ClassLoader classLoader, String className, boolean initialize) {
      if (className == null) {
         System.out.println("Error: null class name to TypeUtil.loadClass");
         return null;
      }
      Map<String,Class> clMap = null;
      try {
         PerfMon.start("loadClass", false);
         Class res;
         if (classLoader == null) {
            return loadClass(className);
         }
         try {
            clMap = initialize ? getClassLoaderInitMap(classLoader) : getClassLoaderMap(classLoader);
            res = clMap.get(className);
            if (res != null) {
               if (res == NullSentinelClass.class) {
                  return null;
               }
               return res;
            }
            res = Class.forName(className, initialize, classLoader);
            if (res != null) {
               clMap.put(className, res);
               if (verboseClasses)
                  System.out.println("Loaded class: " + className + " initialize = " + initialize + " from classLoader: " + classLoader);
            }
            return res;
         }
         // The Javascript layers need to redefine java.util and java.lang source files.  We may try to load the compiled
         // version of these classes cause layers let you override things.  Java mandates you don't do that though, probably
         // a good idea.   In this case, just go back to the primordial class loader to get these class files.
         catch (SecurityException exc) {
            if (verboseClasses)
               System.err.println("*** Security exception loading class with class loader: " + exc);
            return Class.forName(className);
         }
      }
      catch (ClassNotFoundException exc) {
         clMap.put(className, NullSentinelClass.class);
      }
      catch (NoClassDefFoundError exc2) {
         clMap.put(className, NullSentinelClass.class);
      }
      catch (IllegalAccessError exc3) {
         clMap.put(className, NullSentinelClass.class);
      }
      finally {
         PerfMon.end("loadClass");
      }
      return null;
   }

   /** Constructs a new component type object with the given set of parameters.  Calls the newX method generated for the component */
   public static Object newComponent(Class theClass, Object... params) {
      String methodName = "new" + CTypeUtil.capitalizePropertyName(CTypeUtil.getClassName(theClass.getName().replace('$', '.')));
      Method method = getMethodFromArgs(theClass, methodName, params);
      // If there's no newX method, we'll try the constructor.  Maybe it's a component which does not need graph construction
      // but does need init, start, etc.
      if (method == null) {
         return DynUtil.createInstance(theClass, null, params);
      }
      if (method == null)
         throw new IllegalArgumentException("No method named: " + methodName + " with args: " + params + " in: " +  getMethods(theClass, methodName));
      if (!PTypeUtil.hasModifier(method, "static"))
         throw new IllegalArgumentException("Non-static method: " + methodName + " called from a static context");
      return invokeInternal(method, theClass, null, params);
   }

   public static Object newInnerComponent(Object thisObj, Class accessClass, Class theClass, String name, Object... params) {
      String methodName = "new" + CTypeUtil.capitalizePropertyName(CTypeUtil.getClassName(name.replace('$', '.')));
      Method method = getMethodFromArgs(accessClass, methodName, params);
      if (method == null) {
         throw new IllegalArgumentException("No component new" + name + " method defined for class: " + theClass + "." + methodName + "(" + params + ")");
      }
      if (PTypeUtil.hasModifier(method, "static")) {
         Object[] newParams = new Object[params.length+1];
         System.arraycopy(params, 0, newParams, 1, params.length);
         newParams[0] = thisObj;
         // Need to get the version of the method with the this object in its parameters
         Method newMethod = getMethodFromArgs(accessClass, methodName, newParams);
         if (newMethod != null) {
            method = newMethod;
            params = newParams;
         }
         else
            throw new IllegalArgumentException("No method named: " + methodName + " on class: " + accessClass + " with params: " + newParams + " for new inner component");
      }
      return invokeInternal(method, accessClass, thisObj, params);
   }

   public static String getGenericTypeName(Object resultType, Object member, boolean includeDims) {
      if (member instanceof Field)
         return genericTypeToTypeName(((Field) member).getGenericType(), includeDims);
      else if (member instanceof Method)
         return genericTypeToTypeName(((Method) member).getGenericReturnType(), includeDims);
      else if (member instanceof Class) {
         return TypeUtil.getTypeName((Class) member, includeDims);
      }
      else
         throw new UnsupportedOperationException();
   }


   public static String genericTypeToTypeName(java.lang.reflect.Type t, boolean includeDims) {
      if (t instanceof GenericArrayType) {
         GenericArrayType gat = (GenericArrayType) t;
         String componentName = genericTypeToTypeName(gat.getGenericComponentType(), includeDims);
         if (includeDims)
            return componentName + "[]";
         else
            return componentName;
      }
      if (t instanceof ParameterizedType) {
         ParameterizedType pt = (ParameterizedType) t;
         java.lang.reflect.Type[] args = pt.getActualTypeArguments();
         StringBuilder sb = new StringBuilder();
         sb.append(genericTypeToTypeName(pt.getRawType(), includeDims));
         sb.append("<");
         for (java.lang.reflect.Type at:args) {
            // Do not propagate includeDims inside of the <> since it is part of the type args then
            sb.append(genericTypeToTypeName( at, true));
         }
         sb.append(">");
         return sb.toString();
      }
      else if (t instanceof Class) {
         return ((Class) t).getName();
      }
      else if (t instanceof TypeVariable) {
         return ((TypeVariable) t).getName();
      }
      else
         throw new UnsupportedOperationException();
   }

   public static Method getMethodFromArgs(Class theClass, String methodName, Object...argValues) {
      Method[] list = getMethods(theClass, methodName);
      if (list == null)
         return null;
      Method method = null;
      if (list.length == 1)
         method = list[0];
      else {
         for (Method m:list) {
            Class[] ptypes = m.getParameterTypes();
            if (ptypes.length == argValues.length) {
               int i = 0;
               for (Class pt:ptypes) {
                  Object argValue = argValues[i++];
                  if (argValue != null && !pt.isInstance(argValue)) {
                     if (pt.isPrimitive()) {
                        String wrapTypeName = getPrimitiveWrapperName(pt.getTypeName());
                        Class wrapType = loadClass("java.lang." + wrapTypeName);
                        if (!DynUtil.isAssignableFrom(wrapType, argValue.getClass()))
                           break;
                     }
                     else
                        break;
                  }
               }
               if (i == ptypes.length) {
                  method = m;
                  break;
               }
            }
         }
      }
      return method;
   }

   public static Object invokeMethod(Class theClass, Object thisObject, String methodName, Object... argValues) {
      Method method = getMethodFromArgs(theClass, methodName, argValues);
      if (method == null)
         throw new IllegalArgumentException("No method named: " + methodName + " with args: " + argValues + " in: " +  getMethods(theClass, methodName));
      if (thisObject == null && !PTypeUtil.hasModifier(method, "static"))
         throw new IllegalArgumentException("Non-static method: " + methodName + " called from a static context");
      return invokeInternal(method, theClass, thisObject, argValues);
   }

   private static Object invokeInternal(Method method, Class theClass, Object thisObject, Object... argValues) {
      try {
         return method.invoke(thisObject, argValues);
      }
      catch (IllegalAccessException exc) {
         System.err.println("*** Invoke method failed due to access exception: " + exc.toString());
         throw new IllegalArgumentException("Can't invoke method: " + method);
      }
      catch (InvocationTargetException exc) {
         System.err.println("*** Invoke method failed due to error in the method: " + exc.getTargetException());
         exc.getTargetException().printStackTrace();
         throw new IllegalArgumentException("Can't invoke method: " + method);
      }
      catch (IllegalArgumentException exc) {
         throw exc;
      }
      catch (NullPointerException exc) {
         throw exc;
      }
   }

   public static Object createInstance(Class resultClass) {
      try {
         return resultClass.newInstance();
      }
      catch (InstantiationException exc) {
         System.err.println("*** createInstance error: " + exc);
      }
      catch (IllegalAccessException exc) {
         System.err.println("*** createInstance error: " + exc);
      }
      return null;
   }

   public static Object[] getDeclaredProperties(Class typeObj, String modifier) {
      ArrayList<Object> declProps = new ArrayList<Object>();
      IBeanMapper[] allProps = TypeUtil.getProperties(typeObj, modifier);
      for (int i = 0; i < allProps.length; i++) {
         Object prop = allProps[i];
         if (prop != null && getEnclosingType(prop) == typeObj) {
            declProps.add(prop);
         }
      }
      return declProps.toArray();
   }

   public static Object getEnclosingType(Object srcMember) {
      if (srcMember instanceof Member) {
         return ((Member) srcMember).getDeclaringClass();
      }
      else if (srcMember instanceof Class) {
         return ((Class) srcMember).getDeclaringClass();
      }
      else if (srcMember instanceof IBeanMapper)
         return getEnclosingType(((IBeanMapper) srcMember).getPropertyMember());
      throw new UnsupportedOperationException();
   }

   static HashMap<Object, IFromString> fromStringTable = new HashMap<Object,IFromString>();
   static {
      fromStringTable.put(String.class, new IFromString() {
         public String fromString(String value) {
            return '"' + value + '"';
         }
      });
      IFromString tmp;

      fromStringTable.put(Short.class, tmp = new IFromString() {
         public String fromString(String value) {
            try {
               return String.valueOf(Short.valueOf(value));
            }
            catch (NumberFormatException exc) {
               throw new IllegalArgumentException("Invalid string to short conversion: " + exc.toString());
            }
         }
      });
      fromStringTable.put(Short.TYPE, tmp);

      fromStringTable.put(Integer.class, tmp = new IFromString() {
         public String fromString(String value) {
            try {
               return String.valueOf(Integer.valueOf(value));
            }
            catch (NumberFormatException exc) {
               throw new IllegalArgumentException("Invalid string to integer conversion: " + exc.toString());
            }
         }
      });
      fromStringTable.put(Integer.TYPE, tmp);

      fromStringTable.put(Float.class, tmp = new IFromString() {
         public String fromString(String value) {
            try {
               return String.valueOf(Float.valueOf(value));
            }
            catch (NumberFormatException exc) {
               throw new IllegalArgumentException("Invalid string to float conversion: " + exc.toString());
            }
         }
      });
      fromStringTable.put(Float.TYPE, tmp);

      fromStringTable.put(Double.class, tmp = new IFromString() {
         public String fromString(String value) {
            try {
               return String.valueOf(Double.valueOf(value));
            }
            catch (NumberFormatException exc) {
               throw new IllegalArgumentException("Invalid string to double conversion: " + exc.toString());
            }
         }
      });
      fromStringTable.put(Double.TYPE, tmp);

      fromStringTable.put(Boolean.class, tmp = new IFromString() {
         public String fromString(String value) {
            try {
               return String.valueOf(Boolean.valueOf(value));
            }
            catch (NumberFormatException exc) {
               throw new IllegalArgumentException("Invalid string to boolean conversion: " + exc.toString());
            }
         }
      });
      fromStringTable.put(Boolean.TYPE, tmp);

      fromStringTable.put(BigDecimal.class, new IFromString() {
         public String fromString(String value) {
            try {
               return "new BigDecimal(\"" + value + "\")";
            }
            catch (NumberFormatException exc) {
               throw new IllegalArgumentException("Invalid string to BigDecimal conversion: " + exc.toString());
            }
         }
      });
   }

   public static boolean canConvertTypeFromString(Object propType) {
      return fromStringTable.get(propType) != null;
   }

   public static String fromString(Object propType, String strValue) {
      IFromString fromStr = fromStringTable.get(propType);
      if (fromStr == null)
         throw new IllegalArgumentException("No ability to convert values of type: " + propType + " from a string");
      return fromStr.fromString(strValue);
   }

   static HashMap<String,Map<String,String>> methodAliases = new HashMap<String,Map<String,String>>();

   class MethodAlias {
      String typeName;
      String methodName;
   }

   public static String getMethodAlias(String typeName, String name) {
      Map<String,String> aliasMap = methodAliases.get(typeName);
      if (aliasMap != null)
         return aliasMap.get(name);
      return null;
   }

   public static void addMethodAlias(String typeName, String name, String replaceWith) {
      if (name.equals(replaceWith))
         throw new IllegalArgumentException("Method alias that's the same as the name not allowed");
      Map<String,String> aliasMap = methodAliases.get(typeName);
      if (aliasMap == null) {
         aliasMap = new TreeMap<String,String>();
         methodAliases.put(typeName, aliasMap);
      }
      aliasMap.put(name, replaceWith);
      aliasMap.put(replaceWith, name);
   }
}
