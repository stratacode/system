/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.dyn;

import sc.bind.BindSettings;
import sc.type.MethodBindSettings;
import sc.type.RTypeUtil;
import sc.bind.IBinding;
import sc.bind.MethodBinding;
import sc.type.PTypeUtil;
import sc.type.ReverseBindingImpl;

/**
 * This class is for public dynamic utilities that are not used by ordinary runtime code, except when reflection is
 * present.  In the GWT version, none of these methods are linked into the client.   PTypeUtil may call into this
 * class but only in the PTypeUtil which uses reflection, for the JRE.
 */
public class RDynUtil {
   public static IRDynamicSystem dynamicSystem;
   public static Object[] getMethods(Object type, String methodName) {
      if (type instanceof Class)
         return RTypeUtil.getMethods((Class) type, methodName);
      else if (dynamicSystem != null)
         return dynamicSystem.getMethods(type, methodName);
      else
         throw new IllegalArgumentException("Unrecognized type to getMethods: " + type);
   }

   public static Object[] getAllMethods(Object typeObj, String modifier, boolean hasModifier) {
      if (dynamicSystem != null) {
         return dynamicSystem.getAllMethods(typeObj, modifier, hasModifier);
      }
      else if (typeObj instanceof Class)
         return RTypeUtil.getMethods((Class) typeObj, modifier);
      else
         throw new IllegalArgumentException("Invalid dynamic type: " + typeObj);
   }


   public static Object getAnnotation(Object def, Class annotClass) {
      if (dynamicSystem != null)
         return dynamicSystem.getAnnotation(def, annotClass);
      else
         return RTypeUtil.getAnnotation(def, annotClass);
   }

   public static Object getDeclaringClass(Object method) {
      if (dynamicSystem != null)
         return dynamicSystem.getDeclaringClass(method);
      else
         return RTypeUtil.getDeclaringClass(method);
   }

   public static boolean isInstance(Object pt, Object argValue) {
      if (pt instanceof Class)
         return RTypeUtil.isInstance((Class) pt, argValue);
      else if (dynamicSystem != null)
         return dynamicSystem.isInstance(pt, argValue);
      else
         throw new IllegalArgumentException("Invalid dynamic type: " + pt);
   }

   public static Object getAnnotationValue(Object settings, String s) {
      if (settings instanceof java.lang.annotation.Annotation)
         return RTypeUtil.getAnnotationValue((java.lang.annotation.Annotation) settings, s);
      else if (dynamicSystem != null)
         return dynamicSystem.getAnnotationValue(settings, s);
      else
         throw new IllegalArgumentException("Invalid dynamic type: " + settings);
   }

   /**
    * Returns the enclosing type of the supplied.  If instOnly=true, only return the enclosing type if the
    * inner type is not a static type.
    */
   public static Object getEnclosingType(Object memberType, boolean instOnly) {
      if (dynamicSystem == null) {
         if (instOnly && PTypeUtil.hasModifier(memberType, "static"))
            return null;
         return RTypeUtil.getPropertyDeclaringClass(memberType);
      }
      else
         return dynamicSystem.getEnclosingType(memberType, instOnly);
   }

   public static MethodBindSettings getMethodBindSettings(Object method) {
      MethodBindSettings mbs = null;
      Object settings = RDynUtil.getAnnotation(method, BindSettings.class);
      String reverseMethodName;
      if (settings != null) {
         mbs = new MethodBindSettings();
         Integer reverseSlotObj;
         int reverseSlot = 0;
         if ((reverseSlotObj = (Integer) RDynUtil.getAnnotationValue(settings, "reverseSlot")) != null) {
            mbs.reverseSlot = reverseSlotObj;
            if (mbs.reverseSlot != -1)
               reverseSlot = mbs.reverseSlot;
         }
         if ((reverseMethodName = (String) RDynUtil.getAnnotationValue(settings, "reverseMethod")).length() > 0) {
            Object[] methods = RDynUtil.getMethods(RDynUtil.getDeclaringClass(method), reverseMethodName);
            if (methods == null) {
               System.err.println("*** Method: " + method + " BindSettings.reverseMethod annotation refers to a non-existent method: " + reverseMethodName);
               return null;
            }
            Object returnType = DynUtil.getReturnType(method);
            for (Object invMeth:methods) {
               Object[] ptypes = DynUtil.getParameterTypes(invMeth);
               if (ptypes.length > reverseSlot) {
                  if (DynUtil.isAssignableFrom(ptypes[reverseSlot], returnType)) {
                     mbs.reverseMethod = invMeth;
                     mbs.oneParamReverse = ptypes.length == 1;
                  }
               }
            }
            if (mbs.reverseMethod == null)
               System.err.println("*** Method: " + method + " BindSettings.reverseMethod: " + reverseMethodName + " needs a signature like: (" + returnType + ", ...) - where BindSettings.reverseSlot specifies the index of the inverse value, the rest are the input parameters");
         }
         Integer forwardSlotObj;
         if ((forwardSlotObj = (Integer) RDynUtil.getAnnotationValue(settings, "forwardSlot")) != null) {
            mbs.forwardSlot = forwardSlotObj;
         }
         Boolean modifyParamObj;
         if ((modifyParamObj = (Boolean) RDynUtil.getAnnotationValue(settings, "modifyParam")) != null) {
            mbs.modifyParam = modifyParamObj;
         }
         mbs.reverseMethodStatic = DynUtil.hasModifier(method, "static");
      }
      return mbs;
   }

