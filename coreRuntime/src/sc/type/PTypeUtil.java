/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.type;

import java.util.*;

import sc.dyn.IDynObject;
import sc.dyn.CompReverseMethodMapper;
import sc.bind.MethodBinding;
import sc.dyn.IReverseMethodMapper;
import sc.js.JSSettings;

/** A version of the PTypeUtil class with minimum dependencies on core Java features.  Originally, this class was built for
 * the GWT integration to separate out methods which did not exist in that runtime.  It now is primarily used to separate
 * out a java-script only implementation of various type utilities.  This class is replaced in the JS runtime by scdyn.js.
 */
@JSSettings(jsLibFiles="js/scdyn.js")
public class PTypeUtil {
   public static Map<String,Object> threadLocalMap = new HashMap<String,Object>();

   public final static int MIN_PROPERTY = 1;

   public static int numClassesCached = 0;
   public static int numFieldsCached = 0;
   public static int numMethodsCached = 0;
   public static int numInterfacePropsCached = 0;
   public static int numPropsInherited = 0;

   public static IBeanMapper getPropertyMapping(Class beanClass, String propName) {
      DynType cache = TypeUtil.getPropertyCache(beanClass);
      if (cache == null) {
         System.err.println("**** Can't find DynType for: " + beanClass + " hc=" + beanClass.hashCode());
         new Throwable().printStackTrace();
         return null;
      }
      IBeanMapper res = (IBeanMapper) cache.getPropertyMapper(propName);
      if (res == null) {
         System.err.println("**** No property named: " + propName + " registered in type: " + cache);
         new Throwable().printStackTrace();
      }
      return res;
   }

   /** Abstraction around thread local cause GWT does not implement it */
   public static Object setThreadLocal(String key, Object value) {
      return threadLocalMap.put(key, value);
   }

   public static void clearThreadLocal(String key) {
      threadLocalMap.remove(key);
   }

   public static Object getThreadLocal(String key) {
      return threadLocalMap.get(key);
   }

   public static Map getWeakHashMap() {
      return new HashMap();
   }

   public static Map getWeakIdentityHashMap() {
      return new IdentityHashMap();
   }

   public static int getModifiers(Object def) {
      if (def instanceof CompMethodMapper) {
         return ((CompMethodMapper) def).isStatic ? STATIC | PUBLIC : PUBLIC;
      }
      if (def instanceof IBeanMapper) {
         if (((IBeanMapper) def).getStaticPropertyPosition() != -1)
            return STATIC | PUBLIC;
         else
            return PUBLIC;
      }
      else if (def instanceof Class)
         return PUBLIC;
      throw new UnsupportedOperationException();
   }

    /**
     * The <code>int</code> value representing the <code>public</code> 
     * modifier.
     */    
    public static final int PUBLIC           = 0x00000001;

    /**
     * The <code>int</code> value representing the <code>private</code> 
     * modifier.
     */    
    public static final int PRIVATE          = 0x00000002;

    /**
     * The <code>int</code> value representing the <code>protected</code> 
     * modifier.
     */    
    public static final int PROTECTED        = 0x00000004;

    /**
     * The <code>int</code> value representing the <code>static</code> 
     * modifier.
     */    
    public static final int STATIC           = 0x00000008;

    /**
     * The <code>int</code> value representing the <code>final</code> 
     * modifier.
     */    
    public static final int FINAL            = 0x00000010;

    /**
     * The <code>int</code> value representing the <code>synchronized</code> 
     * modifier.
     */    
    public static final int SYNCHRONIZED     = 0x00000020;

    /**
     * The <code>int</code> value representing the <code>volatile</code> 
     * modifier.
     */    
    public static final int VOLATILE         = 0x00000040;

    /**
     * The <code>int</code> value representing the <code>transient</code> 
     * modifier.
     */    
    public static final int TRANSIENT        = 0x00000080;

    /**
     * The <code>int</code> value representing the <code>native</code> 
     * modifier.
     */    
    public static final int NATIVE           = 0x00000100;

    /**
     * The <code>int</code> value representing the <code>interface</code> 
     * modifier.
     */    
    public static final int INTERFACE        = 0x00000200;

    /**
     * The <code>int</code> value representing the <code>abstract</code> 
     * modifier.
     */    
    public static final int ABSTRACT         = 0x00000400;

