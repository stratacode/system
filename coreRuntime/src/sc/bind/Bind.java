/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import sc.dyn.DynUtil;
import sc.js.JSSettings;
import sc.obj.CompilerSettings;
import sc.obj.CurrentScopeContext;
import sc.obj.ScopeContext;
import sc.obj.ScopeDefinition;
import sc.sync.SyncManager;
import sc.type.IBeanMapper;
import sc.type.PTypeUtil;
import sc.type.TypeUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * This is the main entry point for 'data binding'. It contains static methods which you
 * can use to create bindings on objects and properties from the API.  These static methods are used
 * in the generated code to implement all bindings so it's easy to find examples by looking at the generated source.
 * To create a simple binding use the
 * Bind.bind() method.  You provide the destination object (i.e. the lhs), the name or property mapper for the destination property,
 * the srcObject (if any) used in mapping the binding, and the list of properties in the chain (i.e. "a" and "b" in an a.b binding).
 * Each binding can either be specified as the String name of the property or you can find the IBeanMapper and pass that in.
 * When you use the IBeanMapper, lookups will be faster since an integer index is used rather than the String to do a hashtable lookup.
 * You also specify the BindingDirection - forward, reverse or bidirectional.
 * <p>
 * The other types of bindings - method, arithmetic, cast, unary, condition, ternary, arrayElement, new and newArray.  There's also a constant
 * binding you can use when you need to substitute a constant in an expression.  Finally there's an assignment binding which is used
 * most commonly in reverse-only bindings to perform an assignment when the binding is fired.
 * </p>
 * <p>
 * SC developers note: When you add new code to the core runtime (i.e. used by GWT or other non-reflective runtimes), you must add the package name to the jarPackages.
 * You also must change the Bind's CompilerSettings in the full runtime and the StrataCode.gwt.xml file in the
 * core src root folder.
 * </p>
 */
@CompilerSettings(jarFileName="bin/scrt-core.jar", srcJarFileName="bin/scrt-core-src.jar",
                  jarPackages={"sc.type", "sc.js", "sc.bind", "sc.obj", "sc.dyn", "sc.util", "sc", "sc.sync"})
@JSSettings(jsLibFiles = "js/sccore.js,js/scdyn.js,js/scbind.js", prefixAlias="sc_")
public class Bind {
   /*
   private final static Class[] PROP_LISTENER_ARGS = {String.class, java.beans.PropertyChangeListener.class};
   private final static Class[] COMMON_LISTENER_ARGS = {java.beans.PropertyChangeListener.class};
   */

   /** These are option flags you can combine in the flags argument to various calls to create bindings.  Settable via @Bindable using code-generation */
   public static int INACTIVE = 1, TRACE = 2, VERBOSE = 4, QUEUED = 8, IMMEDIATE = 16, CROSS_SCOPE = 32, DO_LATER = 64, HISTORY = 128, ORIGIN = 256, SKIP_NULL = 512;

   /** The OR'd list of flags to be propagated to all child bindings */
   static int PROPAGATED_FLAGS = SKIP_NULL;

   public static void removeListenerOfType(Object inst, IBeanMapper prop, Object listenerType, int valueChangedMask) {
      BindingListener listenerPtr = getPropListeners(inst, prop);
      while (listenerPtr != null) {
         IListener toRemListener = listenerPtr.listener;
         Object curListenerType = DynUtil.getType(toRemListener);
         if (curListenerType == listenerType) {
            removeListener(inst, prop, toRemListener, valueChangedMask);
            return;
         }
         listenerPtr = listenerPtr.next;
      }
      System.err.println("*** Failed to remove listener of type: " + listenerType + " for property: " + prop.getPropertyName() + " on object: " + inst);
   }

   static class BindingListenerEntry {
      int numProps = 0;
      BindingListener[] bindingListeners;

      public String toString() {
         return arrayToString(bindingListeners);
      }
   }

   /**
    * For objects which do not implement IBindingContainer, stores the list of bindings set on that instance for each property in a list.  This includes forward, bi-directional,
    * and reverse bindings.   We do not order the list, so in order to see which property a given binding is set on, you look at the DestinationListener's destination property.
    */
   public final static Map<Object,List<DestinationListener>> bindingContainerRegistry = PTypeUtil.getWeakIdentityHashMap();

   /** Stores a linked list of binding listeners for a given object.  The BindingListenerEntry has the array of listeners which is indexed by the property position */
   public final static Map<Object,BindingListenerEntry> bindingListenerRegistry = PTypeUtil.getWeakIdentityHashMap();

   public static boolean trace = false;
   public static boolean traceAll = false;
   public static boolean info = true;

   public static BindingManager bindingManager = new BindingManager();

   /**
    * Add a simple binding onto dstProp in dstObj.  Returns the initial value of the binding.  Takes an optional srcObj
    * to refer to a constant object which starts the "a.b" chain of expressions.  Then it takes a list of boundProps -
    * either String names of properties in the chain or IBeanMapper instances or nested bindings using the IBinding implementation (created with
    * the bindP, methodP, etc. method calls).  The final parameter is the direction which
    * can be forward, reverse or bi-directional.
    */
   public static Object bind(Object dstObj, String dstProp, Object srcObj, Object[] boundProps, BindingDirection dir, int flags, BindOptions opts) {
      return bind(dstObj, TypeUtil.resolveObjectPropertyMapping(dstObj, dstProp), srcObj, boundProps, dir, flags, opts);
   }

   public static Object bind(Object dstObj, IBinding dstProp, Object srcObj, Object[] boundProps, BindingDirection dir, int flags, BindOptions opts) {
      VariableBinding binding = new VariableBinding(dstObj, dstProp, srcObj, boundProps, dir, flags, opts);
      return bindInternal(dstObj, binding);
   }

   public static int bindInt(Object dstObj, IBinding dstProp, Object srcObj, Object[] boundProps, BindingDirection dir, int flags, BindOptions opts) {
      VariableBinding binding = new VariableBinding(dstObj, dstProp, srcObj, boundProps, dir, flags, opts);
      Object val = bindInternal(dstObj, binding);
      if (val == null)
         return 0;
      return ((Number) val).intValue();
   }

   /** Implements a top-level method binding */
   public static Object method(Object dstObj, String dstProp, Object method, IBinding[] boundArgs, BindingDirection dir, int flags, BindOptions opts) {
      return method(dstObj, TypeUtil.resolveObjectPropertyMapping(dstObj, dstProp), method, boundArgs, dir, flags, opts);
   }

   public static Object method(Object dstObj, IBinding dstProp, Object method, IBinding[] args, BindingDirection dir, int flags, BindOptions opts) {
      MethodBinding binding = new MethodBinding(dstObj, dstProp, dstObj, method, args, dir, flags, opts);
      return bindInternal(dstObj, binding);
   }

   /** This variant is used when the method specified is not on the destination object. */
   public static Object method(Object dstObj, String dstProp, Object methObj, Object method, IBinding[] boundArgs, BindingDirection dir, int flags, BindOptions opts) {
      return method(dstObj, TypeUtil.resolveObjectPropertyMapping(dstObj, dstProp), methObj, method, boundArgs, dir, flags, opts);
   }

   public static Object method(Object dstObj, IBinding dstProp, Object methObj, Object method, IBinding[] args, BindingDirection dir, int flags, BindOptions opts) {
      MethodBinding binding = new MethodBinding(dstObj, dstProp, methObj, method, args, dir, flags, opts);
      return bindInternal(dstObj, binding);
   }

   public static Object arith(Object dstObj, String dstProp, String operator, IBinding[] boundArgs, BindingDirection dir, int flags, BindOptions opts) {
      return arith(dstObj, TypeUtil.resolveObjectPropertyMapping(dstObj, dstProp), operator, boundArgs, dir, flags, opts);
   }


