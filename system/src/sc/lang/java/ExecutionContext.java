/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.dyn.DynUtil;
import sc.dyn.INameContext;
import sc.lang.DynObject;
import sc.layer.LayeredSystem;
import sc.type.IBeanMapper;
import sc.type.PTypeUtil;
import sc.util.CoalescedHashMap;

import java.lang.reflect.Array;
import java.util.List;
import java.util.Stack;

/**
 * Used for executing dynamic code - holds the virtual machine state which affects code execution: the stack, local variables.
 * Customizable for resolving references to names and for allowing operations to take place.
 */
public class ExecutionContext {
   
   private static ThreadLocal<JavaSemanticNode> executingModelObject = new ThreadLocal<JavaSemanticNode>();
   private BodyTypeDeclaration pendingConstructor;
   private BodyTypeDeclaration origConstructor;
   private boolean skipCompiledSuper = false;

   public static void setExecutingModelObject(JavaSemanticNode node) {
      executingModelObject.set(node);
   }

   public static JavaSemanticNode getExecutingModelObject() {
      return executingModelObject.get();
   }

   Stack<Frame> frames = new Stack<Frame>();
   public Stack<Object> currentObjects = new Stack<Object>();

   public String currentLabel; // For the break and continue return actions, this specifies the label to process

   public boolean createObjects = true;

   /**
    * Set to true to enable 'sync' of statements to multiple processes. Used for the command line to control where statements
    * are run when dealing with a multi-process application
    */
   public boolean syncExec = false;

   public INameContext resolver;

   public LayeredSystem system;

   // Set to the value to return for the return statement.
   public Object currentReturnValue = null;

   public Object getCurrentObject() {
      if (currentObjects.size() == 0)
         return null;

      return currentObjects.peek();
   }

   public ExecutionContext() {
      frames.push(new Frame(true, 8, null));
   }

   public ExecutionContext(JavaModel model) {
      resolver = model;
      system = model.layeredSystem;
   }

   public ExecutionContext(INameContext nameResolver) {
      resolver = nameResolver;
      if (nameResolver instanceof LayeredSystem)
         system = (LayeredSystem) nameResolver;
   }

   public void pushStaticFrame(Object typeObj) {
      currentObjects.push(null);
      frames.push(new Frame(false, 0, typeObj));
   }

   public void popStaticFrame() {
      if (currentObjects.size() == 0)
         System.err.println("*** No current objects to pop!");
      else
         currentObjects.pop();
      if (frames.size() == 0)
         System.err.println("*** No frames to pop!");
      else
         frames.pop();
   }

   public Object getCurrentStaticType() {
      Object res;
      if (frames.isEmpty())
         res = null;
      else {
         Object st;
         res = null;
         // Need to skip block statements looking for the current type
         for (int next = frames.size() - 1; next >= 0; next--) {
            Frame f = frames.get(next);
            if ((st = f.staticType) != null) {
               res = st;
               break;
            }
            else if (f.methodFrame)
               break;
         }
      }
      Object curObj;
      if (res == null && currentObjects.size() > 0 && (curObj = currentObjects.peek()) != null)
         return DynUtil.getType(curObj);
      return res;
   }

   public void pushCurrentObject(Object obj) {
      currentObjects.push(obj);
   }

   public Object popCurrentObject() {
      if (currentObjects.size() == 0)
         System.err.println("*** popping off");
      return currentObjects.pop();
   }

   public void pushFrame(boolean methodFrame, int size) {
      frames.push(new Frame(methodFrame, size, null));
   }

   public void pushFrame(boolean methodFrame, int size, List<? extends Object> paramValues, Parameter parameters, Object staticType) {
      int numDecl = parameters == null ? 0 : parameters.getNumParameters();
      size += numDecl;

      int numPassed = paramValues == null ? 0 : paramValues.size();
      if (numDecl != numPassed)
         throw new IllegalArgumentException("Incorrect number of parameters to invocation: " + paramValues + " for: " + parameters);
      Frame fr = new Frame(methodFrame, size, staticType);
      frames.push(fr);
      Parameter current = parameters;
      for (int i = 0; i < numPassed; i++) {
         defineVariable(current.variableName, paramValues.get(i));
         current = current.nextParameter;
      }
   }

   public void popFrame() {
      frames.pop();
   }

   public void defineVariable(String name, Object defaultValue) {
      Frame top = frames.peek();
      if (top.get(name) != null)
         throw new IllegalArgumentException("Redefinition of variable: " + name + " in the frame");

      top.put(name, defaultValue);
   }

   public void setVariable(String name, Object value) {
      for (int i = frames.size()-1; i >= 0; i--) {
         Frame f = frames.get(i);
         if (f.contains(name)) {
            f.put(name, value);
            return;
         }

         if (f.methodFrame)
            break;
      }
      throw new IllegalArgumentException("No variable to set: " + name + " value:" + value);
   }

   public Object getVariable(String name, boolean throwError, boolean global) {
      for (int i = frames.size()-1; i >= 0; i--) {
         Frame f = frames.get(i);
         if (f.contains(name))
            return f.get(name);

         if (!global && f.methodFrame || f.staticType != null)
            break;
      }
      if (throwError)
         throw new IllegalArgumentException("No variable to get: " + name);
      return null;
   }

