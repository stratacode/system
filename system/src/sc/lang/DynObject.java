/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.bind.Bind;
import sc.bind.IListener;
import sc.dyn.IDynObject;
import sc.dyn.DynUtil;
import sc.lang.java.*;
import sc.layer.LayeredSystem;
import sc.type.IBeanMapper;
import sc.type.RTypeUtil;
import sc.type.PTypeUtil;
import sc.type.TypeUtil;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.BitSet;

/** Used for dynamic types which do not extend either a compiled type, or type which is already an IDynObject  */
public class DynObject implements IDynObject, IDynSupport, Serializable {
   public final static String lazyInitSentinel = new String("<LazyInitPending>");
   /** Index of the slot reserved for storing the enclosing instance */
   public final static int OUTER_INSTANCE_SLOT = 0;
   
   transient BodyTypeDeclaration type;
   transient Object[] properties;

   public DynObject(BodyTypeDeclaration decl) {
      type = decl;
      properties = new Object[type.getDynInstFieldCount()];
   }

   /* DEBUGGING TIP!  Debug nestCount  If you hit an infinite loop involving this class, try uncommenting this section and where these are used below.  It happens
     when there's a bug identifying if a given property is dynamic or not and we bounce back and forth each thinking it's the other
   transient ThreadLocal<Integer> nestCount = new ThreadLocal<Integer>();
   transient ThreadLocal<Integer> setNestCount = new ThreadLocal<Integer>();
   */

   public Object getPropertyFromWrapper(IDynObject origObj, String propName, boolean getField) {
      int index = type.getDynInstPropertyIndex(propName);
      if (index == -1) {
         index = type.getDynStaticFieldIndex(propName);
         if (index == -1) {
            IBeanMapper mapper = DynUtil.getPropertyMapping(type, propName);
            // Note: DynBeanMapper, at least with the GET method thing wraps back around so don't vector of to it
            if (mapper == null) {
               index = type.getDynInstPropertyIndex(propName);
               mapper = DynUtil.getPropertyMapping(type, propName);
               throw new IllegalArgumentException("No property: " + propName + " for get value on type: " + type.typeName);
            }

            return TypeUtil.getPropertyValue(origObj, mapper, getField);
            /*
            Integer nestCt = nestCount.get();
            if (nestCt == null)
               nestCount.set(0);
            else {
               if (nestCt > 10) {
                  System.out.println("*** Invalid get dynamic property property error: " + propName);
                  throw new IllegalArgumentException("No property: " + propName);
               }
               nestCount.set(nestCt + 1);
            }
            try {
               return TypeUtil.getPropertyValue(origObj, mapper);
            }
            finally {
               if (nestCt == null)
                  nestCount.set(null);
            }
            */
         }
         else
            return type.getDynStaticProperty(index);
      }
      return getPropertyFromWrapper(origObj, index, getField);
   }

   public Object getProperty(String propName, boolean getField) {
      return getPropertyFromWrapper(this, propName, getField);
   }

   public Object getProperty(int propIndex, boolean getField) {
      return getPropertyFromWrapper(this, propIndex, getField);
   }

   public Object getPropertyFromWrapper(IDynObject origObj, int propIndex, boolean getField) {
      if (propIndex >= properties.length || propIndex < 0)
         throw new IllegalArgumentException("No property with index: " + propIndex + " in type: " + type.typeName);
      Object val = properties[propIndex];
      if (val == lazyInitSentinel) {
         if (getField) // This is basically the way we see if object has been initialized yet - so don't init it here if getField is true
            return null;
         val = properties[propIndex] = type.initLazyDynProperty(origObj, propIndex, true);
      }
      return val;
   }

   public void setLazyInitProperty(int index) {
      properties[index] = lazyInitSentinel;
   }

   public <T> T getTypedProperty(String propName, Class<T> propType) {
      return (T) getProperty(propName, false);
   }