   public static Object arith(Object dstObj, IBinding dstProp, String operator, IBinding[] args, BindingDirection dir, int flags, BindOptions opts) {
      ArithmeticBinding binding = new ArithmeticBinding(dstObj, dstProp, operator, args, dir, flags, opts);
      return bindInternal(dstObj, binding);
   }

   public static Object condition(Object dstObj, String dstProp, String operator, IBinding[] boundArgs, BindingDirection dir, int flags, BindOptions opts) {
      return condition(dstObj, TypeUtil.resolveObjectPropertyMapping(dstObj, dstProp), operator, boundArgs, dir, flags, opts);
   }

   public static Object condition(Object dstObj, IBinding dstProp, String operator, IBinding[] args, BindingDirection dir, int flags, BindOptions opts) {
      ConditionalBinding binding = new ConditionalBinding(dstObj, dstProp, operator, args, dir, flags, opts);
      return bindInternal(dstObj, binding);
   }

   public static Object unary(Object dstObj, String dstProp, String operator, IBinding[] boundArgs, BindingDirection dir, int flags, BindOptions opts) {
      return unary(dstObj, TypeUtil.resolveObjectPropertyMapping(dstObj, dstProp), operator, boundArgs, dir, flags, opts);
   }

   public static Object unary(Object dstObj, IBinding dstProp, String operator, IBinding[] args, BindingDirection dir, int flags, BindOptions opts) {
      UnaryBinding binding = new UnaryBinding(dstObj, dstProp, operator, args, dir, flags, opts);
      return bindInternal(dstObj, binding);
   }

   public static Object ternary(Object dstObj, String dstProp, IBinding[] boundArgs, BindingDirection dir, int flags, BindOptions opts) {
      return ternary(dstObj, TypeUtil.resolveObjectPropertyMapping(dstObj, dstProp), boundArgs, dir, flags, opts);
   }

   public static Object ternary(Object dstObj, IBinding dstProp, IBinding[] args, BindingDirection dir, int flags, BindOptions opts) {
      TernaryBinding binding = new TernaryBinding(dstObj, dstProp, args, dir, flags, opts);
      return bindInternal(dstObj, binding);
   }

   public static Object arrayElement(Object dstObj, String dstProp, Object srcObj, Object[] boundArgs, IBinding[] arrayDims, BindingDirection dir, int flags, BindOptions opts) {
      return arrayElement(dstObj, TypeUtil.resolveObjectPropertyMapping(dstObj, dstProp), srcObj, boundArgs, arrayDims, dir, flags, opts);
   }

   public static Object arrayElement(Object dstObj, IBinding dstProp, Object srcObj, Object[] args, IBinding[] arrayDims, BindingDirection dir, int flags, BindOptions opts) {
      ArrayElementBinding binding = new ArrayElementBinding(dstObj, dstProp, srcObj, args, arrayDims, dir, flags, opts);
      return bindInternal(dstObj, binding);
   }

   public static Object cast(Object dstObj, String dstProp, Class theClass, IBinding boundArg, BindingDirection dir, int flags, BindOptions opts) {
      return cast(dstObj, TypeUtil.resolveObjectPropertyMapping(dstObj, dstProp), theClass, boundArg, dir, flags, opts);
   }

   public static Object cast(Object dstObj, IBinding dstProp, Class theClass, IBinding arg, BindingDirection dir, int flags, BindOptions opts) {
      CastBinding binding = new CastBinding(dstObj, dstProp, theClass, arg, dir, flags, opts);
      return bindInternal(dstObj, binding);
   }

   public static Object selector(Object dstObj, String dstProp, Object [] boundProps, BindingDirection dir, int flags, BindOptions opts) {
      return selector(dstObj, TypeUtil.resolveObjectPropertyMapping(dstObj, dstProp), boundProps, dir, flags, opts);
   }

   public static Object selector(Object dstObj, IBinding dstProp, Object[] boundProps, BindingDirection dir, int flags, BindOptions opts) {
      SelectorBinding binding = new SelectorBinding(dstObj, dstProp, boundProps, dir, flags, opts);
      return bindInternal(dstObj, binding);
   }

   public static Object bindNew(Object dstObj, String dstProp, Object newType, String paramSig, IBinding[] boundArgs, BindingDirection dir, int flags, BindOptions opts) {
      return bindNew(dstObj, TypeUtil.resolveObjectPropertyMapping(dstObj, dstProp), newType, paramSig, boundArgs, dir, flags, opts);
   }

   public static Object bindNew(Object dstObj, IBinding dstProp, Object newType, String paramSig, IBinding[] args, BindingDirection dir, int flags, BindOptions opts) {
      NewBinding binding = new NewBinding(dstObj, dstProp, newType, paramSig, args, dir, flags, opts);
      return bindInternal(dstObj, binding);
   }

   public static Object newArray(Object dstObj, String dstProp, Object compType, IBinding[] boundArgs, BindingDirection dir, int flags, BindOptions opts) {
      return newArray(dstObj, TypeUtil.resolveObjectPropertyMapping(dstObj, dstProp), compType, boundArgs, dir, flags, opts);
   }

   public static Object newArray(Object dstObj, IBinding dstProp, Object compType, IBinding[] args, BindingDirection dir, int flags, BindOptions opts) {
      NewArrayBinding binding = new NewArrayBinding(dstObj, dstProp, compType, args, dir, flags, opts);
      return bindInternal(dstObj, binding);
   }

   public static Object assign(Object dstObj, String dstProp, Object srcObj, IBinding lhsBinding, Object rhs, BindingDirection dir, int flags, BindOptions opts) {
      return assign(dstObj, TypeUtil.resolveObjectPropertyMapping(dstObj, dstProp), srcObj, lhsBinding, rhs, dir, flags, opts);
   }

   public static Object assign(Object dstObj, IBinding dstProp, Object srcObj, IBinding lhsBinding, Object rhs, BindingDirection dir, int flags, BindOptions opts) {
      AssignmentBinding binding = rhs instanceof IBinding ? new AssignmentBinding(dstObj, dstProp, srcObj, (VariableBinding) lhsBinding, (IBinding) rhs, dir, flags, opts) :
                                                            new AssignmentBinding(dstObj, dstProp, srcObj, (VariableBinding) lhsBinding, rhs, dir, flags, opts);
      return bindInternal(dstObj, binding);
   }

   public static Object constant(Object dstObj, String dstProp, Object value, BindingDirection dir, int flags, BindOptions opts) {
      return constant(dstObj, TypeUtil.resolveObjectPropertyMapping(dstObj, dstProp), value, dir, flags, opts);
   }

   public static Object constant(Object dstObj, IBinding dstProp, Object value, BindingDirection dir, int flags, BindOptions opts) {
      if (dir.doForward())
         removePropertyBindings(dstObj, dstProp, true, false);  // Only remove the existing forward binding
      return value;
   }

   /**
    * This implements overriding of bindings.  When you add a binding for a specific property, it removes
    * any binding already defined for that property. 
    */
   private static DestinationListener bindToList(List<DestinationListener> bindings, DestinationListener binding, Object dstObj) {
      int sz = bindings.size();
      int i;

      DestinationListener replacedBinding = null;

      // Only bindings with := cause the automatic overriding. =: bindings must be removed explicitly using the api
      if (binding.direction.doForward()) {
         for (i = 0; i < sz; i++) {
            DestinationListener oldBinding = bindings.get(i);
            if (PBindUtil.equalProps(oldBinding.dstProp, binding.dstProp) && oldBinding.direction.doForward()) {
               replacedBinding = oldBinding;
               oldBinding.removeListener();
               bindings.set(i, binding);
               break;
            }
         }
         if (i == sz)
            bindings.add(binding);
      }
      else
         bindings.add(binding);
      return replacedBinding;
   }