   public static ReverseBindingImpl initBindSettings(MethodBinding binding) {
      Object method = binding.getMethod();
      ReverseBindingImpl bs = new ReverseBindingImpl(binding, getMethodBindSettings(method));
      initReverseMethod(bs, method, binding.getBoundParams());
      return bs;
   }

   public static Class loadClass(String className) {
      if (DynUtil.dynamicSystem == null)
         return RTypeUtil.loadClass(className);
      else
         return DynUtil.dynamicSystem.loadClass(className);
   }

   private static void initReverseMethod(ReverseBindingImpl bs, Object method, IBinding[] boundParams) {
      if (bs.methBindSettings.reverseMethod == null)
         System.err.println("*** Method: " + method + " has no BindSettings(reverseMethod=..) annotation - invalid use in bi-directional (:=:) binding");
      if (bs.methBindSettings.reverseMethod != null) {
         for (int i = 0; i < boundParams.length; i++) {
            IBinding param = boundParams[i];
            if (!param.isConstant()) {
               // the first non-constant parameter is the inverse value.  The second and subsequent parameters can be
               // constants or expressions.  If there are no constant parameters, a new parameter is inserted at the
               // beginning to hold the reverse value.
               if (bs.methBindSettings.reverseSlot == -1)
                  bs.methBindSettings.reverseSlot = i;
            }
         }
         Object[] reverseParams = DynUtil.getParameterTypes(bs.methBindSettings.reverseMethod);
         int numReverseParams = reverseParams.length;
         // For one parameter forward methods, the first parameter is always the reverse parameter
         // But when there is one more param in the reverse parameters, we currently try to heuristically
         // figure out how to map the parameters looking at the return types.  Probably need a way maybe like:
         // BindSettings(reverseMethod="set(int reverseValue, Object value)")
         // using the special names to indicate which is the forward and which is the reverse.
         if (bs.methBindSettings.reverseSlot == -1 && boundParams.length + 1 == numReverseParams) {
            Object returnType = DynUtil.getReturnType(method);
            Object[] forwardParams = DynUtil.getParameterTypes(method);
            if (forwardParams == null || forwardParams.length == 0)
               bs.methBindSettings.reverseSlot = 0;
            else {
               for (int i = 0; i < reverseParams.length; i++) {
                  if (DynUtil.isAssignableFrom(returnType, reverseParams[i]) &&
                          !DynUtil.isAssignableFrom(forwardParams[i], reverseParams[i]))
                     bs.methBindSettings.reverseSlot = i;
               }
               if (bs.methBindSettings.reverseSlot == -1) {
                  System.err.println("*** Method binding can't find mapping to identify parameters to reverse method: " + bs.methBindSettings.reverseMethod + " for binding: " + bs.binding);
                  return;
               }
            }
            if (DynUtil.getReturnType(method) == null && forwardParams != null) {  // TODO: check for Void.class?
               int k = 0;
               if (bs.methBindSettings.forwardSlot == -1) {
                  for (int i = 0; i < reverseParams.length; i++) {
                     if (i != bs.methBindSettings.reverseSlot) {
                        if (DynUtil.isAssignableFrom(forwardParams[k], reverseParams[i])) {
                           bs.methBindSettings.forwardSlot = i;
                           break;
                        }
                        k++;
                     }
                  }
                  if (bs.methBindSettings.forwardSlot == -1)
                     System.err.println("*** Invalid reverse method signature: " + bs.methBindSettings.reverseMethod + " for: " + method + " no matching parameter type to use for forwardSlot");
               }
            }
         }
         if (numReverseParams != 1 && numReverseParams != boundParams.length + 1)
            System.err.println("*** Invalid reverse method signature: " + bs.methBindSettings.reverseMethod + " should have one parameter of type: " + DynUtil.getReturnType(method) + " or " + (boundParams.length + 1) + " parameters to match this call " + bs.binding);

         if (bs.methBindSettings.reverseSlot != -1 && bs.methBindSettings.modifyParam) {
            if (bs.methBindSettings.reverseSlot < 0 || bs.methBindSettings.reverseSlot >= boundParams.length)
               System.err.println("*** reverseSlot value: " + bs.methBindSettings.reverseSlot + " must be between 0 and: " + (boundParams.length-1));
            else
               bs.reverseParam = boundParams[bs.methBindSettings.reverseSlot];
         }
      }
   }

   public static void setSystemClassLoader(ClassLoader loader) {
      if (dynamicSystem == null)
         return;

      dynamicSystem.setSystemClassLoader(loader);
   }

}