   public void setPropertyFromWrapper(Object origObj, String propName, Object value, boolean setField) {
      if (type.replaced) // This type might have been changed (and replaced) but the instance was not tracked in instancesByType so we failed to update it proactively.
         type = type.resolve(true);
      int index = type.getDynInstPropertyIndex(propName);
      if (index == -1) {
         index = type.getDynStaticFieldIndex(propName);
         if (index == -1) {
            IBeanMapper mapper = DynUtil.getPropertyMapping(type, propName);
            if (mapper == null)
               type.runtimeError(IllegalArgumentException.class, "No property: " + propName + " for set value in type: ");
            else {
               // Debug nestCount - comment this next line out and uncomment the block following it
               TypeUtil.setProperty(origObj, mapper, value);
               /*
               Integer nestCt = setNestCount.get();
               if (nestCt == null)
                  setNestCount.set(0);
               else {
                  if (nestCt > 10) {
                     System.out.println("*** Invalid dynamic set property property error: " + propName);
                     throw new IllegalArgumentException("No property: " + propName);
                  }
                  setNestCount.set(nestCt + 1);
               }
               //TODO: if (mapper instanceof DynBeanMapper)
               //   ... sometimes this causes an infinite loop if index = -1 for a dyn property that uses the DynBeanMapper
               //   but not all of the time - because Node and other classes implement IDynObject which then route here to find
               //   the dyn bean property.  If we ask for a property which is not found, this method will just try to call the dyn property again.
               try {
                  TypeUtil.setProperty(origObj, mapper, value);
               }
               finally {
                  if (nestCt == null)
                     setNestCount.set(null);
               }
               */
            }
         }
         else
            type.setDynStaticField(index, value);
         // TODO: static dynamic events!
      }
      else {
         if (index < 0 || index >= properties.length)
            System.out.println("**** invalid property: " + index);
         else {
            IBeanMapper mapper = type.getPropertyMapping(propName);
            if (mapper == null) {
               System.err.println("*** Property has index but no mapper: " + propName);
               properties[index] = value;
            }
            else if (!setField && mapper.getSetSelector() != null) {
               // Debug nestCount - comment this out and uncomment the next block
               mapper.setPropertyValue(origObj, value);
               /*
               Integer nestCt = setNestCount.get();
               if (nestCt == null)
                  setNestCount.set(0);
               else {
                  if (nestCt > 10) {
                     System.out.println("*** Invalid dynamic set property property error: " + propName);
                     throw new IllegalArgumentException("No property: " + propName);
                  }
                  setNestCount.set(nestCt + 1);
               }
               try {
                  mapper.setPropertyValue(origObj, value);
               }
               finally {
                  if (nestCt == null)
                     setNestCount.set(null);
               }
               */
            }
            else
               properties[index] = value;


            // Don't fire the events for constant objects.  In particular if this is the property for an object definition
            // firing it when the object instance is set is too early for an event.
            if (mapper != null && !mapper.isConstant())
               Bind.sendEvent(IListener.VALUE_CHANGED, origObj, mapper, value);
         }
      }
   }

   public void setProperty(String propName, Object value, boolean setField) {
      setPropertyFromWrapper(this, propName, value, setField);
   }

   public void setProperty(int propIndex, Object value, boolean setField) {
      int ix = propIndex;
      if (ix < 0 || ix >= properties.length)
         throw new IllegalArgumentException("Invalid property index: " + propIndex);
      // assert setField == true - by the time it gets here it should always be looking to set the field
      properties[ix] = value;
   }

   public static void setStaticProperty(String typeName, String propName, Object value) {
      TypeDeclaration dynType = LayeredSystem.getCurrent().getSrcTypeDeclaration(typeName, null, true);
      if (dynType == null)
         throw new IllegalArgumentException("No type named: " + typeName + " for dynamic set static property: " + propName);
      setStaticProperty(dynType, propName, value);
   }

   public static void setStaticProperty(TypeDeclaration dynType, String propName, Object value) {
      IBeanMapper mapper = dynType.getPropertyMapping(propName);
      if (mapper == null)
         throw new IllegalArgumentException("No property named: " + propName + " for dynamic set static property on type: " + dynType.typeName);
      dynType.setDynStaticField(propName, value);
      Bind.sendEvent(IListener.VALUE_CHANGED, dynType, mapper);
   }

   public static Object getStaticProperty(String typeName, String propName) {
      TypeDeclaration dynType = LayeredSystem.getCurrent().getSrcTypeDeclaration(typeName, null, true);
      if (dynType == null)
         throw new IllegalArgumentException("No type named: " + typeName + " for dynamic get static property: " + propName);
      return dynType.getStaticProperty(propName);
   }