   /** Removes the bindings on the object specified for the property specified.  You may remove just the forward, just the reverse or all types of bindings on this property */
   public static int removePropertyBindings(Object dstObj, String propName, boolean removeForward, boolean removeReverse) {
      return removePropertyBindings(dstObj, TypeUtil.resolveObjectPropertyMapping(dstObj, propName), removeForward, removeReverse);
   }

   public static int removePropertyBindings(Object dstObj, IBinding dstProp, boolean removeForward, boolean removeReverse) {
      List<DestinationListener> bindings;
      if (dstObj instanceof IBindingContainer) {
         IBindingContainer bc = (IBindingContainer) dstObj;
         bindings = bc.getBindings();
         if (bindings == null) {
            return 0;
         }
      }
      else {
         synchronized (bindingContainerRegistry) {
            bindings = bindingContainerRegistry.get(dstObj);
            if (bindings == null) {
               return 0;
            }
         }
      }
      int sz = bindings.size();
      int ct = 0;
      for (int i = 0; i < sz; i++) {
         DestinationListener oldBinding = bindings.get(i);
         if (PBindUtil.equalProps(oldBinding.dstProp, dstProp) && ((removeForward && oldBinding.direction.doForward()) || (removeReverse && oldBinding.direction.doReverse()))) {
            oldBinding.removeListener();
            bindings.remove(i);
            ct++;
            i--;
            sz--;
         }
      }
      return ct;
   }

   private static Object bindInternal(Object dstObj, DestinationListener binding) {
      DestinationListener oldBinding;
      if (dstObj instanceof IBindingContainer) {
         IBindingContainer bc = (IBindingContainer) dstObj;
         List<DestinationListener> bindings = bc.getBindings();
         if (bindings == null) {
            bindings = new ArrayList<DestinationListener>();
            bc.setBindings(bindings);
         }
         oldBinding = bindToList(bindings, binding, dstObj);
      }
      else {
         synchronized (bindingContainerRegistry) {
            List<DestinationListener> bindings = bindingContainerRegistry.get(dstObj);
            if (bindings == null) {
               bindings = new ArrayList<DestinationListener>();
               bindingContainerRegistry.put(dstObj, bindings);
            }
            oldBinding = bindToList(bindings, binding, dstObj);
         }
      }
      Object res = binding.initializeBinding();
      if (oldBinding != null && (trace || (oldBinding.flags & TRACE) != 0)) {
         if (logBindingMessage("replaced", oldBinding, dstObj, null, binding))
            endPropMessage();
      }
      return res;
   }

   public static IBinding bindP(Object srcObj, Object[] boundProps) {
      if (boundProps.length == 0)
         return new ConstantBinding(srcObj);
      return new VariableBinding(srcObj, boundProps);
   }

   public static IBinding methodP(Object method, IBinding[] boundArgs) {
      return new MethodBinding(method, boundArgs);
   }

   public static IBinding methodP(Object methObj, Object method, IBinding[] boundArgs) {
      return new MethodBinding(methObj, method, boundArgs);
   }

   public static IBinding arithP(String operator, IBinding[] boundArgs) {
      return new ArithmeticBinding(operator, boundArgs);
   }

   public static IBinding conditionP(String operator, IBinding[] boundArgs) {
      return new ConditionalBinding(operator, boundArgs);
   }

   public static IBinding unaryP(String operator, IBinding[] boundArgs) {
      return new UnaryBinding(operator, boundArgs);
   }

   public static IBinding ternaryP(IBinding[] boundArgs) {
      return new TernaryBinding(boundArgs);
   }

   public static IBinding arrayElementP(Object srcObj, Object[] boundArgs, IBinding[] arrayDims) {
      return new ArrayElementBinding(srcObj, boundArgs, arrayDims);
   }

   public static IBinding castP(Class theClass, IBinding boundArg) {
      return new CastBinding(theClass, boundArg);
   }

   public static IBinding selectorP(Object[] boundProps) {
      return new SelectorBinding(boundProps);
   }

   public static IBinding constantP(Object value) {
      return new ConstantBinding(value);
   }

   public static IBinding bindNewP(Object newClass, String paramSig, IBinding[] boundProps) {
      return new NewBinding(newClass, paramSig, boundProps);
   }

   public static IBinding newArrayP(Object newClass, IBinding[] boundProps) {
      return new NewArrayBinding(newClass, boundProps);
   }

   public static IBinding assignP(Object srcObj, IBinding lhsBinding, Object rhsValue) {
      return new AssignmentBinding(srcObj, (VariableBinding) lhsBinding, rhsValue);
   }

   public static void removeBindings(Object dstObj) {
      removeBindings(dstObj, true);
   }

   public static void removeBindings(Object dstObj, boolean removeChildren) {
      List<DestinationListener> bindings;

      if (dstObj instanceof IBindingContainer) {
         IBindingContainer bc = (IBindingContainer) dstObj;
         bindings = bc.getBindings();
         bc.setBindings(null);
      }
      else {
         synchronized (bindingContainerRegistry) {
            bindings = bindingContainerRegistry.remove(dstObj);
         }
      }
      if (bindings == null)
         return;

      if (trace)
         System.out.println("Removing: " + bindings.size() + " bindings for: " + DynUtil.getInstanceName(dstObj));

      for (int i = 0; i < bindings.size(); i++)
         bindings.get(i).removeListener();

      // We'll also remove all of the bindings on any child objects so this method becomes a simple way to dispose of
      // a declarative tree.
      if (removeChildren) {
         Object[] children = DynUtil.getObjChildren(dstObj, null, false);
         if (children != null) {
            for (Object child:children) {
               if (child != null)
                  removeBindings(child);
            }
         }
      }
   }

   public static int refreshBindings(Object dstObj) {
      boolean endLogIndent = false;
      int ct = 0;
      long startTime = 0;
      if (trace) {
         endLogIndent = logPropMessage("Refresh", dstObj, null, null);
         startTime = System.currentTimeMillis();
      }
      try {
         ct = refreshBindings(dstObj, true);
      }
      finally {
         if (endLogIndent) {
            System.out.println(indent(getIndentLevel()) + "refreshed: " + ct + " in: " + (System.currentTimeMillis() - startTime) + " millis");
            endPropMessage();
         }
      }
      return ct;
   }

   public static int refreshBindings(Object dstObj, boolean refreshChildren) {
      List<DestinationListener> bindings;
      int ct = 0;
      if (dstObj instanceof IBindingContainer) {
         IBindingContainer bc = (IBindingContainer) dstObj;
         bindings = bc.getBindings();
      }
      else {
         synchronized (bindingContainerRegistry) {
            bindings = bindingContainerRegistry.get(dstObj);
         }
      }
      if (bindings != null) {
         for (int i = 0; i < bindings.size(); i++)
            ct += bindings.get(i).refreshBinding();
      }

      // We'll also remove all of the bindings on any child objects so this method becomes a simple way to dispose of
      // a declarative tree.
      if (refreshChildren) {
         Object[] children = DynUtil.getObjChildren(dstObj, null, false);
         if (children != null) {
            for (Object child:children) {
               if (child != null)
                  ct += refreshBindings(child, true);
            }
         }
      }
      return ct;
   }

   public static void accessBindings(Object dstObj, boolean doChildren) {
      List<DestinationListener> bindings;
      int ct = 0;
      if (dstObj instanceof IBindingContainer) {
         IBindingContainer bc = (IBindingContainer) dstObj;
         bindings = bc.getBindings();
      }
      else {
         synchronized (bindingContainerRegistry) {
            bindings = bindingContainerRegistry.get(dstObj);
         }
      }
      if (bindings != null) {
         for (int i = 0; i < bindings.size(); i++)
            bindings.get(i).accessBinding();
      }
      // If necessary, register each object in the sync system. Although we also access any objects reachable by
      // bindings, not guaranteed to reach all children that way.
      String scopeName = DynUtil.getScopeName(dstObj);
      if (scopeName != null)
         SyncManager.accessSyncInst(dstObj, scopeName);

      // We'll also remove all of the bindings on any child objects so this method becomes a simple way to dispose of
      // a declarative tree.
      if (doChildren) {
         Object[] children = DynUtil.getObjChildren(dstObj, null, false);
         if (children != null) {
            for (Object child:children) {
               if (child != null)
                  accessBindings(child, true);
            }
         }
      }
   }