   public BodyTypeDeclaration getOrigConstructor() {
      return origConstructor;
   }

   /**
    * Stores the original type declaration for a dynamic type being constructed through a 'super(x)' call chain.
    * We pass through each of the super(x) calls which are in dynamic types until we reach a constructor with
    * no super call, or we hit a compiled class.   Then we construct an instance of a dynamic type with the
    * "origConstructor" set to the type.  Then we unwind and actually run all of the constructors from the
    * inside out.
    * */
   public void setOrigConstructor(BodyTypeDeclaration origConstructor) {
      this.origConstructor = origConstructor;
   }

   public BodyTypeDeclaration getPendingConstructor() {
      return pendingConstructor;
   }

   /** Stores the most recent TypeDeclaration on the chain of "super(xxx)" calls that we make to construct a type. */
   public void setPendingConstructor(BodyTypeDeclaration pendingConstructor) {
      if (pendingConstructor != null && origConstructor == null)
         origConstructor = pendingConstructor;
      this.pendingConstructor = pendingConstructor;
   }

   public void setSkipCompiledSuper(boolean v) {
       skipCompiledSuper = v;
   }

   public boolean getSkipCompiledSuper() {
      return skipCompiledSuper;
   }

   /**
    * In Java, you can refer to a "this" construct up on the stack: "EnclosingType.this".  When you construct
    * an inner class, similarly it fills in the wrapping outer type automatically.  This method implements
    * that lookup.
    */
   public Object findThisType(Object enclType) {
      Object thisObj;
      /*
      for (int i = currentObjects.size()-1; i >= 0; i--) {
         thisObj = currentObjects.get(i);
         if (ModelUtil.isAssignableFrom(enclType, DynUtil.getType(thisObj))) {
            return thisObj;
         }
      }
      */
      thisObj = getCurrentObject();
      if (thisObj == null)
         return null;
      Object pType = DynUtil.getType(thisObj);

      while (pType != null && !ModelUtil.isAssignableFrom(enclType, pType)) {
         Object outerObj = null;
         if (system != null)
            outerObj = system.getOuterInstance(thisObj);
         if (outerObj == null)
            outerObj = DynObject.getParentInstance(thisObj);
         thisObj = outerObj;

         if (thisObj == null)
            break;

         // Pull the current type off the object, do not use the enclosing type as it might not match the instance's type hierarchy.  You can extend an inner class at a different level of the hierarchy of the same outer class.
         pType = DynUtil.getType(thisObj);
         // Get the most specific type for this type, in case it was modified etc.
         pType = ModelUtil.resolve(pType, true);
      }
      if (thisObj == null) {
         System.err.println("Unable to resolve 'this' object referring to type: " + enclType + " context includes: " + currentObjTypesToString());
      }
      return thisObj;
   }

   private String currentObjTypesToString() {
      StringBuilder sb = new StringBuilder();
      if (currentObjects.size() == 0)
         sb.append("no 'this' object");
      else {
         int statIx = 0;
         for (int i = 0; i < currentObjects.size(); i++) {
            if (i != 0)
               sb.append(", ");
            Object t = currentObjects.get(0);
            if (t != null)
               sb.append(DynUtil.getType(t));
            else {
               if (statIx < frames.size())
                  sb.append("static: " + frames.get(statIx++).staticType);
               else
                  sb.append("<null frame>");
            }
         }
      }
      return sb.toString();
   }

   static class Frame extends CoalescedHashMap<String,Object> {
      boolean methodFrame;  // Block statements define "visible" frames which can see variables above
      Object staticType;
      Frame(boolean isMethod, int size, Object staticType) {
         super(size);
         this.methodFrame = isMethod;
         this.staticType = staticType;
      }
   }

   public Object resolveUnboundName(String name) {
      Object res = getVariable(name, false, false);
      if (res != null)
         return res;
      Object currentObject = getCurrentObject();
      if (currentObject != null) {
         try {
            IBeanMapper mapper = PTypeUtil.getPropertyMapping(currentObject.getClass(), name);
            if (mapper != null)
               return mapper.getPropertyValue(currentObject, false, false);
         }
         catch (IllegalArgumentException exc) {}
         if (res != null)
            return res;
      }
      return resolveName(name, true);
   }

   public Object resolveName(String name, boolean returnTypes) {
      if (resolver != null)
         return resolver.resolveName(name, createObjects, returnTypes);
      if (system != null)
         return system.resolveName(name, createObjects, returnTypes);
      return null;
   }

   public int getFrameSize() {
      return frames == null ? 0 : frames.size();
   }


   public boolean allowCreate(Object type) {
      return true;
   }

   public boolean allowSetProperty(Object type, String propName) {
      return true;
   }

   public boolean allowInvoke(Object method) {
      return true;
   }

   // Hook point for remote method invocation - during deserialization of a remote stream, we by default queue up property-changes
   // because they all essentially happened at the same time from the server's perspective.   This hook is here so that we can
   // flush the data binding events before and after the remote method call to make sure it runs with an appropriate context.
   public void postInvoke() {}
}