   public static <T> T getTypedStaticProperty(String typeName, String propName, Class<T> valClass) {
      return (T) getStaticProperty(typeName, propName);
   }

   public static <T> T resolveName(String typeName, Class<T> valClass) {
      return (T) LayeredSystem.getCurrent().resolveName(typeName, true, true);
   }

   public static TypeDeclaration getType(String typeName) {
      return LayeredSystem.getCurrent().getSrcTypeDeclaration(typeName, null, true);
   }

   public BodyTypeDeclaration getDynType() {
      return type;
   }

   public void addProperty(Object propType, String propName, Object initValue) {
      int propIndex = type.getDynInstPropertyIndex(propName);
      int propLen = properties.length;
      if (propIndex != -1 && propIndex < propLen) {
         // re-initializing?
         properties[propIndex] = initValue;
      }
      else {
         //if (propIndex == -1)  This happens because we start adding props to the instances before we add it to the type.
         //   System.out.println("*** adding prop to instances before it is added to the type");
         int newLen = propLen + 1;
         Object[] newProps = new Object[newLen];
         System.arraycopy(properties, 0, newProps, 0, propLen);
         newProps[propLen] = initValue;
         properties = newProps;
      }
   }

   public static Object create(TypeDeclaration dynType, String constructorSig, Object...args) {
      return create(dynType, null, constructorSig, args);
   }


   public static Object create(TypeDeclaration dynType, Object outerObj, String constructorSig, Object...args) {
      return create(true, dynType, outerObj, constructorSig, args);
   }

   // TODO: this is almost the same as BodyTypeDeclaration.createInstance for where it handles 'class' creation... this
   // version works for LayerComponents. We should clean this up so there's only one copy of this logic since there's a lot
   // of overlap in the different versions. This version takes the constructor parameters - that version takes Expressions that
   // are evaluated to get the constructor values, and that version deals with instantiating objects, not just classes.
   public static Object create(boolean doInit, TypeDeclaration dynType, Object outerObj, String constructorSig, Object...args) {
      Object inst = null;
      ExecutionContext ctx = new ExecutionContext(dynType.getJavaModel());

      if (dynType.isLayerComponent())
         return createCompiled(doInit, dynType, outerObj, constructorSig, args);

      if (dynType.isDynamicNew()) {
         // Get this before we add the inner obj's parent
         ConstructorDefinition con = (ConstructorDefinition) dynType.getConstructorFromSignature(constructorSig, true);
         int origNumArgs = args == null ? 0 : args.length;

         // First get the constructor values in the parent's context
         boolean success = false;
         boolean isDynStub = dynType.isDynamicStub(false);

         boolean pushedInst = false;
         boolean needsInit = false;

         try {
            // TODO: paramTypes should be providing a type context here right?
            if (con == null && origNumArgs > 0)
               throw new IllegalArgumentException("No constructor matching " + Arrays.asList(args) + " for: ");

            // If there are constructors and a base class (but we are not a dynamic stub), we need to do some work here.  The super(xx) call is the first
            // time we have the constructor/args to construct the instance.   In that case, mark the ctx,
            // set call the constructor.  when super is hit, it sees the flag in the ctx and creates the
            // instance (or propagates the pending constructor to the next super call).
            //
            // If there is a constructor and a base class but no super
            // call - using the implied zero arg constructor.  In that case, we do the init here.

            if (outerObj == null)
               outerObj = ModelUtil.getOuterObject(dynType, ctx);

            if (con == null || !con.callsSuper(true) || isDynStub) {
               // Was emptyObjectArray for the args
               inst = dynType.constructInstance(ctx, outerObj, args, false, true, true);
               needsInit = false;
            }
            else {
               if (ctx.getOrigConstructor() == null)
                  ctx.setOrigConstructor(dynType);
               ctx.setPendingConstructor(dynType);
               ctx.setPendingOuterObj(outerObj);
               needsInit = true;
            }

            if (con != null) {
               //if (inst != null) {
               //   ctx.pushCurrentObject(inst);
               //   pushedInst = true;
               //}

               //if (inst != null)
               //   dynType.initDynInstance(inst, ctx, true, false, outerObj, null, false, false);

               con.invoke(ctx, Arrays.asList(args));

               //if (ctx.currentObjects.size() > 0)
               //   inst = ctx.getCurrentObject();
               //else
               //  System.err.println("*** No current object created in constructor");

               //dynType.initDynInstance(inst, ctx, true, false, outerObj, args);
            }

            if (ctx.getPendingConstructor() != null)
               throw new IllegalArgumentException("Failure to construct instance of type: " + dynType.getFullTypeName());
            success = true;
         }
         finally {
            if (success && ctx.currentObjects.size() > 0) {
               inst = ctx.getCurrentObject();
               if (inst != null) {
                  if (outerObj != null)
                     dynType.initOuterInstanceSlot(inst, ctx, outerObj);

                  //if (needsInit) {
                  //   dynType.initDynInstance(inst, ctx, true, false, outerObj, null, false, false);
                  //}
                  // Set the slot before we populate so that we can resolve cycles
                  /*
                  if (dynIndex != -1) {
                     if (outerObj instanceof IDynObject) {
                        IDynObject dynParent = (IDynObject) outerObj;
                        dynParent.setProperty(dynIndex, inst, true);
                     }
                  */
                        /*
                        else
                           dynParentType.setDynStaticField(dynIndex, inst);
                  }
                        */

                  dynType.initNewSyncInst(args, inst);

                  dynType.initDynComponent(inst, ctx, doInit, outerObj, args, false);
                  // Fetches the object pushed from constructInstance, as called indirectly when super() is evaled.
               }
               ctx.popCurrentObject();
            }
         }
      }
      else {
         inst = createCompiled(doInit, dynType, outerObj, constructorSig, args);
      }
      if (inst == null)
         System.err.println("*** Failed to create instance of: " + dynType);
      return inst;
   }