   /**
    * Refreshes the bindings attached to a single named property of the supplied destination object.  You can use this to
    * force a specific binding to be re-validated.  If the bound value has changed, the destination object's property is
    * updated to the new value.
    */
   public static int refreshBinding(Object dstObj, String propName) {
      DestinationListener[] bindings = getBindings(dstObj);
      if (bindings == null)
         return 0;
      int sz = bindings.length;
      int ct = 0;
      for (int i = 0; i < sz; i++) {
         DestinationListener binding = bindings[i];
         if (PBindUtil.equalProps(binding.dstProp, propName)) {
            ct += binding.refreshBinding();
         }
      }
      return ct;
   }

   /**
    * Like addDynamicListener but takes an explicit type to use for this object.  In the case where you might select and
    * retrieve the static properties for a subtype and want to add a listener for those values, you pass in the subtype.
    */
   public static void addDynamicListener(Object obj, Object typeObj, String propName, IListener listener, int eventMask) {
      IBeanMapper mapper = DynUtil.getPropertyMapping(typeObj, propName);
      if (mapper == null) {
         System.err.println("*** Failed to add dynamic listener.  Property: " + propName + " not found for obj: " + obj);
         return;
      }
      else if (mapper.getStaticPropertyPosition() != -1)
         obj = typeObj;
      addListener(obj, mapper, listener, eventMask);
   }

   public static void addDynamicListener(Object obj, String propName, IListener listener, int eventMask) {
      Object typeObj = DynUtil.getType(obj);
      addDynamicListener(obj, typeObj, propName, listener, eventMask);
   }

   public static void addListener(Object obj, Object propObj, IListener listener, int eventMask) {
      addListener(obj, propObj, listener, eventMask, 0);
   }

   public static void addListener(Object obj, Object propObj, IListener listener, int eventMask, int priority) {
      if (propObj instanceof String) {
         Object typeObj = DynUtil.isType(obj) ? obj : DynUtil.getSType(obj);
         IBeanMapper mapper = DynUtil.getPropertyMapping(typeObj, (String) propObj);
         if (mapper == null) {
            System.err.println("*** Bind.addListener failed - no property named: " + propObj + " on type: " + DynUtil.getSType(obj));
            return;
         }
         addListener(obj, mapper, listener, eventMask, 0);
      }
      else if (propObj instanceof IBeanMapper || propObj == null) {
         IBeanMapper prop = (IBeanMapper) propObj;
         BindingListener[] newListeners;
         if (obj instanceof IBindable) {
            IBindable bobj = (IBindable) obj;
            int propPos = prop == null ? 0 : prop.getPropertyPosition(obj);
            if (propPos == -1)
               throw new IllegalArgumentException("Attempt to bind to non-existent property: " + prop + " on: " + DynUtil.getInstanceName(obj));

            BindingListener [] oldListeners = bobj.getBindingListeners();
            if (oldListeners == null) {
               newListeners = new BindingListener[DynUtil.getPropertyCount(obj)];
               bobj.setBindingListeners(newListeners);
            }
            else if (oldListeners.length <= propPos) {
               newListeners = new BindingListener[DynUtil.getPropertyCount(obj)];
               System.arraycopy(oldListeners, 0, newListeners, 0, oldListeners.length);
               bobj.setBindingListeners(newListeners);
            }
            else
               newListeners = oldListeners;

            BindingListener n = new BindingListener(eventMask, listener, priority);
            BindingListener prev = null;
            BindingListener cur = newListeners[propPos];

            // Put this guy in the right place in the list.  We preserve the order in which bindings are added so that if you do foo=: a() ; foo=: b(); they will run in the right order.  Also honor priority where higher priority listeners go in front.
            while (cur != null && cur.priority >= n.priority) {
               prev = cur;
               cur = cur.next;
            }

            if (prev == null) {
               n.next = newListeners[propPos];
               newListeners[propPos] = n;
            }
            else {
               n.next = prev.next;
               prev.next = n;
            }
         }
         else {
            boolean isStatic = DynUtil.isSType(obj);

            Object cl = (isStatic && prop != null ? prop.getOwnerType() : DynUtil.getType(obj));
            int propPos = prop == null ? 0 : isStatic ? prop.getStaticPropertyPosition() : prop.getPropertyPosition(obj);

            // Need to use the class for the registry
            if (isStatic)
               obj = cl;

            if (propPos == -1) {
               if ((isStatic ? prop.getPropertyPosition(obj) : prop.getStaticPropertyPosition()) != -1)
                  throw new IllegalArgumentException("Attempt to bind to a " + (isStatic ? "static" : "non-static") +
                                                     " property which is " + (isStatic ? "not static" : "static"));
               else
                  throw new IllegalArgumentException("Attempt to bind to non-existent property: " + prop + " on: " + cl);
            }

            BindingListener n = new BindingListener(eventMask, listener, priority);

            synchronized (bindingListenerRegistry) {
               BindingListenerEntry ble = bindingListenerRegistry.get(obj);
               if (ble == null) {
                  ble = new BindingListenerEntry();
                  ble.bindingListeners = new BindingListener[isStatic ? DynUtil.getStaticPropertyCount(cl) : DynUtil.getPropertyCount(obj)];
                  bindingListenerRegistry.put(obj, ble);
               }
               BindingListener[] oldListeners = ble.bindingListeners;
               if (oldListeners == null) {
                  newListeners = new BindingListener[isStatic ? DynUtil.getStaticPropertyCount(cl) : DynUtil.getPropertyCount(obj)];
               }
               else if (oldListeners.length <= propPos) {
                  newListeners = new BindingListener[isStatic ? DynUtil.getStaticPropertyCount(cl) : DynUtil.getPropertyCount(obj)];
                  System.arraycopy(oldListeners, 0, newListeners, 0, oldListeners.length);
                  ble.bindingListeners = newListeners;
               }
               else
                  newListeners = oldListeners;

               if (propPos >= newListeners.length) {
                  System.err.println("*** addListener - invalid property index!");
                  return;
               }
               BindingListener cur = newListeners[propPos];
               // Increment the number of properties which have a binding the first time we add a binding
               if (cur == null)
                  ble.numProps++;

               BindingListener prev = null;

               // Put this guy in the right place in the list.  We preserve the order in which bindings are added so that if you do foo=: a() ; foo=: b(); they will run in the right order.  Also honor priority where higher priority listeners go in front.
               while (cur != null && cur.priority >= n.priority) {
                  prev = cur;
                  cur = cur.next;
               }

               if (prev == null) {
                  n.next = newListeners[propPos];
                  newListeners[propPos] = n;
               }
               else {
                  n.next = prev.next;
                  prev.next = n;
               }
            }
            /*
            if (prop != null && (addListener = getChangeListener(obj, true)) != null) {
               if ((eventMask & IListener.VALUE_REQUESTED) != 0)
                  throw new IllegalArgumentException("Lazy evaluation not supported against java bean sources: " + obj);

               try {
                  if (addListener.getParameterTypes().length == 1)
                     addListener.invoke(obj, listener);
                  else
                     addListener.invoke(obj, prop.getPropertyName(), listener);
               }
               catch (IllegalAccessException exc) {
                  throw new IllegalArgumentException(exc.toString());
               }
               catch (InvocationTargetException exc) {
                  System.err.println("*** Error adding event listener: " + exc.getTargetException().toString());
                  exc.getTargetException().printStackTrace();
                  throw new IllegalArgumentException("Error adding event listener: " + exc.getTargetException().toString());
               }
            }
            */
         }

         // Check if there are any listeners on this object (using the default property - propObj = null)
         // to be notified of new 'addListener' requests.
         // This way, you can wait till someone is listening for a property change on this object before you
         // set up downstream listeners.  E.g. the JS tag objects do this so they only register for DOM events which
         // are being listened too.  The Sync system uses this to lazily pull objects into scopes the first time
         // a listener is added in a given scope.
         if (((eventMask & ~IListener.LISTENER_ADDED) != 0)) {
            BindingListener objListener = newListeners[0];
            while (objListener != null) {
               if ((objListener.eventMask & IListener.LISTENER_ADDED) != 0)
                  objListener.listener.listenerAdded(obj, propObj, listener, eventMask, priority);
               objListener = objListener.next;
            }
         }
      }
      else {
         IBinding binding = (IBinding) propObj;
         binding.addBindingListener(obj, listener, eventMask);
      }
   }