    /**
     * The <code>int</code> value representing the <code>strict</code> 
     * modifier.
     */    
    public static final int STRICT           = 0x00000800;

   public static boolean hasModifier(Object def, String s) {
      int modifiers = getModifiers(def);
      switch (s.charAt(0)) {
         case 'p':
            switch (s.charAt(2)) {
               case 'b':
                  return (PUBLIC & modifiers) != 0;
               case 'o':
                  return (PROTECTED & modifiers) != 0;
               case 'i':
                  return (PRIVATE & modifiers) != 0;
               default:
                  throw new UnsupportedOperationException();
            }
         case 'a':
            return (ABSTRACT & modifiers) != 0;
         case 's':
            switch (s.charAt(2)) {
               case 'a':
                  return (STATIC & modifiers) != 0;
               case 'n':
                  return (SYNCHRONIZED & modifiers) != 0;
               case 'r':
                  return (STRICT & modifiers) != 0;
               default:
                  throw new UnsupportedOperationException();
            }
         case 'f':
            return (FINAL & modifiers) != 0;
         case 't':
            return (TRANSIENT & modifiers) != 0;
         case 'v':
            return (VOLATILE & modifiers) != 0;
         case 'n':
            return (NATIVE & modifiers) != 0;
         case 'i':
            return (INTERFACE & modifiers) != 0;
      }
      throw new UnsupportedOperationException();
   }

   public static void setProperty(Object parent, Object selector, Object value) {
      if (selector instanceof IBeanMapper) {
         ((IBeanMapper) selector).setPropertyValue(parent, value);
      }
      else if (parent instanceof IDynObject) {
         IDynObject dparent = (IDynObject) parent;

         if (selector instanceof String) 
            dparent.setProperty((String) selector, value, false);
         else
            throw new UnsupportedOperationException();
      }
   }

   public static Object getProperty(Object parent, Object mapping) {
      if (mapping instanceof IBeanMapper)
         return ((IBeanMapper) mapping).getPropertyValue(parent);
      else if (parent instanceof IDynObject) {
         if (mapping instanceof String)
            return ((IDynObject) parent).getProperty((String) mapping);
      }
      throw new UnsupportedOperationException();
   }

   public static IBeanMapper getPropertyMappingConverter(Class beanClass, String propName, IBeanMapper mapper, Class valueClass, Class componentClass) {
      // TODO - is this used by runtime components?  Think it is just used for the parselet engine
      return mapper;
   }

   public static Object invokeMethod(Object thisObject, Object method, Object... params) {
      if (method instanceof CompMethodMapper) {
         return ((CompMethodMapper) method).invoke(thisObject, params);
      }
      throw new IllegalArgumentException("Unrecognized type of method to TypeUtil.invokeMethod: " + method);
   }

   public static Object resolveMethod(Class resultClass, String methodName, String paramSig) {
      DynType type = DynType.getDynType(resultClass);
      return type.getMethod(methodName, paramSig);
   }

   public static int getArrayLength(Object arrVal) {
      if (arrVal instanceof Collection)
         return ((Collection) arrVal).size();
      if (arrVal instanceof Object[]) {
         return ((Object[]) arrVal).length;
      }
      if (arrVal instanceof int[]) {
         return ((int[]) arrVal).length;
      }
      if (arrVal instanceof String[]) {
         return ((String[]) arrVal).length;
      }
      throw new UnsupportedOperationException();
      // TODO: more types
   }

   public static Object getArrayElement(Object arrVal, int dim) {
      if (arrVal instanceof List)
         return ((List) arrVal).get(dim);
      if (arrVal instanceof Object[]) {
         return ((Object[]) arrVal)[dim];
      }
      if (arrVal instanceof int[]) {
         return ((int[]) arrVal)[dim];
      }
      throw new UnsupportedOperationException();
      // TODO: more types
   }

   public static void setArrayElement(Object arrVal, int dim, Object value) {
      if (arrVal instanceof List)
         ((List) arrVal).set(dim, value);
      else if (arrVal instanceof Object[]) {
         ((Object[]) arrVal)[dim] = value;
      }
      else if (arrVal instanceof int[]) {
         ((int[]) arrVal)[dim] = (Integer) value;
      }
      throw new UnsupportedOperationException();
      // TODO: more types
   }

