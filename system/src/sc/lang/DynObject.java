/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
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
import java.util.BitSet;

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

   // TODO: remove - debug only
   transient ThreadLocal<Integer> nestCount = new ThreadLocal<Integer>();
   transient ThreadLocal<Integer> setNestCount = new ThreadLocal<Integer>();

   public Object getPropertyFromWrapper(IDynObject origObj, String propName) {
      int index = type.getDynInstPropertyIndex(propName);
      if (index == -1) {
         index = type.getDynStaticFieldIndex(propName);
         if (index == -1) {
            IBeanMapper mapper = DynUtil.getPropertyMapping(type, propName);
            // Note: DynBeanMapper, at least with the GET method thing wraps back around so don't vector of to it
            if (mapper == null)
               throw new IllegalArgumentException("No property: " + propName + " for get value on type: " + type.typeName);

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
         }
         else
            return type.getDynStaticProperty(index);
      }
      return getPropertyFromWrapper(origObj, index);
   }

   public Object getProperty(String propName) {
      return getPropertyFromWrapper(this, propName);
   }

   public Object getProperty(int propIndex) {
      return getPropertyFromWrapper(this, propIndex);
   }

   public Object getPropertyFromWrapper(IDynObject origObj, int propIndex) {
      if (propIndex >= properties.length || propIndex < 0)
         throw new IllegalArgumentException("No property with index: " + propIndex + " in type: " + type.typeName);
      Object val = properties[propIndex];
      if (val == lazyInitSentinel)
         val = properties[propIndex] = type.initLazyDynProperty(origObj, propIndex, true);
      return val;
   }

   public void setLazyInitProperty(int index) {
      properties[index] = lazyInitSentinel;
   }

   public <T> T getTypedProperty(String propName, Class<T> propType) {
      return (T) getProperty(propName);
   }

   public void setPropertyFromWrapper(Object origObj, String propName, Object value, boolean setField) {
      int index = type.getDynInstPropertyIndex(propName);
      if (index == -1) {
         index = type.getDynStaticFieldIndex(propName);
         if (index == -1) {
            IBeanMapper mapper = DynUtil.getPropertyMapping(type, propName);
            if (mapper == null)
               type.runtimeError(IllegalArgumentException.class, "No property: " + propName + " for set value in type: ");
            else {
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
      return (T) LayeredSystem.getCurrent().resolveName(typeName, true);
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


   public static Object create(boolean doInit, TypeDeclaration dynType, Object outerObj, String constructorSig, Object...args) {
      Class rtClass = dynType.getCompiledClass();
      Object dynObj;
      if (rtClass == null) {
         dynType.displayError("No compiled class for creating: " + dynType.getFullTypeName() + ": ");
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
            // the compiled classes use an inner type, add the outer obj as a parameteter.
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
                  if ((accessClass != null) && dynType.needsDynInnerStub) {
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
               if (outerObj != null && ModelUtil.getEnclosingType(rtClass) != null) {
                  newerArgs = new Object[newArgs.length + 1];
                  System.arraycopy(newArgs, 0, newerArgs, 1, newArgs.length);
                  newerArgs[0] = outerObj;
                  args = newerArgs;
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
               dynType.getLayeredSystem().addDynInnerInstance(dynType.getFullTypeName(), dynObj, outerObj);
            }
            else {
               dynType.getLayeredSystem().addDynInstance(dynType.getFullTypeName(), dynObj);
            }
         }

         ctx.pushCurrentObject(dynObj);
         pushedObj = true;
         
         dynType.initDynamicFields(dynObj, ctx);
         
         Object constr = null;
         if (constructorSig != null) {
            constr = dynType.getConstructorFromSignature(constructorSig);
         }
         else {
            if (args == null || args.length == 0) {
               // Look for an inherited constructor here.  If we don't find the zero arg constructor in our
               // type we'll stil have to invoke the one in the super type.
               constr = dynType.definesConstructor(null, null, false);
               // Unless the constructor we found is not dynamic - in that case, it will get called when we create the Java instance
               if (!ModelUtil.isDynamicType(constr))
                  constr = null;
            }
            else {
               Object[] cstrs = dynType.getConstructors(null);
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
            ModelUtil.invokeMethod(null, constr, args, ctx);
         }
         dynType.initDynComponent(dynObj, ctx, doInit, outerObj, true);
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
         return TypeUtil.invokeMethod(inst, meth, args);
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
         return ((IDynObject) srcObj).getProperty(OUTER_INSTANCE_SLOT);
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
         System.err.println("*** Missing old field mapping in set type for: " + type.getFullTypeName());
         if (newType.getDynInstFieldCount() == properties.length)
            newProperties = properties;
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