   /*
   public static Method getChangeListener(Object obj, boolean add) {
      if (obj instanceof Class) return null;

      String methodName = add ? "addPropertyChangeListener" : "removePropertyChangeListener";
      
      Method addListener = TypeUtil.getMethod(obj.getClass(), methodName, PROP_LISTENER_ARGS);
      if (addListener == null) {
         addListener = TypeUtil.getMethod(obj.getClass(), methodName, COMMON_LISTENER_ARGS);
      }
      return addListener;
   }
   */
   public static void removeDynamicListener(Object obj, String propName, IListener listener, int eventMask) {
      Object typeObj = DynUtil.getType(obj);
      removeDynamicListener(obj, typeObj, propName, listener, eventMask);
   }

   public static void removeDynamicListener(Object obj, Object typeObj, String propName, IListener listener, int eventMask) {
      IBeanMapper mapper = DynUtil.getPropertyMapping(typeObj, propName);
      if (mapper == null) {
         System.err.println("*** Failed to remove dynamic listener.  Property: " + propName + " not found for obj: " + obj);
         return;
      }
      if (mapper.getStaticPropertyPosition() != -1)
         obj = typeObj;
      removeListener(obj, mapper, listener, eventMask);
   }

   public static void removeListener(Object obj, Object propObj, IListener listener, int eventMask) {
      if (propObj instanceof String) {
         removeListener(obj, DynUtil.getPropertyMapping(DynUtil.getType(obj), (String) propObj), listener, eventMask);
      }
      else if (propObj instanceof IBeanMapper || propObj == null) {
         IBeanMapper prop = (IBeanMapper) propObj;
         if (obj instanceof IBindable) {
            IBindable bobj = (IBindable) obj;
            Class cl = bobj.getClass();
            int propPos = prop == null ? 0 : prop.getPropertyPosition(obj);
            if (propPos == -1)
               throw new IllegalArgumentException("Attempt to remove listener from non-existent property: " + prop + " on type: " + cl);

            BindingListener [] oldListeners = bobj.getBindingListeners();
            if (propPos >= oldListeners.length)
               throw new IllegalArgumentException("Attempt to remove listener not added for property: " + prop + " on type: " + cl);

            removeListenerFromList(obj, null, oldListeners, propPos, listener, eventMask, prop);
         }
         else {
            boolean isStatic = DynUtil.isType(obj);
            Object cl = (isStatic && prop != null ? prop.getOwnerType() : DynUtil.getType(obj));
            int propPos = prop == null ? 0 : isStatic ? prop.getStaticPropertyPosition() : prop.getPropertyPosition(obj);
            if (propPos == -1)
               throw new IllegalArgumentException("Attempt to bind to non-existent static property: " + prop + " on type: " + cl);

            if (isStatic)
               obj = cl;

            synchronized (bindingListenerRegistry) {
               BindingListenerEntry ble = bindingListenerRegistry.get(obj);
               BindingListener [] oldListeners = ble == null ? null : ble.bindingListeners;
               if (oldListeners == null || propPos >= oldListeners.length) {
                  System.out.println("Attempt to remove listener not added for property: " + prop + " on object: " + obj);
               }
               else {
                  removeListenerFromList(obj, ble, oldListeners, propPos, listener, eventMask, prop);
               }
            }

            /*
            if (prop != null && (remListener = getChangeListener(obj, false)) != null) {
               if ((eventMask & IListener.VALUE_REQUESTED) != 0)
                  throw new IllegalArgumentException("Lazy evaluation not supported against java bean sources: " + obj);

               try {
                  if (remListener.getParameterTypes().length == 1)
                     remListener.invoke(obj, listener);
                  else
                     remListener.invoke(obj, prop.getPropertyName(), listener);
               }
               catch (IllegalAccessException exc) {
                  throw new IllegalArgumentException(exc.toString());
               }
               catch (InvocationTargetException exc) {
                  System.err.println("*** Error removing event listener: " + exc.getTargetException().toString());
                  exc.getTargetException().printStackTrace();
                  throw new IllegalArgumentException("Error removing event listener: " + exc.getTargetException().toString());
               }
            }
            */
         }
      }
      else {
         IBinding binding = (IBinding) propObj;
         binding.removeBindingListener(obj, listener, eventMask);
      }
   }

   private static void removeListenerFromList(Object cl, BindingListenerEntry ble, BindingListener[] oldListeners, int propPos, IListener listener, int eventMask, IBeanMapper prop) {
      synchronized (bindingListenerRegistry) {
         BindingListener b = oldListeners[propPos];
         BindingListener p = null;
         while (b != null && (b.listener != listener || b.eventMask != eventMask)) {
            p = b;
            b = b.next;
         }

         if (b == null)
            System.err.println("Attempt to remove listener not added property: " + prop + " on: " + DynUtil.getInstanceName(cl));
         else {
            if (p == null) {
               oldListeners[propPos] = b.next;
               if (b.next == null) {
                  if (ble != null && --ble.numProps == 0)
                     bindingListenerRegistry.remove(cl);
               }
            }
            else
               p.next = b.next;
         }
      }
   }

   public static BindingListener[] getBindingListeners(Object obj) {
      if (obj instanceof IBindable) {
         IBindable bobj = (IBindable) obj;

         return bobj.getBindingListeners();
      }
      else {
         synchronized (bindingListenerRegistry) {
            BindingListenerEntry ent = bindingListenerRegistry.get(obj);
            return ent == null ? null : ent.bindingListeners;
         }
      }
   }

   public static Object NO_VALUE_EVENT_DETAIL = new String("<noValueInEventSentinel>");

   public static void sendDynamicEvent(int event, Object obj, String propName) {
      sendEvent(event, obj, DynUtil.getPropertyMapping(DynUtil.getType(obj), propName), NO_VALUE_EVENT_DETAIL);
   }

   /** The easiest method to send a change event for a given property on a given object. If the property is null, the default event for that object is sent instead.  The property supplied must refer to an actual defined property on the object. */
   public static void sendChangedEvent(Object obj, String propName) {
      sendEvent(IListener.VALUE_CHANGED, obj, propName == null ? null : DynUtil.getPropertyMapping(DynUtil.getType(obj), propName), NO_VALUE_EVENT_DETAIL);
   }

   /** like sendChangedEvent but a shorter name for less JS code.  Also takes the value for nice logging. */
   public static void sendChange(Object obj, String propName, Object val) {
      sendEvent(IListener.VALUE_CHANGED, obj, propName == null ? null : DynUtil.getPropertyMapping(DynUtil.getType(obj), propName), val);
   }

   public static void sendInvalidate(Object obj, String propName, Object val) {
      sendEvent(IListener.VALUE_INVALIDATED, obj, propName == null ? null : DynUtil.getPropertyMapping(DynUtil.getType(obj), propName), val);
   }