   public static String getPropertyName(Object mapper) {
      if (mapper instanceof IBeanMapper)
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

      char c;
      if (!Character.isLetter(c = toStr.charAt(0)) || c == '_')
         return false;

      int i = 0;
      while (i < len - 1 && (Character.isLetterOrDigit(c = toStr.charAt(++i)) || c == '_'))
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

   /** Creates a newInstance of the class */
   public static Object createInstance(Class resultClass, String constrSig, Object... params) {
      DynType type = DynType.getDynType(resultClass);
      if (type == null) {
         System.err.println("*** Can't find DynType for class: " + resultClass);
         return null;
      }
      return type.createInstance(constrSig, params);
   }

   public static boolean isPrimitive(Class type) {
      // TODO: anyway to identify primitive types in GWT?  Used by DynUtil.getInstanceName
      return false;
   }

   public static Object getDefaultObjectValue(Class type) {
      return Type.get(type).getDefaultObjectValue();
   }

   public static Object getReturnType(Object method) {
      throw new UnsupportedOperationException();
   }

   public static Object[] getParameterTypes(Object dynMethod) {
      return null; // MethodBinding right now does the int -> integer conversion but only if this returns non-null
   }

   public static String getMethodName(Object method) {
      if (method instanceof CompMethodMapper)
         return ((CompMethodMapper) method).methodName;
      throw new UnsupportedOperationException();
   }

   public static IReverseMethodMapper getReverseMethodMapper(MethodBinding binding) {
      return new CompReverseMethodMapper(binding);
   }

   /** 
    * For the compiled type system we should not need the more flexible comparison used to manage inheritance of properties.  
    * We are not inheriting CompBeanMappers the way we do in the runtime system using reflection.
    */
   public static boolean compareBeanMappers(IBeanMapper thisP, IBeanMapper otherP) {
      CompBeanMapper thisM, otherM;
      return thisP == otherP ||
             (thisP instanceof CompBeanMapper && otherP instanceof CompBeanMapper &&
             (thisM = (CompBeanMapper) thisP).getPropertyName().equals((otherM = (CompBeanMapper) otherP).getPropertyName()) &&
             thisM.type.isAssignableFrom(otherM.type));
   }

   static DynType initPropertyCache(Class cl) {
      // These should get loaded as part of the classes static init
      return DynType.getDynType(cl);
   }

   public static boolean evalInstanceOfExpression(Object lhsVal, Class theClass) {
      throw new UnsupportedOperationException();
   }

   public static IBeanMapper getArrayLengthBeanMapper() {
      // TODO: need to create a dynamic bean mapper which uses getArrayLength to pull off the length at runtime from an object
      // for binding against array.length at runtime.
      throw new UnsupportedOperationException();
   }

   // Reflectively create an array.  There's no reflection API to do that in a typed fashion in GWT so hoping no one is checking runtime errors for the "core runtime"
   public static Object newArray(Class cl, int size) {
      return new Object[size];
   }

   public static boolean isArray(Object cl) {
      if (cl instanceof Class)
         return ((Class) cl).isArray();
      return false;
   }

   public static Object getComponentType(Object cl) {
      return cl instanceof Class ? ((Class) cl).getComponentType() : null;
   }

   public static boolean isAssignableFrom(Class cl, Class other) {
      if (cl == other || cl.getName().equals(other.getName()))
         return true;
      Class supCl = cl.getSuperclass();
      if (supCl != null)
         return isAssignableFrom(supCl, other);
      return false;
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

   // Implemented in Javascript
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

   public static boolean isObjectType(Object obj) {
      throw new UnsupportedOperationException();
   }

   public static Object getInheritedAnnotationValue(Class cl, String annotName, String annotValue) {
      return null;
   }

   public static Object getAnnotationValue(Class cl, String annotName, String annotValue) {
      return null;
   }

   public static Object getAnnotation(Class cl, String annotName) {
      return null;
   }

   public static Object getEnclosingType(Object typeObj, boolean instOnly) {
      return null;
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

   public static Object clone(Object o) {
      Object meth = resolveMethod(o.getClass(), "clone", null);
      return invokeMethod(o, meth);
   }
}