   public static Object createCompiled(boolean doInit, TypeDeclaration dynType, Object outerObj, String constructorSig, Object...args) {
      Class rtClass = dynType.getCompiledClass();
      Object dynObj;
      boolean dynInnerArgs = false;
      if (rtClass == null) {
         dynType.displayError("No compiled class for creating: " + dynType.getFullTypeName() + ": ");
         rtClass = dynType.getCompiledClass();
      }

      if (rtClass == null || rtClass == IDynObject.class)
         dynObj = new DynObject(dynType);
      else {
         // Note: classes like Element can extend this interface but are not dynamic types.
         if (dynType.isDynamicType() && IDynObject.class.isAssignableFrom(rtClass)) {
            Object[] newArgs = new Object[(args == null ? 0 : args.length)+1];
            newArgs[0] = dynType;
            if (args != null)
               System.arraycopy(args, 0, newArgs, 1, args.length);

            Class accessClass = null;
            Object accessType = null;
            BodyTypeDeclaration enclType = null;
            if (outerObj != null) {
               enclType = dynType.getEnclosingType();
               if (enclType != null) {
                  accessType = enclType;
                  accessClass = enclType.getCompiledClass();
               }
            }

            // If we have an enclosing instance and the class we access this type is not the type itself... ie.
            // the compiled classes use an inner type, add the outer obj as a parameter.
            /*
            if (outerObj != null && accessClass != null) {
               Object[] newerArgs = new Object[newArgs.length+1];
               newerArgs[0] = outerObj;
               System.arraycopy(newArgs, 0, newerArgs, 1, newArgs.length);
               newArgs = newerArgs;
            }
            */

            if (ModelUtil.isComponentType(rtClass)) {
               // The newX method is defined on the enclosing type in this case.  That provides "outerObj" for the
               // compiled case.
               if (outerObj != null) {
                  // Make sure the runtime class is itself an inner class before we make the enclosing type be the access class.  We may not have
                  // needed to generate a class for the inner type
                  if ((accessClass != null) && dynType.getNeedsDynInnerStub()) {
                     String name = dynType.typeName;
                     Object curType = dynType;
                     while (curType != null && !ModelUtil.sameTypes(ModelUtil.getEnclosingType(curType), accessType)) {
                        // For modified types, this returned the modified type, not the extends type
                        curType = ModelUtil.getSuperclass(curType);
                        if (curType != null)
                           name = ModelUtil.getClassName(curType);
                     }
                     return RTypeUtil.newInnerComponent(outerObj, accessClass, rtClass, name, newArgs);
                  }
                  else {
                     String name = dynType.getInnerStubTypeName();
                     return RTypeUtil.newInnerComponent(outerObj, rtClass, rtClass, name, newArgs);
                  }
               }
               else
                  return RTypeUtil.newComponent(rtClass, newArgs);
            }
            else {
               Object[] newerArgs = newArgs;
               if (outerObj != null) {
                  if (ModelUtil.getEnclosingType(rtClass) != null) {
                     newerArgs = new Object[newArgs.length + 1];
                     System.arraycopy(newArgs, 0, newerArgs, 1, newArgs.length);
                     newerArgs[0] = outerObj;
                     args = newerArgs;
                  }
                  else
                     dynInnerArgs = true;
               }

               dynObj = PTypeUtil.createInstance(rtClass, constructorSig, newerArgs);
            }
         }
         else {
            if (outerObj != null && ModelUtil.getEnclosingInstType(rtClass) != null) {
               Object[] newArgs = new Object[args.length+1];
               newArgs[0] = outerObj;
               System.arraycopy(args, 0, newArgs, 0, args.length);
               args = newArgs;
            }
            dynObj = PTypeUtil.createInstance(rtClass, constructorSig, args);
         }
      }
      ExecutionContext ctx = new ExecutionContext(dynType.getJavaModel());
      if (outerObj != null)
         ctx.pushCurrentObject(outerObj);
      boolean pushedObj = false;
      try {
         dynType.initOuterInstanceSlot(dynObj, ctx, outerObj);

         if (dynType.getLiveDynamicTypesAnnotation()) {
            // Add this instance to the global table so we can do type -> inst mapping
            if (outerObj != null) {
               // TODO: should be able to remove this since we add it in initOuterInstanceSlot
               dynType.getLayeredSystem().addDynInnerInstance(dynType.getFullTypeName(), dynObj, outerObj);
            }
            else {
               dynType.getLayeredSystem().addDynInstance(dynType.getFullTypeName(), dynObj);
            }
         }

         ctx.pushCurrentObject(dynObj);
         pushedObj = true;
         
         dynType.initDynamicFields(dynObj, ctx, true);
         
         Object constr = null;
         if (constructorSig != null) {
            constr = dynType.getConstructorFromSignature(constructorSig, true);
         }
         else {
            if (args == null || args.length == 0) {
               // Look for an inherited constructor here.  If we don't find the zero arg constructor in our
               // type we'll still have to invoke the one in the super type.
               constr = dynType.definesConstructor(null, null, false);
               // Unless the constructor we found is not dynamic - in that case, it will get called when we create the Java instance
               if (!ModelUtil.isDynamicType(constr))
                  constr = null;
            }
            else {
               Object[] cstrs = dynType.getConstructors(null, true);
               if (cstrs != null) {
                  if (cstrs.length == 1) {
                     constr = cstrs[0];
                  }
                  else if (cstrs.length > 1) {
                     dynType.displayError("Unable to choose between multiple constructors for dynamic new of: ");
                     throw new IllegalArgumentException("Invalid constructors");
                  }
               }
            }
         }
         // Any native constructors will be called indirectly by the stub
         if (constr instanceof ConstructorDefinition) {
            // A dynamic type where the compiled class is not an innerObject but the actual dynType is an inner instance type.
            // The constructor expects to be called with the outerObj as the first argument but we did not add it above.
            if (dynInnerArgs) {
               Object[] innerArgs = new Object[args == null ? 1 : args.length+1];
               if (args != null)
                  System.arraycopy(args, 0, innerArgs, 1, args.length);
               innerArgs[0] = outerObj;
               args = innerArgs;
            }
            ModelUtil.invokeMethod(null, constr, args, ctx);
         }

         dynType.initDynComponent(dynObj, ctx, doInit, outerObj, args, true);
      }
      finally {
         if (outerObj != null)
            ctx.popCurrentObject();
         if (pushedObj)
            ctx.popCurrentObject();
      }
      return dynObj;
   }