   public static void sendValidate(Object obj, String propName, Object val) {
      sendEvent(IListener.VALUE_VALIDATED, obj, propName == null ? null : DynUtil.getPropertyMapping(DynUtil.getType(obj), propName), val);
   }

   public static void sendChange(Object obj, IBeanMapper prop, Object val) {
      sendEvent(IListener.VALUE_CHANGED, obj, prop, val);
   }

   /** Sends the specified event, e.g. value changed to the property of the given object.  The event property contains a bit mask of the events to send, constants on the IListener interface, e.g. IListener.VALUE_CHANGED
    * The event mask can contain more than one event.   ValueChanged for example sends both value-invalidated and value-validated events.
    * This two pass approach can greatly reduce the number of bindings in complex situations and improves the consistency, getting rid of "validates" that occur with stale values.
    * This variant does not send a "detail value" (used mainly for logging when it's present)  For array element changes, you need the location as the detail but the value typically is computed from the binding.
    * */
   public static void sendEvent(int event, Object obj, Object prop) {
      if (prop instanceof String)
         sendEvent(event, obj, DynUtil.getPropertyMapping(DynUtil.getType(obj), (String) prop));
      else
         sendEvent(event, obj, (IBeanMapper) prop, NO_VALUE_EVENT_DETAIL);
   }

   /** Sends the most common type of event to the object passed.  If you provide prop as "null", it acts like the default event for that object. */
   public static void sendCompiledChangedEvent(Object obj, IBeanMapper prop) {
      sendEvent(IListener.VALUE_CHANGED, obj, prop, NO_VALUE_EVENT_DETAIL);
   }

   private static class ThreadLogState {
      int indentLevel;
      Object obj;
      IBeanMapper prop;
      IBinding binding;
      ThreadLogState prev;
      Object val;
   }

   public static class BindFrame {
      Object obj;
      IBeanMapper prop;
      IListener listener;

      public boolean equals(Object other) {
         if (other instanceof BindFrame) {
            BindFrame o = (BindFrame) other;
            return o.obj == obj && o.prop == prop && o.listener == listener;
         }
         return false;
      }

      public int hashCode() {
         return obj.hashCode() + (prop == null ? 0 : prop.hashCode()) + listener.hashCode();
      }

      public String toString() {
         return DynUtil.getInstanceName(obj) + (prop == null ? "<default event>" : "." + prop) + " listener: " + listener;
      }
   }

   // A lighter weight version of the ThreadLogState that we always maintain.  Used to trap recursive bindings and to know when we are in the top-level setX or triggered by a binding
   private static class ThreadState {
      int nestedLevel;
      Object obj;
      IBeanMapper prop;
      ArrayList<BindFrame> recurseFrames;
   }

   public static int getIndentLevel() {
      ThreadLogState logState = (ThreadLogState) PTypeUtil.getThreadLocal("logState");
      if (logState == null) return 0;
      return logState.indentLevel;
   }

   public static boolean logBindingMessage(String prefix, IBinding binding, Object obj, Object val, Object src) {
      // If we are logging we need to get the current value.  Possibly make this optional since debugging code really should
      // not be calling app code like this.
      if (val == NO_VALUE_EVENT_DETAIL) {
         val = null;
      }

      ThreadLogState logState = (ThreadLogState) PTypeUtil.getThreadLocal("logState");
      if (logState == null || logState.obj != obj || logState.binding != binding || !DynUtil.equalObjects(logState.val,val)) {
         int indentLevel = getIndentLevel();
         String bstr = binding instanceof DestinationListener ? ((DestinationListener) binding).toString(prefix, false) : binding.toString();
         if (src != null)
            bstr = src.toString() + " " + bstr;
         // When the binding is not valid, it does not print the object's value.  But this is not ideal... we want
         // to see the value in the debug message either way so do it by hand here in that case.
         if (val == null)
            System.out.println(indent(indentLevel) + bstr);
         else {
            System.out.println(indent(indentLevel) + bstr + " = " + DynUtil.getInstanceName(val));
         }

         ThreadLogState prev = logState;
         logState = new ThreadLogState();
         logState.obj = obj;
         logState.binding = binding;
         logState.prev = prev;
         logState.val = val;
         logState.indentLevel = prev == null ? 0 : prev.indentLevel + 1;
         PTypeUtil.setThreadLocal("logState", logState);
         return true;
      }
      else
         return false;
   }

   public static void logMessage(String prefix, Object obj, IBeanMapper prop, Object val) {
      boolean endIndent = false;
      try {
         endIndent = logPropMessage(prefix, obj, prop, val);
      }
      finally {
         if (endIndent)
            endPropMessage();
      }
   }

   public static boolean logPropMessage(String prefix, Object obj, IBeanMapper prop, Object val) {
      // If we are logging we need to get the current value.  Possibly make this optional since debugging code really should
      // not be calling app code like this.
      if (val == NO_VALUE_EVENT_DETAIL) {
         if (prop != null)
            val = prop.getPropertyValue(obj, true, false); // using 'true' here to avoid creating objects just to log
         else
            val = null;
      }

      ThreadLogState logState = (ThreadLogState) PTypeUtil.getThreadLocal("logState");
      if (logState == null || logState.obj != obj || logState.prop != prop || !DynUtil.equalObjects(logState.val,val)) {
         DestinationListener binding = getBinding(obj, prop);
         int indentLevel = logState == null ? 0 : logState.indentLevel;
         if (binding != null) {
            String bstr = binding.toString(prefix, true);
            // When the binding is not valid, it does not print the object's value.  But this is not ideal... we want
            // to see the value in the debug message either way so do it by hand here in that case.
            if (binding.isValid())
               System.out.println(indent(indentLevel) + bstr);
            else {
               System.out.println(indent(indentLevel) + bstr + " = " + getPrintableValue(val, obj, prop));
            }
         }
         else if (prop == null) {
            System.out.println("default event for: " + DynUtil.getInstanceName(obj));
         }
         else
            System.out.println(prefix + " " + DynUtil.getInstanceName(obj) + "." + prop.getPropertyName() + " = " + getPrintableValue(val, obj, prop));

         ThreadLogState prev = logState;
         logState = new ThreadLogState();
         logState.obj = obj;
         logState.prop = prop;
         logState.prev = prev;
         logState.val = val;
         logState.indentLevel = prev == null ? 0 : prev.indentLevel + 1;
         PTypeUtil.setThreadLocal("logState", logState);
         return true;
      }
      else
         return false;
   }

   private static String getPrintableValue(Object val, Object obj, IBeanMapper prop) {
       return DynUtil.getInstanceName(val == IListener.UNSET_VALUE_SENTINEL ? prop.getPropertyValue(obj, true, false) : val);
   }

   public static void endPropMessage() {
      PTypeUtil.setThreadLocal("logState", ((ThreadLogState) PTypeUtil.getThreadLocal("logState")).prev);
   }

   public static void sendEvent(int event, Object obj, String prop, Object eventDetail) {
      sendEvent(event, obj, DynUtil.getPropertyMapping(DynUtil.getType(obj), prop), eventDetail);
   }

