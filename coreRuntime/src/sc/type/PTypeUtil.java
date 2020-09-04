/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.type;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;

import sc.dyn.IDynObject;
import sc.dyn.CompReverseMethodMapper;
import sc.bind.MethodBinding;
import sc.dyn.IReverseMethodMapper;
import sc.js.JSSettings;
import sc.obj.ISystemExitListener;

/** A version of the PTypeUtil class with minimum dependencies on core Java features.  Originally, this class was built for
 * the GWT integration to separate out methods which did not exist in that runtime.  It now is primarily used to separate
 * out a java-script only implementation of various type utilities.  This class is replaced in the JS runtime by scdyn.js.
 */
@JSSettings(jsLibFiles="js/scdyn.js")
public class PTypeUtil {
   public static Map<String,Object> threadLocalMap = new HashMap<String,Object>();

   public final static int MIN_PROPERTY = 1;

   // Flags which will mirror the system values on both client and server
   public static boolean verbose, testMode, testVerifyMode;

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

   /** Abstraction around thread local because GWT and JS do not support thread local */
   public static Object setThreadLocal(String key, Object value) {
      return threadLocalMap.put(key, value);
   }

   public static void clearThreadLocal(String key) {
      threadLocalMap.remove(key);
   }

   public static Object getThreadLocal(String key) {
      return threadLocalMap.get(key);
   }

   public static String getThreadName() {
      return "<no-thread>";
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

   public static Object getProperty(Object parent, Object mapping, boolean getField) {
      if (mapping instanceof IBeanMapper)
         return ((IBeanMapper) mapping).getPropertyValue(parent, getField, false);
      else if (parent instanceof IDynObject) {
         if (mapping instanceof String)
            return ((IDynObject) parent).getProperty((String) mapping, getField);
      }
      throw new UnsupportedOperationException();
   }

   public static IBeanMapper getPropertyMappingConverter(IBeanMapper mapper, Class valueClass, Class componentClass) {
      // TODO - is this used by runtime components?  Think it is just used for the parselet engine
      return mapper;
   }

   public static Object invokeMethod(Object thisObject, Object method, Object... params) {
      if (method instanceof CompMethodMapper) {
         return ((CompMethodMapper) method).invoke(thisObject, params);
      }
      throw new IllegalArgumentException("Unrecognized type of method to TypeUtil.invokeMethod: " + method);
   }

   public static Object resolveMethod(Class resultClass, String methodName, Object returnType, String paramSig) {
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
      if (arrVal instanceof double[]) {
         return ((double[]) arrVal).length;
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
      if (arrVal instanceof double[]) {
         return ((double[]) arrVal)[dim];
      }
      if (arrVal instanceof float[]) {
         return ((float[]) arrVal)[dim];
      }
      if (arrVal instanceof char[]) {
         return ((char[]) arrVal)[dim];
      }
      if (arrVal instanceof byte[]) {
         return ((byte[]) arrVal)[dim];
      }
      if (arrVal instanceof boolean[]) {
         return ((boolean[]) arrVal)[dim];
      }
      throw new UnsupportedOperationException();
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
      else if (arrVal instanceof double[]) {
         ((double[]) arrVal)[dim] = (Double) value;
      }
      else if (arrVal instanceof float[]) {
         ((float[]) arrVal)[dim] = (Float) value;
      }
      else if (arrVal instanceof char[]) {
         ((char[]) arrVal)[dim] = (Character) value;
      }
      else if (arrVal instanceof byte[]) {
         ((byte[]) arrVal)[dim] = (Byte) value;
      }
      else if (arrVal instanceof boolean[]) {
         ((boolean[]) arrVal)[dim] = (Boolean) value;
      }
      throw new UnsupportedOperationException();
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
      return Type.get(type).primitiveClass != null;
   }

   public static boolean isANumber(Class type) {
      return Type.get(type).isANumber();
   }

   public static boolean isStringOrChar(Class type) {
      Type t = Type.get(type);
      return t == Type.String || t == Type.Character;
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

   public static Object getMethodType(Object method) {
      if (method instanceof CompMethodMapper)
         return ((CompMethodMapper) method).type;
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
         return null;
      }
   }

   public static Object addScheduledJob(final Runnable toRun, long delay, boolean repeat) {
      TimerTask task = new TimerTask() {
          public void run() {
             toRun.run();
          }
      };
      Timer timer = new Timer("Timer: " + toRun.toString());
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

   public static Object getValueFromAnnotation(Object annotation, String annotValue) {
      return null;
   }

   public static Object getAnnotationValue(Class cl, String annotName, String annotValue) {
      return null;
   }

   public static Object getAnnotation(Object cl, Object annotNameOrType) {
      return null;
   }

   public static Object getEnclosingType(Object typeObj, boolean instOnly) {
      return null;
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
   }

   public static void releaseLocks(List<Object> locks, String traceInfo) {
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

   // Here we print the time since the PageDispatcher started since that's perhaps the easiest basic way to follow "elapsed time" in the context of a server process.
   // I could imagine having an option to show this relative to start-session or start-thread time or even displaying multiple time spaces in the same log to diagnose different scenarios
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
}