   public static Object create(String typeName, String constructorSig, Object...args) {
      TypeDeclaration dynType = LayeredSystem.getCurrent().getSrcTypeDeclaration(typeName, null, true);
      if (dynType == null)
         throw new IllegalArgumentException("No type named: " + typeName + " for dynamic new operation");
      return create(dynType, constructorSig, args);
   }

   public static Object create(String typeName, Object outerObj, String constructorSig, Object...args) {
      TypeDeclaration dynType = LayeredSystem.getCurrent().getSrcTypeDeclaration(typeName, null, true);
      if (dynType == null)
         throw new IllegalArgumentException("No type named: " + typeName + " for dynamic new operation");
      return create(dynType, outerObj, constructorSig, args);
   }

   /**
    * When generating getX inner object methods in dynamic stubs, to allow these to be overridden without adding a new getX method to a modifyInherited type, look up the most specific
    * version of the dype for the given instance.  I think this is a little bit of runtime overhead but avoids a lot of changing the compiled definition.
    */
   public static Object createVirtual(String typeName, Object outerObj, String constructorSig, Object...args) {
      TypeDeclaration dynType = LayeredSystem.getCurrent().getSrcTypeDeclaration(typeName, null, true);
      if (dynType == null)
         throw new IllegalArgumentException("No type named: " + typeName + " for dynamic new operation");
      dynType = dynType.getVirtualTypeForInstance(outerObj);
      return create(true, dynType, outerObj, constructorSig, args);
   }