   // TODO: it would be nice to validate that "obj" has "prop" but kind of expensive and if this call is generated
   // the check will have performed then.
   public static void sendEvent(int event, Object obj, IBeanMapper prop, Object eventDetail) {
      boolean endLogIndent = false;
      try {
         BindingListener [] bindings = Bind.getBindingListeners(obj);
         if (bindings == null)
            return;
         boolean isStatic = DynUtil.isType(obj);
         int propPos = prop == null ? 0 : (isStatic ? prop.getStaticPropertyPosition() : prop.getPropertyPosition(obj));

         if (bindings.length <= propPos)
            return;

         BindingListener bl = bindings[propPos];
         if (event == IListener.VALUE_CHANGED) {
            if ((trace && (traceAll || bl != null)) || (bl != null && (bl.flags & (Bind.TRACE | Bind.VERBOSE)) != 0)) {
               endLogIndent = logPropMessage("Set", obj, prop, eventDetail);
            }

            if (bl != null) {
               dispatchListeners(bindings, propPos, IListener.VALUE_INVALIDATED, obj, prop, eventDetail);
               dispatchListeners(bindings, propPos, IListener.VALUE_VALIDATED, obj, prop, eventDetail);
            }
         }
         else {
            if ((event == IListener.VALUE_VALIDATED && trace && (traceAll || bl != null)) || (bl != null && (bl.flags & (Bind.TRACE | Bind.VERBOSE)) != 0)) {
               endLogIndent = logPropMessage("ISet", obj, prop, eventDetail);
            }
            if (bl != null)
               dispatchListeners(bindings, propPos, event, obj, prop, eventDetail);
         }
      }
      finally {
         if (endLogIndent)
            endPropMessage();
      }
      // TODO: add a flag - something like "per operation" or "ordered" which executes the listeners
      // here - after dependency reordering.
   }

   private static void dispatchListeners(BindingListener[] bindings, int propPos, int event, Object obj, IBeanMapper prop, Object eventDetail) {
      BindingListener b = bindings[propPos];
      while (b != null) {
         if ((b.eventMask & event) == event) {
            IListener listener = b.listener;
            bindingManager.sendEvent(listener, event, obj, prop, eventDetail);
         }
         b = b.next;
      }

   }

   /** Used to send change events to all properties on "obj" */
   public static void sendAllEvents(int event, Object obj) {
      BindingListener [] bindings = Bind.getBindingListeners(obj);
      if (bindings == null)
         return;

      boolean isStatic = DynUtil.isType(obj);
      IBeanMapper[] mappers = isStatic ? DynUtil.getStaticProperties(obj) : DynUtil.getProperties(DynUtil.getType(obj));
      assert mappers.length == bindings.length;

      int i = 0;
      for (BindingListener b:bindings) {
         while (b != null) {
            if ((b.eventMask & event) == event) {
               IListener listener = b.listener;
               bindingManager.sendEvent(listener, event, obj, mappers[i], null);
            }
            b = b.next;
         }
         i++;
      }
   }

   // TODO: make this configurable. It's possible to have valid long chains of bindings 
   private final static int RecursionDetectionThreadhold = 150;

   public static int getNestedBindingCount() {
      ThreadState bindState = (ThreadState) PTypeUtil.getThreadLocal("bindingState");
      if (bindState == null)
         return 0;
      return bindState.nestedLevel;
   }

   static void dispatchEvent(int event, Object obj, IBeanMapper prop, IListener listener, Object eventDetail, CurrentScopeContext origCtx) {
      if (listener.isCrossScope()) {
         CurrentScopeContext curScopeCtx = CurrentScopeContext.getCurrentScopeContext();
         List<CurrentScopeContext> listenerCtxs = listener.getCurrentScopeContexts();
         boolean deliverToCurrent = false;
         int ct = 0;
         for (CurrentScopeContext listenerCtx:listenerCtxs) {
            // Once an event has been queued once, it has an origCtx - where this event originated. This keeps us from sending it back to the source after it's been queued
            if (origCtx != null && origCtx.sameContexts(listenerCtx))
               continue;

            if (listenerCtx != curScopeCtx) {
               ScopeContext listenerEventScopeCtx = listenerCtx.getEventScopeContext();
               ScopeContext curEventScopeCtx = curScopeCtx.getEventScopeContext();
               // TODO: should the binding store the ScopeContext where the component lives instead? Then there would only be one ScopeContext, even for a shared object,
               // we would not have to use accessBinding to add each leaf context and the change event
               if (listenerEventScopeCtx != null && listenerEventScopeCtx != curEventScopeCtx) {
                  BindingContext bctx = (BindingContext) listenerEventScopeCtx.eventListener;
                  if (bctx == null) {
                     synchronized (listenerEventScopeCtx.eventListenerLock) {
                        bctx = (BindingContext) listenerEventScopeCtx.eventListener;
                        if (bctx == null)
                           listenerEventScopeCtx.eventListener = bctx = new BindingContext(IListener.SyncType.IMMEDIATE);
                     }
                  }
                  if (ScopeDefinition.trace || trace)
                     System.out.println("Bind - cross scope event: " + DynUtil.getInstanceName(obj) + "." + (prop == null ? "<default-event>" : prop.getPropertyName()) + " - Queuing event from: " + curScopeCtx + " to: " + listenerEventScopeCtx + " in: " + listenerCtx);
                  bctx.queueEvent(event, obj, prop, listener, eventDetail, curScopeCtx);
                  listenerEventScopeCtx.scopeChanged(); // This schedules a 'notify()' call on the listeners in a doLater and so does not need a doLater step itself... we want to batch up all events delivered in one sync so they are batched to the remote side in one sync.
                  ct++;
               }
               else
                  deliverToCurrent = true;
            }
            else
               deliverToCurrent = true;
         }
         if (!deliverToCurrent)
            return;
      }
      ThreadState threadState = initThreadState(obj, prop, listener);
      try {
         switch (event) {
            case IListener.VALUE_INVALIDATED:
               listener.valueInvalidated(obj, prop, eventDetail, true);
               break;
            case IListener.ARRAY_ELEMENT_INVALIDATED:
               listener.arrayElementInvalidated(obj, prop, eventDetail, true);
               break;
            case IListener.VALUE_VALIDATED:
               listener.valueValidated(obj, prop, eventDetail, true);
               break;
            case IListener.ARRAY_ELEMENT_VALIDATED:
               listener.arrayElementValidated(obj, prop, eventDetail, true);
               break;
            case IListener.VALUE_CHANGED:
               listener.valueChanged(obj, prop, eventDetail, true);
               break;
            case IListener.ARRAY_ELEMENT_CHANGED:
               listener.arrayElementChanged(obj, prop, eventDetail, true);
               break;
            case IListener.VALUE_REQUESTED:
               listener.valueRequested(obj, prop);
               break;
         }
      }
      catch (BindingLoopException exc) {
         // Unwind till we have the top event - the one that triggered the whole thing.
         if (threadState.nestedLevel != 1)
            throw exc;
         else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < exc.recurseFrames.size(); i++) {
               sb.append("   " + exc.recurseFrames.get(i));
            }
            System.err.println("Loop detected in bindings for change: " + DynUtil.getInstanceName(obj) + (prop == null ? " <default event>" : "." + prop) + ": " + getBinding(obj, prop) + ": " + sb);
         }
      }
      finally {
         threadState.nestedLevel--;
      }
   }

   private static ThreadState initThreadState(Object obj, IBeanMapper prop, IListener listener) {
      ThreadState bindState = (ThreadState) PTypeUtil.getThreadLocal("bindingState");
      if (bindState == null) {
         bindState = new ThreadState();
         PTypeUtil.setThreadLocal("bindingState", bindState);
      }
      bindState.nestedLevel++;
      bindState.obj = obj;
      bindState.prop = prop;
      // When nestedLevel > the threshold.  Turn on recursion detection.  For each new frame we add: obj, prop, listener, eventDetail? to a list.  Keep recording until we find a matching list already in the list.  Then gather up the list, format it into a nice error message.
      if (bindState.nestedLevel >= RecursionDetectionThreadhold) {
         if (bindState.recurseFrames == null)
            bindState.recurseFrames = new ArrayList<BindFrame>();
         BindFrame bf = new BindFrame();
         bf.obj = obj;
         bf.prop = prop;
         bf.listener = listener;
         if (bindState.recurseFrames.contains(bf) || bindState.recurseFrames.size() > 100) {
            throw new BindingLoopException(bindState.recurseFrames);
         }
         bindState.recurseFrames.add(bf);
      }
      return bindState;
   }

   public static Class getClassForInstance(Object dstObj) {
      if (dstObj instanceof Class)
         return (Class) dstObj;
      else
         return dstObj.getClass();
   }


   public static DestinationListener getBinding(Object obj, String prop) {
      return getBinding(obj, TypeUtil.getObjectPropertyMapping(obj, prop));
   }

   public static DestinationListener getBinding(Object dstObj, IBinding dstProp) {
      List<DestinationListener> bindings;
      DestinationListener toRet = null;
      synchronized (bindingContainerRegistry) {
         if (dstObj instanceof IBindingContainer) {
            IBindingContainer bc = (IBindingContainer) dstObj;
            bindings = bc.getBindings();
         }
         else {
            bindings = bindingContainerRegistry.get(dstObj);
         }
         if (bindings == null)
            return null;
         int sz = bindings.size();
         int i;
         for (i = 0; i < sz; i++) {
            DestinationListener binding = bindings.get(i);
            if (PBindUtil.equalProps(binding.dstProp, dstProp)) {
               // If there's a forward binding, there will be only one.  but we can have multiple
               // reverse only bindings of the form =: a() for the same property.   For logging purposes
               // though we want to get the real forward binding for the property if there is one.
               if (binding.direction.doForward())
                  return binding;
               else
                  toRet = binding;
            }
         }
      }
      return toRet;
   }

   public static boolean hasBindingListeners(Object obj, String prop) {
      return hasBindingListeners(obj, TypeUtil.getObjectPropertyMapping(obj, prop));
   }

   /**
    * Returns true for an object and property where there is one or more bindings that listen for changes on that property.
    * If prop is null, we return true if there are listeners for the default value event listener.
    * For static properties, obj should be a class or TypeDeclaration.
    */
   public static boolean hasBindingListeners(Object obj, IBeanMapper prop) {
      BindingListener[] listeners = getBindingListeners(obj);
      if (listeners == null)
         return false;

      int propPos = prop == null ? 0 : DynUtil.isSType(obj) ? prop.getStaticPropertyPosition() : prop.getPropertyPosition(obj);
      if (propPos == -1 || propPos > listeners.length)
         return false;

      return listeners[propPos] != null;

   }

   /** Returns the list of property listeners for a given bean mapper */
   public static BindingListener getPropListeners(Object obj, IBeanMapper prop) {
      BindingListener[] list = getBindingListeners(obj);
      if (list == null)
         return null;
      return getPropListeners(obj, list, prop);
   }

   /**
    * Use this version when you have already called getBindingListeners and so have the array of listeners to get the list
    * of listeners for more than one property in the object more efficiently.
    */
   public static BindingListener getPropListeners(Object obj, BindingListener[] listeners, IBeanMapper prop) {
      int propPos = prop == null ? 0 : DynUtil.isSType(obj) ? prop.getStaticPropertyPosition() : prop.getPropertyPosition(obj);
      if (propPos == -1 || propPos > listeners.length)
         return null;

      return listeners[propPos];
   }


   public static DestinationListener[] getBindings(Object dstObj) {
      List<DestinationListener> bindings;
      synchronized (bindingContainerRegistry) {
         if (dstObj instanceof IBindingContainer) {
            IBindingContainer bc = (IBindingContainer) dstObj;
            bindings = bc.getBindings();
         }
         else {
            bindings = bindingContainerRegistry.get(dstObj);
         }
         if (bindings == null)
            return null;

         return bindings.toArray(new DestinationListener[bindings.size()]);
      }
   }

   public static String destinationListenerArrayToString(Object theObj, DestinationListener[] bindingListeners) {
      StringBuilder sb = new StringBuilder();
      if (bindingListeners == null || bindingListeners.length == 0)
         return "";
      else {
         for (DestinationListener dl: bindingListeners) {
            sb.append("   ");
            sb.append(dl);
            sb.append("\n");
         }
      }
      return sb.toString();
   }

   public static String listenerArrayToString(Object theObj, BindingListener[] bindingListeners) {
      if (bindingListeners == null)
         return "";

      boolean isStatic = DynUtil.isType(theObj);
      Object theClass = isStatic ? theObj : DynUtil.getType(theObj);

      IBeanMapper[] props = isStatic ? DynUtil.getStaticProperties(theClass) : DynUtil.getProperties(theClass);
      if (props == null)
          return "<no properties>";

      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < props.length; i++) {
         BindingListener l = i < bindingListeners.length ? bindingListeners[i] : null;
         if (l != null) {
            IBeanMapper prop = props[i];
            if (prop == null)
               sb.append("<valueListener>");
            else
               sb.append(prop.getPropertyName());
            sb.append(":");
            sb.append("\n");
            while (l != null) {
               sb.append("   ");
               IListener rootListener = getRootListener(l.listener);
               sb.append(rootListener);
               sb.append("\n");
               l = l.next;
            }
         }
      }
      return sb.toString();
   }

   private static IListener getRootListener(IListener l) {
      if (l instanceof DestinationListener) {
         DestinationListener dl = (DestinationListener) l;
         if (dl.dstObj == dl.dstProp && dl.dstObj instanceof IListener)
            return getRootListener((IListener) dl.dstObj);
      }
      return l;
   }

   public static void printBindings(Object obj) {
      Object cl;
      cl = DynUtil.getType(obj);
      if (obj != cl) {
         String objName = DynUtil.getInstanceName(obj);
         System.out.println("Bindings for instance: " + objName);
         System.out.println("  -- from properties: ");
         System.out.println(Bind.destinationListenerArrayToString(obj, getBindings(obj)));
         System.out.println("  -- to properties: ");
         System.out.println(Bind.listenerArrayToString(obj, Bind.getBindingListeners(obj)));
      }
      String typeName = DynUtil.getTypeName(cl, false);
      System.out.println("Bindings for type: " + typeName);
      System.out.println("  -- from this type (static properties): ");
      System.out.println(Bind.destinationListenerArrayToString(cl, getBindings(cl)));
      System.out.println("  -- to this type (static properties): ");
      System.out.println(Bind.listenerArrayToString(cl, Bind.getBindingListeners(cl)));
      System.out.println("");
   }

   public static void printAllBindings() {
      synchronized (bindingContainerRegistry) {
         System.out.println("Printing all bindings:");
         for (Map.Entry<Object,List<DestinationListener>> ent:bindingContainerRegistry.entrySet()) {
            Object obj = ent.getKey();
            printBindings(obj);
         }
         System.out.println("");
      }
   }

   public static String indent(int n) {
      if (n == 0)
         return "";
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < n; i++)
         sb.append("   ");
      return sb.toString();
   }

   public static String arrayToString(Object[] list) {
      if (list == null)
         return "";
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < list.length; i++) {
         if (i != 0)
            sb.append(", ");
         sb.append(list[i]);
      }
      return sb.toString();
   }

   public static void setBindingParent(Object prop, IBinding parent, BindingDirection dir) {
      if (prop instanceof String)
          return;
      if (prop instanceof IBinding)
         ((IBinding) prop).setBindingParent(parent, dir);
   }
   public static void activate(Object prop, boolean state, Object bindingParent, boolean chained) {
      if (prop instanceof IBinding)
        ((IBinding) prop).activate(state, bindingParent, chained);
   }

   public static void applyReverseBinding(Object obj, Object prop, Object value, Object src) {
      if (prop instanceof IBinding)
         ((IBinding) prop).applyReverseBinding(obj, value, src);
      else if (obj != null)
         DynUtil.setPropertyValue(obj, (String)prop, value);
   }

   public static void applyBinding(Object obj, Object prop, Object value, IBinding src) {
      if (prop instanceof IBinding)
         ((IBinding) prop).applyBinding(obj, value, src, false, false);
      else
         DynUtil.setPropertyValue(obj, (String) prop, value);
   }

   public static void parentBindingChanged(Object prop) {
      if (prop instanceof IBinding)
         ((IBinding) prop).parentBindingChanged();
   }
}