   public static Object createVirtual(boolean doInit, String typeName, Object outerObj, String constructorSig, Object...args) {
      TypeDeclaration dynType = LayeredSystem.getCurrent().getSrcTypeDeclaration(typeName, null, true);
      if (dynType == null)
         throw new IllegalArgumentException("No type named: " + typeName + " for dynamic new operation");
      dynType = dynType.getVirtualTypeForInstance(outerObj);
      return create(doInit, dynType, outerObj, constructorSig, args);
   }

   public static Object create(boolean doInit, String typeName, Object outerObj, String constructorSig, Object...args) {
      TypeDeclaration dynType = LayeredSystem.getCurrent().getSrcTypeDeclaration(typeName, null, true);
      if (dynType == null)
         throw new IllegalArgumentException("No type named: " + typeName + " for dynamic new operation");
      return create(doInit, dynType, outerObj, constructorSig, args);
   }

   public Object invokeFromWrapper(Object origObj, String methodName, String paramSig, Object... args) {
      Object meth = type.getMethodFromSignature(methodName, paramSig, true);
      if (meth == null) {
         type.runtimeError(IllegalArgumentException.class, "No method found: ", methodName, " with signature ", paramSig, " for: ");
         return null; // not reached
      }
      return invokeInternal(origObj, meth, args);
   }

   public Object invokeFromWrapper(Object origObj, int methodIndex, Object... args) {
      Object meth = type.getMethodFromIndex(methodIndex);
      if (meth == null) {
         type.runtimeError(IllegalArgumentException.class, "No method found at position: ", String.valueOf(methodIndex), " for: ");
         return null; // not reached
      }
      return invokeInternal(origObj, meth, args);
   }

   private Object invokeInternal(Object origObj, Object meth, Object... args) {
      ExecutionContext ctx = new ExecutionContext(type.getJavaModel());
      ctx.pushCurrentObject(origObj);
      try {
         return ModelUtil.invokeMethod(origObj, meth, args, ctx);
      }
      finally {
         ctx.popCurrentObject();
      }
   }

   public Object invoke(String methodName, String paramSig, Object... args) {
      return invokeFromWrapper(this, methodName, paramSig, args);
   }

   public Object invoke(int methodIndex, Object...args) {
      Object meth = type.getMethodFromIndex(methodIndex);
      if (meth == null) {
         type.runtimeError(IllegalArgumentException.class, "No method found at position: ", String.valueOf(methodIndex), " for: ");
         return null; // not reached
      }
      return invokeInternal(this, meth, args);
   }

   public static Object invokeInst(Object inst, String methodName, String paramSig, Object... args) {
      if (inst instanceof IDynObject)
         return ((IDynObject) inst).invoke(methodName, paramSig, args);
      else {
         Method meth = RTypeUtil.getMethodFromTypeSignature(inst.getClass(), methodName, paramSig);
         return PTypeUtil.invokeMethod(inst, meth, args);
      }
   }

   public static Object invokeStatic(String typeName, String methodName, String paramSig, Object... args) {
      TypeDeclaration dynType = LayeredSystem.getCurrent().getSrcTypeDeclaration(typeName, null, true);
      if (dynType == null)
         throw new IllegalArgumentException("No type named: " + typeName + " for dynamic method call: " + methodName);
      Object meth = dynType.getMethodFromSignature(methodName, paramSig, true);
      if (meth == null) {
         dynType.runtimeError(IllegalArgumentException.class, "No method found: ", methodName, " with signature ", paramSig, " for: ");
         return null; // not reached
      }
      ExecutionContext ctx = new ExecutionContext(dynType.getJavaModel());
      try {
         return ModelUtil.invokeMethod(null, meth, args, ctx);
      }
      finally {
         ctx.popCurrentObject();
      }
   }

   public String toString() {
      return DynUtil.getInstanceName(this);
   }

   public static Object getParentInstance(Object srcObj) {
      // For non-static inner instances, we store the outer object as the first slot
      // If we were not created with a src type declaration though, the dynamic type does not define our parent.
      if (srcObj instanceof IDynObject && ((IDynObject) srcObj).hasDynObject())
         return ((IDynObject) srcObj).getProperty(OUTER_INSTANCE_SLOT, false);
      else {
         int ix = 0;
         Class cl = srcObj.getClass();
         // Static inner types do not have the this property.
         if ((cl.getModifiers() & Modifier.STATIC) != 0)
            return null;
         if (cl.isEnum() || srcObj instanceof java.lang.Enum)
            return null;
         Class encl;
         if ((encl = cl.getEnclosingClass()) == null)
            return null;
         while ((encl = encl.getEnclosingClass()) != null)
            ix++;
         return TypeUtil.getPropertyValue(srcObj, "this$" + ix);
      }
   }

   public void setDynType(Object typeObj) {
      setTypeFromWrapper(this, typeObj);
   }

   public void setTypeFromWrapper(Object thisObj, Object typeObj) {
      BodyTypeDeclaration newType = (BodyTypeDeclaration) typeObj;
      BodyTypeDeclaration oldType = type;

      /* Now that we are using type names, do not have to re-register
      if (oldType != newType) {
         LayeredSystem sys = oldType.getLayeredSystem();
         sys.removeDynInstance(oldType, thisObj);
         sys.addDynInstance(newType, thisObj);
      }
      */

      // Dyninst fields - computing the mapping table from old to new fields for static/inst.  For each instance replace the
      // properties.
      Object[] oldInstFields = oldType.getOldDynInstFields();
      Object[] newProperties = new Object[newType.getDynInstFieldCount()];
      
      // Copy over the outer obj slot
      int incr = 1;
      if (newType.getEnclosingInstType() != null) {
         newProperties[OUTER_INSTANCE_SLOT] = properties[OUTER_INSTANCE_SLOT];
      }
      if (oldInstFields != null) {
         for (int i = 0; i < oldInstFields.length; i++) {
            int newIx = newType.getDynInstPropertyIndex(ModelUtil.getPropertyName(oldInstFields[i]));
            if (newIx != -1)
               newProperties[newIx] = properties[i+incr];
         }
      }
      else {
         if (newType.getDynInstFieldCount() == properties.length)
            newProperties = properties;
         else
            System.err.println("*** Missing old field mapping in set type for: " + type.getFullTypeName());
      }
      type = newType;
      properties = newProperties;
   }

   private void writeObject(ObjectOutputStream out) throws java.io.IOException {
      out.defaultWriteObject();
      out.writeObject(type.getFullTypeName());
      int num = properties.length;
      out.writeInt(num);
      BitSet dynTransientFields = type.getDynTransientFields();
      for (int i = 0; i < num; i++) {
         if (dynTransientFields.get(i))
            out.writeObject(null);
         else
            out.writeObject(properties[i]);
      }
   }

   private void readObject(ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
      in.defaultReadObject();
      String typeName = (String) in.readObject();
      LayeredSystem sys = LayeredSystem.getCurrent();
      type = (BodyTypeDeclaration) sys.getSrcTypeDeclaration(typeName, null, true, false, true, null, sys.layerResolveContext);
      if (type == null)
         System.err.println("*** No type: " + typeName + " deserializing dynamic object");
      int num = in.readInt();
      properties = new Object[num];
      for (int i = 0; i < num; i++) {
         properties[i] = in.readObject();
      }
   }

   // Used for sub-types like Node which want the ability to implement IDynObject but not always be managing a dyn object
   public boolean hasDynObject() {
      return true;
   }
}
