/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.BindSettings;
import sc.bind.Bindable;
import sc.bind.IChangeable;
import sc.classfile.CFClass;
import sc.classfile.CFMethod;
import sc.dyn.IDynObject;
import sc.lang.*;
import sc.lang.html.Attr;
import sc.lang.html.Element;
import sc.lang.sc.PropertyAssignment;
import sc.lang.sc.ModifyDeclaration;
import sc.lang.sc.OverrideAssignment;
import sc.lang.template.Template;
import sc.lang.template.TemplateStatement;
import sc.layer.*;
import sc.obj.GlobalScopeDefinition;
import sc.obj.IAltComponent;
import sc.obj.IComponent;
import sc.obj.ScopeDefinition;
import sc.type.*;
import sc.util.CoalescedHashMap;
import sc.util.PerfMon;
import sc.util.StringUtil;
import sc.bind.BindingDirection;
import sc.classfile.CFField;
import sc.dyn.DynUtil;
import sc.dyn.RDynUtil;
import sc.parser.*;

import java.io.File;
import java.io.StringReader;
import java.lang.reflect.*;
import java.lang.reflect.Type;
import java.util.*;

public class ModelUtil {
   public static final String CHILDREN_ANNOTATION = "Children";
   public static final String PARENT_ANNOTATION = "Parent";

   private ModelUtil() {}

   /**
    * Remap the Class back into either a type or the same class.
    * If we get a Class from reflection, we might have a more specific version, such as with an annotation layer 
    */
   private static Object mapClassToType(Class t, JavaModel model) {
      String name = t.getName();
      name = name.replace("$", ".");
      if (model == null)
         return t;
      return model.findTypeDeclaration(name, false);
   }

   public static Object getVariableTypeDeclaration(Object varObj, JavaModel model) {
      Object type = getVariableTypeDeclaration(varObj);
      if (type instanceof Class)
         return mapClassToType((Class) type, model);
      return type;
   }

   // TODO: this does not return consistent things for arrays... ClassType.getTypeDeclaration strips off the array
   public static Object getVariableTypeDeclaration(Object varObj) {
      if (varObj instanceof Field)
         return ((Field) varObj).getType();
      else if (varObj instanceof ITypedObject) 
         return ((ITypedObject) varObj).getTypeDeclaration();
      else if (varObj instanceof IBeanMapper)
         return ((IBeanMapper) varObj).getGenericType();
      else if (varObj instanceof ITypeDeclaration)
         return varObj;
      else if (varObj instanceof Class)
         return varObj;
      throw new UnsupportedOperationException();
   }

   public static Object getVariableGenericTypeDeclaration(Object varObj, JavaModel model) {
      if (varObj instanceof Field)
         return ((Field) varObj).getGenericType();
      else if (varObj instanceof ITypedObject)
         return ((ITypedObject) varObj).getTypeDeclaration();
      else if (varObj instanceof IBeanMapper)
         return ((IBeanMapper) varObj).getGenericType();
      else if (varObj instanceof ITypeDeclaration)
         return varObj;
      else if (varObj instanceof Class)
         return mapClassToType((Class) varObj, model);
      throw new UnsupportedOperationException();
   }

   public static Class getVariableClass(Object varObj) {
      return typeToClass(getVariableTypeDeclaration(varObj));
   }

   public static CoalescedHashMap initMethodTypeParameters(Object[] typeParameters, Object[] genericParamTypes, List<Expression> arguments) {
      if (typeParameters == null)
         return new CoalescedHashMap(0);

      CoalescedHashMap paramMap = new CoalescedHashMap(typeParameters.length);
      for (Object tp:typeParameters) {
         Object boundType = ModelUtil.getTypeParameterDefault(tp);
         if (boundType != Object.class)
            paramMap.put(ModelUtil.getTypeParameterName(tp), boundType);
      }

      int argSize = arguments.size();

      for (int i = 0; i < genericParamTypes.length; i++) {
         Object genParam = genericParamTypes[i];

         boolean isGenArray = ModelUtil.isGenericArray(genParam);

         Object argType;
         if (i >= argSize) {
            break;
         }
         else {
            Expression argExpr = arguments.get(i);
            argType = arguments.get(i).getGenericType();
         }
         if (argType == null)
            continue;

         boolean repeating = false;
         if (isGenArray && i == genericParamTypes.length - 1) {
            if (ModelUtil.isGenericArray(genParam) && !ModelUtil.isArray(argType)) {
               genParam = ModelUtil.getGenericComponentType(genParam);
               repeating = true;
            }
         }

         if (!repeating) {
            while (isGenArray) {
               genParam = ModelUtil.getGenericComponentType(genParam);
               argType = ModelUtil.getArrayComponentType(argType);
               if (argType == null)
                  break;
               isGenArray = ModelUtil.isGenericArray(genParam);
            }
         }

         if (ModelUtil.isTypeVariable(genParam)) {
            paramMap.put(getTypeParameterName(genParam), argType);
         }
         else if (ModelUtil.isParameterizedType(genParam)) {
            int numTypeParams = getNumTypeParameters(genParam);
            for (int atpIndex = 0; atpIndex < numTypeParams; atpIndex++) {
               Object atp = getTypeParameter(genParam, atpIndex);
               if (ModelUtil.isTypeVariable(atp)) {
                  String argParamName = ModelUtil.getTypeParameterName(atp);
                  Object curParamType = paramMap.get(argParamName);
                  if (curParamType == null)
                     ; // This happens sometimes - when we are not albe to map the parameter type.
                  else {
                     // Special case - Class<E> - e.g. <E extends Enum<E>> EnumSet<E> allOf(Class<E> elementType)
                     if (ModelUtil.getTypeName(genParam).equals("java.lang.Class") && DynUtil.isType(argType)) {
                        // assert curParamType.isAssignableFrom(atp)
                        paramMap.put(argParamName, argType = arguments.get(i).getGenericArgumentType());
                     }
                     else {
                        int paramPos = ModelUtil.getTypeParameterPosition(curParamType, argParamName);
                        if (hasTypeParameters(argType)) {
                           Object paramType = getTypeParameter(argType, paramPos);
                           paramMap.put(argParamName, paramType);
                        }
                     }
                  }
               }
            }
         }
      }

      return paramMap;
   }

   public static boolean isGenericArray(Object genArray) {
      return genArray instanceof GenericArrayType ||
            (genArray instanceof ArrayTypeDeclaration && ((ArrayTypeDeclaration) genArray).isTypeParameter());
   }

   public static Object getGenericComponentType(Object genParam) {
      if (genParam instanceof GenericArrayType)
         return ((GenericArrayType) genParam).getGenericComponentType();
      else if (genParam instanceof ArrayTypeDeclaration)
         return ((ArrayTypeDeclaration) genParam).componentType;
      else
         throw new UnsupportedOperationException();
   }

   public static Object getMethodTypeDeclaration(Object typeContext, Object varObj, List<Expression> arguments, JavaModel model) {
      Object type = getMethodTypeDeclarationInternal(typeContext, varObj, arguments, model);
      if (type instanceof Class)
         return mapClassToType((Class) type, model);
      return type;
   }

   private static Object getMethodTypeDeclarationInternal(Object typeContext, Object varObj, List<Expression> arguments, JavaModel model) {
      if (varObj == null)
         return null;
      if (varObj instanceof Method) {
         Method meth = (Method) varObj;
         TypeVariable<Method>[] tps;
         if ((tps = meth.getTypeParameters()) != null && tps.length > 0) {
            Type[] genParamTypes = meth.getGenericParameterTypes();

            Object genRetType = meth.getGenericReturnType();

            boolean isTypeVariable = ModelUtil.isTypeVariable(genRetType);

            // Refine the return type using the type parameters as a guide.  First we bind the
            // type parameters to the arguments supplied, then resolve the type and map that to our
            // return type.
            boolean isParamType = hasTypeParameters(genRetType);
            boolean isArrayType = isGenericArray(genRetType);
            if (isParamType || isArrayType || isTypeVariable) {
               CoalescedHashMap paramMap = initMethodTypeParameters(tps, genParamTypes, arguments);

               if (isTypeVariable) {
                  String retParamName = ModelUtil.getTypeParameterName(genRetType);
                  Object retParamType = paramMap.get(retParamName);
                  if (retParamType != null) {
                     genRetType = retParamType;
                     isParamType = hasTypeParameters(genRetType);
                  }
               }

               Object retType = genRetType;
               int ndim = 0;

               if (isArrayType) {
                  Object componentType = retType;
                  while (ModelUtil.isGenericArray(componentType)) {
                     componentType = ModelUtil.getGenericComponentType(genRetType);
                     ndim++;
                  }
                  retType = componentType;
               }

               if (isParamType) {
                  // Parameterized types: Match the names of the parameters with their values in the map
                  // e.g. public static <T> List<T> asList(T... a)

                  int ntps = getNumTypeParameters(retType);
                  List<Object> typeDefs = new ArrayList(ntps);
                  LayeredSystem sys = model.getLayeredSystem();
                  for (int i = 0; i < ntps; i++) {
                     Object typeParam = getTypeParameter(retType, i);
                     if (ModelUtil.isTypeVariable(typeParam)) {
                        String paramName = ModelUtil.getTypeParameterName(typeParam);
                        Object paramMappedType = paramMap.get(paramName);

                        Object argMappedType = getTypeDeclFromType(typeContext, typeParam, false, sys);
                        if (paramMappedType == null) {
                           if (argMappedType != null && !ModelUtil.isTypeVariable(argMappedType)) {
                             // assert paramMappedType != null && paramMappedType.isAssignableFrom(argMappedType)
                             paramMappedType = argMappedType;
                             paramMap.put(paramName, argMappedType);
                           }
                        }
                        typeDefs.add(paramMappedType);
                     }
                     else
                        typeDefs.add(getTypeDeclFromType(typeContext, typeParam, false, sys));
                  }
                  return new ParamTypeDeclaration(sys,
                                    getTypeParameters(getTypeDeclFromType(typeContext, retType, true, sys)),
                                    typeDefs, ModelUtil.getParamTypeBaseType(retType));
               }

               if (retType != null && isTypeVariable(retType)) {
                  Object paramType = paramMap.get(ModelUtil.getTypeParameterName(retType));
                  if (ndim == 0)
                     return paramType;
                  return new ArrayTypeDeclaration(model.getModelTypeDeclaration(), paramType, StringUtil.repeat("[]", ndim));
               }
            }
         }
         return getTypeDeclFromType(typeContext, meth.getGenericReturnType(), false, model.getLayeredSystem());
      }
      else if (varObj instanceof IMethodDefinition)
         return getTypeDeclFromType(typeContext, ((IMethodDefinition) varObj).getTypeDeclaration(arguments), false, model.getLayeredSystem());
      // MethodDefinition implements ITypedObject - don't reorder
      else  if (varObj instanceof ITypedObject)
         return ((ITypedObject) varObj).getTypeDeclaration();
      else if (varObj instanceof IBeanMapper)
         return getTypeDeclFromType(typeContext, ((IBeanMapper) varObj).getGenericType(), false, model.getLayeredSystem());
      throw new UnsupportedOperationException();
   }

   public static Object getTypeDeclFromType(Object typeContext, Object type, boolean classOnly, LayeredSystem sys) {
      if (type instanceof Class)
         return type;
      else if (ModelUtil.hasTypeParameters(type)) {
         if (classOnly)
            return getTypeDeclFromType(typeContext, ModelUtil.getParamTypeBaseType(type), true, sys);
         else {
            int numTypeParameters = ModelUtil.getNumTypeParameters(type);
            Object[] types = new Object[numTypeParameters];
            for (int i = 0; i < numTypeParameters; i++) {
               types[i] = getTypeDeclFromType(typeContext, ModelUtil.getTypeParameter(type, i), classOnly, sys);
               if (types[i] instanceof TypeVariable) {
                  types[i] = getTypeParameterDefault(types[i]);
                  //types[i] = getTypeDeclFromType(typeContext, ModelUtil.getTypeParameter(type, i), classOnly, sys);
               }
            }
            return new ParamTypeDeclaration(sys, getTypeParameters(getTypeDeclFromType(typeContext, type, true, sys)), Arrays.asList(types), ModelUtil.getParamTypeBaseType(type));
         }
      }
      else if (type instanceof GenericArrayType) {
         int ndim = 1;
         GenericArrayType gat = (GenericArrayType) type;
         Type compType;
         while ((compType = gat.getGenericComponentType()) instanceof GenericArrayType) {
            ndim++;
            gat = (GenericArrayType) gat.getGenericComponentType();
         }
         int[] dims = new int[ndim];
         Object compTypeDecl = getTypeDeclFromType(typeContext, compType, true, sys);
         if (ModelUtil.isTypeVariable(compTypeDecl))
            compTypeDecl = ModelUtil.getTypeParameterDefault(compTypeDecl);
         if (compTypeDecl instanceof ParamTypeDeclaration)
            return ArrayTypeDeclaration.create(compTypeDecl, ndim, (ITypeDeclaration) ModelUtil.getEnclosingType(typeContext));
         return Array.newInstance((Class) compTypeDecl, dims).getClass();
      }
      else if (ModelUtil.isTypeVariable(type)) {
         String tvName = ModelUtil.getTypeParameterName(type);
         if (typeContext != null) {
            Object genericType = getParameterizedType(typeContext, JavaSemanticNode.MemberType.Field);
            if (genericType != null) {
               if (ModelUtil.hasTypeParameters(genericType)) {

                  int numTypeParams = ModelUtil.getNumTypeParameters(genericType);
                  int pix;
                  Object typeParam = null;
                  for (pix = 0; pix < numTypeParams; pix++) {
                     typeParam = ModelUtil.getTypeVariable(genericType, pix);
                     if (ModelUtil.isTypeVariable(typeParam) && ModelUtil.getTypeParameterName(typeParam).equals(tvName))
                        break;
                  }


                  if (pix != numTypeParams)
                     return ModelUtil.getTypeParameter(genericType, pix);
                  // else - in some cases, the type variable is just not bound, like when we are inside of a parameterized class.
               }
               else if (genericType instanceof ParamTypeDeclaration) {
                  ParamTypeDeclaration ptd = (ParamTypeDeclaration) genericType;
                  return ptd.getTypeDeclarationForParam(ModelUtil.getTypeParameterName(type));
               }
            }
         }

         return type; // Do not return the default - need to signal that this is an unbound type parameter - i.e. do not print an error if it does not match - e.g. <T> Collections.emptyList()

      }
      else if (type instanceof ITypeDeclaration)
         return type;
      else if (type instanceof WildcardType) {
         // TODO: need to figure out how to really deal with this
         return getTypeDeclFromType(typeContext, ((WildcardType) type).getUpperBounds()[0], classOnly, sys);
      }
      if (type == null)
         return null;
      else
         throw new UnsupportedOperationException();
   }

   public static Object[] getAllMethods(Object type, String modifier, boolean hasModifier, boolean isDyn, boolean overridesComp) {
      if (type instanceof Class) {
         if (isDyn || overridesComp)
            return null;
         return RTypeUtil.getMethodsWithModifier((Class) type, modifier, hasModifier);
      }
      else if (type instanceof ITypeDeclaration) {
         List<Object> methods = ((ITypeDeclaration) type).getAllMethods(modifier, hasModifier, isDyn, overridesComp);
         return methods == null ? null : methods.toArray(new Object[methods.size()]);
      }
      else
         return null;
   }

   public static Object[] getMethods(Object type, String methodName, String modifier) {
      return getMethods(type, methodName, modifier, true);
   }

   public static Object[] getMethods(Object type, String methodName, String modifier, boolean includeExtends) {
      if (type instanceof Class)
         return RTypeUtil.getMethods((Class) type, methodName, modifier);
      else if (type instanceof ITypeDeclaration) {
         List<Object> methods = ((ITypeDeclaration) type).getMethods(methodName, modifier, includeExtends);
         return methods == null ? null : methods.toArray(new Object[methods.size()]);
      }
      else
         throw new UnsupportedOperationException();
   }

   public static boolean overridesMethod(Object subTypeMethod, Object superTypeMethod) {
      String subName = ModelUtil.getMethodName(subTypeMethod);
      String superName = ModelUtil.getMethodName(superTypeMethod);
      if (!StringUtil.equalStrings(subName, superName))
         return false;

      Object[] subParamTypes = ModelUtil.getParameterTypes(subTypeMethod);
      Object[] superParamTypes = ModelUtil.getParameterTypes(superTypeMethod);

      int numSub = subParamTypes == null ? 0 : subParamTypes.length;
      int numSuper = superParamTypes == null ? 0 : superParamTypes.length;
      // null-null
      if (numSub != numSuper)
         return false;

      for (int i = 0; i < numSub; i++) {
         if (!isAssignableTypesFromOverride(superParamTypes[i], subParamTypes[i]))
            return false;
      }
      return true;
   }

   public static Object getTypeDeclarationFromJavaType(Object javaType) {
      if (javaType instanceof java.lang.reflect.Type)
         return typeToClass(javaType);
      else if (javaType instanceof ITypedObject)
         return ((ITypedObject) javaType).getTypeDeclaration();
      throw new UnsupportedOperationException();
   }

   public static Class[] typeToClassArray(JavaType[] types) {
      if (types == null || types.length == 0)
         return null;

      Class[] cls = new Class[types.length];
      for (int i = 0; i < cls.length; i++)
         cls[i] = types[i].getRuntimeClass();

      return cls;
   }

   public static Object getSetMethodPropertyType(Object setMethod, JavaModel model) {
      Object type = getSetMethodPropertyType(setMethod);
      if (type instanceof Class)
         return mapClassToType((Class) type, model);
      return type;
   }

   public static Object getSetMethodPropertyType(Object setMethod) {
      if (setMethod instanceof IMethodDefinition) {
         Object[] paramTypes = ((IMethodDefinition) setMethod).getParameterTypes();
         if (paramTypes == null || paramTypes.length == 0)
            throw new IllegalArgumentException("Set method without any parameters: " + setMethod);
         if (paramTypes.length != 1) {
            // Indexed setter?
            if (paramTypes.length == 2 && isInteger(paramTypes[0]))
               return paramTypes[1];
            throw new IllegalArgumentException("Set method with too many parameters: " + setMethod);
         }
         return paramTypes[0];
      }
      else if (setMethod instanceof IBeanMapper)
         return ((IBeanMapper) setMethod).getPropertyType();
      else if (setMethod instanceof Method) {
         Class[] parameterTypes = ((Method) setMethod).getParameterTypes();
         if (parameterTypes.length != 1)
            throw new IllegalArgumentException("Set method with wrong number of parameters: " + setMethod);
         return parameterTypes[0];
      }
      else if (setMethod instanceof ParamTypedMember) {
         return getSetMethodPropertyType(((ParamTypedMember) setMethod).getMemberObject());
      }
      else throw new UnsupportedOperationException();
   }

   public static Object getMethodDeclaringClass(Object methodObj) {
      if (methodObj instanceof Method)
         return ((Method) methodObj).getDeclaringClass();
      else if (methodObj instanceof IMethodDefinition)
         return ((IMethodDefinition) methodObj).getDeclaringType();
      else
         throw new UnsupportedOperationException();
   }

   public static Object getMethod(Object resultClass, String methodName, Object... types) {
      return getMethod(resultClass, methodName, null, null, types);
   }

   public static boolean checkAccess(Object refType, Object member) {
      return checkAccess(refType, member, null);
   }

   public static boolean checkAccessList(Object refType, Object member, EnumSet<JavaSemanticNode.MemberType> mtypes) {
      for (JavaSemanticNode.MemberType mtype:mtypes)
         if (checkAccess(refType, member, mtype))
            return true;
      return false;
   }

   public static boolean checkAccess(Object refType, Object member, JavaSemanticNode.MemberType mtype) {
      AccessLevel memberLevel = getAccessLevel(member, false, mtype);
      if (memberLevel == null) {
         Object encType = getEnclosingType(member);
         if (ModelUtil.isInterface(encType))
            return true;
         // TODO: this happens when the member is resolved as a top-level reference property.  maybe check the access level of the type?
         if (encType == null)
            return true;
         return ModelUtil.samePackage(refType, encType);
      }
      switch (memberLevel) {
         case Public:
            return true;
         case Private:
            return ModelUtil.sameModel(refType, getEnclosingType(member));
         // Protected works for either the same package or a subclass
         case Protected:
            Object encType = getEnclosingType(member);
            boolean val = ModelUtil.isAssignableFrom(encType, refType);
            if (val)
               return true;
            // FALL THROUGH if false
            return ModelUtil.samePackage(refType, encType);
         default:
            throw new UnsupportedOperationException();
      }
   }

   public static boolean samePackage(Object type1, Object type2) {
      if (type1 == type2)
         return true;
      String package1 = getPackageName(type1);
      String package2 = getPackageName(type2);
      return StringUtil.equalStrings(package1, package2);
   }

   public static String getPackageName(Object type) {
      if (type instanceof Class) {
         java.lang.Package pkg = ((Class) type).getPackage();
         if (pkg == null)
            return null;
         return pkg.getName();
      }
      else if (type instanceof ITypeDeclaration) {
         return CTypeUtil.getPackageName(ModelUtil.getTypeName(getRootType(type)));
      }
      else
         throw new UnsupportedOperationException();
   }

   public static boolean sameModel(Object type1, Object type2) {
      if (type1 == type2)
         return true;
      Object parent;
      do {
         parent = ModelUtil.getEnclosingType(type1);
         if (parent != null)
            type1 = parent;
      } while (parent != null);

      do {
         parent = ModelUtil.getEnclosingType(type2);
         if (parent != null)
            type2 = parent;
      } while (parent != null);

      if (type1 == type2)
         return true;
      // TODO: speed this up?
      return getTypeName(type1).equals(getTypeName(type2));
   }

   public static boolean isVarArgs(Object method) {
      if (method instanceof Method)
         return ((Method) method).isVarArgs();
      else if (method instanceof Constructor)
         return ((Constructor) method).isVarArgs();
      else if (method instanceof IMethodDefinition)
         return ((IMethodDefinition) method).isVarArgs();
      else
         throw new UnsupportedOperationException();
   }

   /**
    * Uses a more flexible comparison for the arguments.
    */
   public static Object getMethod(Object resultClass, String methodName, Object refType, ITypeParamContext ctx, Object... types) {
      Object[] list = ModelUtil.getMethods(resultClass, methodName, null);

      if (list == null) {
         // Interfaces don't inherit object methods in Java but an inteface type in this system needs to still
         // implement methods like "toString" even if they are not on the interface.
         if (ModelUtil.isInterface(resultClass)) {
            return getMethod(Object.class, methodName, refType, null, types);
         }
         return null;
      }

      int typesLen = types == null ? 0 : types.length;
      Object res = null;
      for (int i = 0; i < list.length; i++) {
         Object toCheck = list[i];
         if (ModelUtil.getMethodName(toCheck).equals(methodName)) {
            Object[] parameterTypes = ModelUtil.getParameterTypes(toCheck);
            int paramLen = parameterTypes == null ? 0 : parameterTypes.length;
            if (paramLen == 0 && typesLen == 0) {
               if (refType == null || checkAccess(refType, toCheck))
                  res = ModelUtil.pickMoreSpecificMethod(res, toCheck, null);
            }
            else {
               int j;
               int last = paramLen - 1;
               if (paramLen != typesLen) {
                  // If the last guy is not a repeating parameter, it can't match
                  if (last < 0 || !ModelUtil.isVarArgs(toCheck) || !ModelUtil.isArray(parameterTypes[last]) || typesLen < last)
                     continue;
               }
               for (j = 0; j < typesLen; j++) {
                  Object paramType;
                  if (j > last) {
                     if (!ModelUtil.isArray(paramType = parameterTypes[last]))
                        break;
                  }
                  else
                     paramType = parameterTypes[j];
                  if (types[j] != null && !ModelUtil.isAssignableFrom(paramType, types[j], false, ctx)) {
                     // Repeating parameters... if the last parameter is an array match if the component type matches
                     if (j >= last && ModelUtil.isArray(paramType)) {
                        if (!ModelUtil.isAssignableFrom(ModelUtil.getArrayComponentType(paramType), types[j], false, ctx)) {
                           break;
                        }
                     }
                     else
                        break;
                  }
               }
               if (j == typesLen) {
                  if (refType == null || checkAccess(refType, toCheck))
                     res = ModelUtil.pickMoreSpecificMethod(res, toCheck, types);
               }
            }
         }
      }
      return res;
   }

   public static Object getMethodFromSignature(Object type, String methodName, String paramSig) {
      if (type instanceof BodyTypeDeclaration)
         return ((TypeDeclaration) type).getMethodFromSignature(methodName, paramSig);
      else if (type instanceof Class)
         return PTypeUtil.resolveMethod((Class) type, methodName, paramSig);
      else if (type instanceof DynType)
         return ((DynType) type).getMethod(methodName, paramSig);
      else if (type instanceof String) {
         String typeName = (String) type;
         Object resolvedType = LayeredSystem.getCurrent().getTypeDeclaration(typeName);
         if (resolvedType == null)
            throw new IllegalArgumentException("No type named: " + typeName);
         return getMethodFromSignature(resolvedType, methodName, paramSig);
      }
      else
         throw new UnsupportedOperationException();
   }

   public static boolean methodNamesMatch(Object c1, Object c2) {
      return ModelUtil.getMethodName(c1).equals(ModelUtil.getMethodName(c2));
   }

   public static boolean methodsMatch(Object c1, Object c2) {
      if (c1 == null)
         return c2 == null;

      if (c2 == null)
         return false;

      Object[] c1Types = ModelUtil.getParameterTypes(c1);
      Object[] c2Types = ModelUtil.getParameterTypes(c2);
      int c1Len = c1Types == null ? 0 : c1Types.length;
      int checkLen = c2Types == null ? 0 : c2Types.length;
      if (c1Len != checkLen)
         return false;

      if (c1Types != null) {
         for (int i = 0; i < c1Types.length; i++) {
            Object c1Arg = c1Types[i];
            Object c2Arg = c2Types[i];

            if (c1Arg == c2Arg)
               continue;

            if (sameTypes(c1Arg, c1Arg))
               continue;

            if (ModelUtil.isAssignableFrom(c1Arg, c2Arg))
               continue;

            return false;
         }
      }
      return true;
   }

   /** Chooses the method with the more specific return type - as per the searchMethods method in java.lang.Class */
   public static Object pickMoreSpecificMethod(Object c1, Object c2, Object[] types) {
      if (c1 == null)
         return c2;

      // First an exact match of parameter types overrides - e.g. Math.abs(int)
      if (types != null) {
         Object[] c1Types = ModelUtil.getParameterTypes(c1);
         Object[] c2Types = ModelUtil.getParameterTypes(c2);
         int c1Len = c1Types == null ? 0 : c1Types.length;
         int checkLen = c2Types == null ? 0 : c2Types.length;
         if (c1Len != checkLen)
            return c1Len > checkLen ? c2 : c1;
         for (int i = 0; i < types.length; i++) {
            Object arg = types[i];
            Object c1Arg = c1Types[i];
            Object c2Arg = c2Types[i];

            if (c1Arg == c2Arg)
               continue;

            if (sameTypes(arg, c1Arg))
               return c1;

            if (sameTypes(arg, c2Arg))
               return c2;

            if (ModelUtil.isAssignableFrom(c1Arg, c2Arg))
               return c2;
            //else if (ModelUtil.isAssignableFrom(c2Arg, c1Arg))

            boolean argIsArray = ModelUtil.isArray(arg);
            if (ModelUtil.isArray(c1Arg) && !ModelUtil.isArray(c2Arg)) {
               if (argIsArray)
                  return c1;
               // If we have c1 as a "..." signifier and c2 is not, match the one which is not
               return c2;
            }
            // If the arg is an array pick the array type
            else if (argIsArray && ModelUtil.isArray(c2Arg) && !ModelUtil.isArray(c1Arg))
               return c2;
            return c1;
         }
      }

      // Preference to methods in the type rather than in the interface
      if (ModelUtil.isInterface(ModelUtil.getEnclosingType(c1)) && !ModelUtil.isInterface(ModelUtil.getEnclosingType(c2)))
         return c2;

      if (ModelUtil.hasModifier(c1, "abstract"))
         return c2;

      if (ModelUtil.isAssignableFrom(ModelUtil.getReturnType(c2), ModelUtil.getReturnType(c1)))
         return c1;
      return c1;
   }

   /** isSameType might be a better name? */
   public static boolean sameTypes(Object typeObj1, Object typeObj2) {
      if (typeObj1 == typeObj2)
         return true;

      if (isInteger(typeObj1) && isInteger(typeObj2))
         return true;

      if (isLong(typeObj1) && isLong(typeObj2))
         return true;

      if (isShort(typeObj1) && isShort(typeObj2))
         return true;

      if (isByte(typeObj1) && isByte(typeObj2))
         return true;

      if (isDouble(typeObj1) && isDouble(typeObj2))
         return true;

      if (isFloat(typeObj1) && isFloat(typeObj2))
         return true;

      if (isBoolean(typeObj1) && isBoolean(typeObj2))
         return true;

      if (isCharacter(typeObj1) && isCharacter(typeObj2))
         return true;

      if (typeObj1 == null || typeObj2 == null)
         return false;

      // Need to include the dims at least to differentiate Object[] from Object
      return ModelUtil.getTypeName(typeObj1, true).equals(ModelUtil.getTypeName(typeObj2, true));
   }

   public static boolean isBoolean(Object type) {
      if (type instanceof PrimitiveType) {
         type = ((PrimitiveType) type).getRuntimeClass();
      }
      if (type instanceof Class) {
         sc.type.Type t = sc.type.Type.get((Class) type);
         return t == sc.type.Type.Boolean;
      }
      if (type instanceof CFClass) {
         return ((CFClass) type).isBoolean();
      }
      if (type instanceof ModifyDeclaration && isBoolean(((ModifyDeclaration) type).getDerivedTypeDeclaration()))
         return true;
      return false;
   }

   public static boolean isCharacter(Object type) {
      if (type instanceof PrimitiveType) {
         type = ((PrimitiveType) type).getRuntimeClass();
      }
      if (type instanceof Class) {
         sc.type.Type t = sc.type.Type.get((Class) type);
         return t == sc.type.Type.Character;
      }
      if (type instanceof CFClass) {
         return ((CFClass) type).isCharacter();
      }
      if (type instanceof ModifyDeclaration && isCharacter(((ModifyDeclaration) type).getDerivedTypeDeclaration()))
         return true;

      return false;
   }

   public static boolean isPrimitive(Object type) {
      if (type instanceof PrimitiveType) {
         return true;
      }
      if (type instanceof Class) {
         sc.type.Type t = sc.type.Type.get((Class) type);
         return t.primitiveClass == type;
      }
      return false;
   }

   public static boolean isPrimitiveNumberType(Object type) {
      if (type instanceof PrimitiveType) {
         type = ((PrimitiveType) type).getRuntimeClass();
      }
      if (type instanceof Class) {
         sc.type.Type t = sc.type.Type.get((Class) type);
         return t.primitiveClass == type && t.isANumber();
      }
      return false;
   }

   public static boolean isANumber(Object type) {
      if (type instanceof PrimitiveType)
         type = ((PrimitiveType) type).getRuntimeClass();
      if (type instanceof Class) {
         if (type == Number.class)
            return true;
         return sc.type.Type.get((Class) type).isANumber();
      }
      if (isNumber(type))
         return true;
      else if (type instanceof ModifyDeclaration && isANumber(((ModifyDeclaration) type).getDerivedTypeDeclaration()))
         return true;
      else
         return false;
   }

   public static boolean isNumber(Object type) {
      // A better way to detect this?  I don't think Number can be redefined...
      if (type instanceof ClassType && ((ClassType) type).typeName.equals("Number"))
         return true;
      if (type instanceof CFClass && ((CFClass) type).isNumber())
         return true;
      return type == Number.class;
   }

   public static Object coerceTypes(Object lhsType, Object rhsType) {
      if (lhsType == rhsType)
         return lhsType;

      // When comparing against Null, always use the other guy
      if (lhsType == NullLiteral.NULL_TYPE)
         return rhsType;
      if (rhsType == NullLiteral.NULL_TYPE)
         return lhsType;

      if (isANumber(lhsType) && isANumber(rhsType))
         return coerceNumberTypes(lhsType, rhsType);

      if (ModelUtil.isAssignableFrom(lhsType, rhsType))
         return lhsType;
      else if (ModelUtil.isAssignableFrom(rhsType, lhsType))
         return rhsType;
      else {
         Object type = ModelUtil.findCommonSuperClass(lhsType, rhsType);
         if (type != null) {
            Object[] ifaces = ModelUtil.getOverlappingInterfaces(lhsType, rhsType);
            if (ifaces == null || ModelUtil.implementsInterfaces(type, ifaces))
               return type;
            // Weird case - need to build a new type which represents the common base type combined with the overlapping interfaces
            return new CoercedTypeDeclaration(type, ifaces);
         }
         throw new IllegalArgumentException("Cannot coerce types: " + ModelUtil.getTypeName(lhsType) + " and: " + ModelUtil.getTypeName(rhsType));
      }
   }

   public static boolean implementsInterfaces(Object type, Object[] ifaces) {
      for (int i = 0; i < ifaces.length; i++)
         if (!ModelUtil.isAssignableFrom(ifaces[i], type))
            return false;
      return true;
   }

   public static Object[] getAllImplementsTypeDeclarations(Object c1) {
      if (c1 instanceof Class)
         return ((Class) c1).getInterfaces();
      else if (c1 instanceof ITypeDeclaration) {
         return ((ITypeDeclaration) c1).getAllImplementsTypeDeclarations();
      }
      else
         throw new UnsupportedOperationException();
   }

   public static Object[] getImplementsTypeDeclarations(Object c1) {
      if (c1 instanceof Class)
         return ((Class) c1).getInterfaces();
      else if (c1 instanceof ITypeDeclaration) {
         return ((ITypeDeclaration) c1).getImplementsTypeDeclarations();
      }
      else
         throw new UnsupportedOperationException();
   }

   public static Object[] getOverlappingInterfaces(Object c1, Object c2) {
      Object[] if1 = ModelUtil.getAllImplementsTypeDeclarations(c1);
      Object[] if2 = ModelUtil.getAllImplementsTypeDeclarations(c2);
      if (if2 == null)
         return null;
      if (if1 == null)
         return null;
      ArrayList<Object> res = new ArrayList<Object>();
      for (int i = 0; i < if2.length; i++) {
         for (int j = 0; j < if1.length; j++) {
            if (if1[j] == if2[i]) {
               res.add(if1[j]);
               break;
            }
         }
      }
      return res.toArray();
   }

   public static Object findCommonSuperClass(Object c1, Object c2) {
      Object o1 = c1;
      Object o2 = c2;

      if (o1 == null && o2 != null)
         return o2;
      if (o2 == null && o1 != null)
         return o1;

      while (o1 != null && !ModelUtil.isAssignableFrom(o1, o2))
         o1 = ModelUtil.getSuperclass(o1);

      while (o2 != null && !ModelUtil.isAssignableFrom(o2, c1))
         o2 = ModelUtil.getSuperclass(o2);

      return o1 != null && o2 != null && ModelUtil.isAssignableFrom(o1, o2) ? o2 : o1;
   }

   // TODO: replace with getRuntimeClass
   public static Class typeToClass(Object type) {
      if (type instanceof Class)
         return (Class) type;
      else if (type instanceof ITypeDeclaration)
         return ((ITypeDeclaration) type).getCompiledClass();
      else
         return null;
   }

   /** Returns just the base part of the name, i.e. "List" for java.util.List.) */
   public static String getClassName(Object type) {
      if (type instanceof Class)
         return CTypeUtil.getClassName(TypeUtil.getTypeName((Class) type, false));
      else if (type instanceof ITypeDeclaration) {
         return ((ITypeDeclaration) type).getTypeName();
      }
      else
         throw new UnsupportedOperationException();
   }

   /** Returns the complete type name of the type with array dimensions, without type parameters */
   public static String getTypeName(Object type) {
      return getTypeName(type, true, false);
   }

   public static String getTypeName(Object type, boolean includeDims) {
      return getTypeName(type, includeDims, false);
   }

   public static String getTypeName(Object type, boolean includeDims, boolean includeTypeParams) {
      if (type == null)
         return "<missing type>";
      if (type instanceof Class) {
         String res = TypeUtil.getTypeName((Class) type, includeDims);
         return res;
      }
      else if (type instanceof ITypeDeclaration) {
         if (includeTypeParams)
            return ((ITypeDeclaration) type).getFullTypeName(includeDims, includeTypeParams);
         else if (includeDims)
            return ((ITypeDeclaration) type).getFullTypeName();
         else
            return ((ITypeDeclaration) type).getFullBaseTypeName();
      }
      else if (type instanceof ITypedObject)
         return getTypeName(((ITypedObject) type).getTypeDeclaration(), includeDims);
      else if (type instanceof IBeanMapper)
         return getTypeName(((IBeanMapper) type).getPropertyType(), includeDims);
      else if (type == NullLiteral.NULL_TYPE)
         return (String) type;
      else if (type instanceof ParameterizedType)
         return getTypeName(((ParameterizedType) type).getRawType(), includeDims);
      else if (ModelUtil.isTypeVariable(type))
         return ModelUtil.getTypeParameterName(type);
      throw new UnsupportedOperationException();
   }

   // TODO: Performance Fix - me - should be looking up a precedence value in each type and just comparing them
   public static Object coerceNumberTypes(Object lhsType, Object rhsType) {
      if (lhsType == rhsType)
           return lhsType;

      if (isDouble(lhsType))
           return lhsType;

      if (isDouble(rhsType))
           return rhsType;

      if (isFloat(lhsType))
           return lhsType;

      if (isFloat(rhsType))
           return rhsType;

      if (isLong(lhsType))
           return lhsType;

      if (isLong(rhsType))
           return rhsType;

      if (isInteger(lhsType))
           return lhsType;

      if (isInteger(rhsType))
           return rhsType;

      if (isShort(lhsType))
           return lhsType;

      if (isShort(rhsType))
           return rhsType;

      return rhsType;
   }

   public static boolean isArray(Object arrayType) {
      if (arrayType instanceof ParamTypeDeclaration)
         arrayType = ((ParamTypeDeclaration) arrayType).baseType;
      if (arrayType instanceof Class) {
         Class arrayClass = (Class) arrayType;
         return arrayClass.isArray();
      }
      else if (arrayType instanceof IArrayTypeDeclaration) {
         return true;
      }
      return false;
   }

   public static Object getArrayComponentType(Object arrayType) {
      if (arrayType instanceof ParamTypeDeclaration)
         arrayType = ((ParamTypeDeclaration) arrayType).baseType;
      if (arrayType instanceof Class) {
         Class arrayClass = (Class) arrayType;
         if (!arrayClass.isArray()) 
            return null;

         return arrayClass.getComponentType();
      }
      else if (arrayType instanceof IArrayTypeDeclaration) {
         return ((IArrayTypeDeclaration) arrayType).getComponentType();
      }
      return null;
   }

   public static boolean hasTypeParameters(Object paramType) {
      return paramType instanceof ParamTypeDeclaration || paramType instanceof ParameterizedType;
   }

   public static int getNumTypeParameters(Object paramType) {
      if (paramType instanceof ParamTypeDeclaration) {
         return ((ParamTypeDeclaration) paramType).types.size();
      }
      else if (paramType instanceof ParameterizedType) {
         ParameterizedType pt = (ParameterizedType) paramType;
         return pt.getActualTypeArguments().length;
      }
      else throw new UnsupportedOperationException();
   }

   public static Object getTypeVariable(Object paramType, int ix) {
      if (paramType instanceof ParamTypeDeclaration) {
         return ((ParamTypeDeclaration) paramType).typeParams.get(ix);
      }
      else if (paramType instanceof ParameterizedType) {
         ParameterizedType pt = (ParameterizedType) paramType;
         // Looking for type variables here - those are stored on the original class.
         return getTypeVariable(pt.getRawType(), ix);
         //return pt.getActualTypeArguments()[ix];
      }
      else if (paramType instanceof Class) {
         Class cl = (Class) paramType;
         return cl.getTypeParameters()[ix];
      }
      else throw new UnsupportedOperationException();
   }

   public static Object getTypeParameter(Object paramType, int ix) {
      if (paramType instanceof ParamTypeDeclaration) {
         return ((ParamTypeDeclaration) paramType).types.get(ix);
      }
      else if (paramType instanceof ParameterizedType) {
         ParameterizedType pt = (ParameterizedType) paramType;
         return pt.getActualTypeArguments()[ix];
      }
      else throw new UnsupportedOperationException();
   }

   public static Object getArrayOrListComponentType(Object arrayType) {
      if (ModelUtil.isAssignableFrom(List.class, arrayType)) {
         if (arrayType instanceof ParamTypeDeclaration) {
            return ((ParamTypeDeclaration) arrayType).types.get(0);
         }
         else if (arrayType instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) arrayType;
            return pt.getActualTypeArguments()[0];
         }
         return Object.class;
      }
      // for java.util.Map, we'll pull out the type parameter for the value
      else if (ModelUtil.isAssignableFrom(Map.class, arrayType)) {
         if (arrayType instanceof ParamTypeDeclaration) {
            ParamTypeDeclaration ptd = (ParamTypeDeclaration) arrayType;
            if (ptd.types.size() == 2)
               return ptd.types.get(1);
         }
         else if (arrayType instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) arrayType;
            Object[] typeArgs = pt.getActualTypeArguments();
            if (typeArgs.length == 2)
               return typeArgs[1];
         }
         return Object.class;
      }
      else
         return ModelUtil.getArrayComponentType(arrayType);
   }

   public static boolean isAssignableFrom(Object type1, Object type2) {
      return isAssignableFrom(type1, type2, false, null);
   }

   /** Use for comparing parameter types in methods to decide when one overrides the other */
   public static boolean isAssignableTypesFromOverride(Object from, Object to) {
      if (to == null)
         return true;

       return from == to || (from != null && ModelUtil.getTypeName(from).equals(ModelUtil.getTypeName(to)));
   }

   public static boolean isAssignableFrom(Object type1, Object type2, boolean assignmentSemantics, ITypeParamContext ctx) {
      if (type1 == type2)
         return true;

      if (type2 == NullLiteral.NULL_TYPE)
         return true;

      if (type1 instanceof TypeParameter) {
         return ((TypeParameter)type1).isAssignableFrom(type2, ctx);
      }
      if (type2 instanceof TypeParameter) {
         return ((TypeParameter)type2).isAssignableTo(type1, ctx);
      }

      if (type1 instanceof TypeVariable) {
         type1 = ((TypeVariable) type1).getBounds()[0];
      }

      if (type2 instanceof TypeVariable) {
         type2 = ((TypeVariable) type2).getBounds()[0];
      }

      if (isANumber(type1) && isANumber(type2))
         // Note: switching the order here to "lhs" = "rhs"
         return numberTypesAssignableFrom(type1, type2, assignmentSemantics);

      if (isBoolean(type1) && isBoolean(type2))
         return true;

      if (isCharacter(type1) && isCharacter(type2))
         return true;

      // Characters can be assigned to ints and vice versa during assignments only
      if (assignmentSemantics) {
         if (isCharacter(type1) && isAnInteger(type2) || (isCharacter(type2) && isAnInteger(type1)))
            return true;
      }
      else {
         // Method parameters - an integer can be assigned with a character argument
         if (isAnInteger(type1) && isCharacter(type2))
            return true;
      }

      while (type1 instanceof ParameterizedType)
         type1 = ((ParameterizedType) type1).getRawType();

      while (type2 instanceof ParameterizedType)
         type2 = ((ParameterizedType) type2).getRawType();

      if (type1 instanceof Class) {
         // Accepts double and other types assuming the unboxing rules in Java
         if (type1 == Object.class)
            return true;
         if (type2 instanceof ITypeDeclaration) {
            // In this case, we need to invert the direction of the test.  Let's see if our TypeDeclaration
            // implements a type with the same name.
            return ((ITypeDeclaration) type2).implementsType(((Class) type1).getName());
         }
         else if (type2 instanceof Class) {
            return ((Class) type1).isAssignableFrom((Class) type2);
         }
         else if (type2 == null)
            return false;
         else
            throw new UnsupportedOperationException();
      }
      else if (type1 instanceof ITypeDeclaration) {
         ITypeDeclaration decl1 = (ITypeDeclaration) type1;
         if (type2 instanceof Class) {
            return decl1.isAssignableFromClass((Class) type2);
         }
         if (!(type2 instanceof ITypeDeclaration))
            System.out.println("*** Error - invalid type to isAssignable method");
         ITypeDeclaration decl2 = (ITypeDeclaration) type2;
         return decl1.isAssignableFrom(decl2);
      }
      else if (type1 == null)
         return false;
      else
         throw new UnsupportedOperationException();
   }

   public static boolean isString(Object type) {
      return type == String.class || (type instanceof CFClass && ((CFClass) type).isString()) ||
              type instanceof PrimitiveType && ((PrimitiveType) type).getRuntimeClass() == String.class || (type instanceof BodyTypeDeclaration && ((BodyTypeDeclaration) type).getFullTypeName().equals("java.lang.String"));
   }

   public static boolean isDouble(Object type) {
      return type == Double.class || type == Double.TYPE || (type instanceof CFClass && ((CFClass) type).isDouble()) ||
             (type instanceof PrimitiveType && isDouble(((PrimitiveType) type).getRuntimeClass()));
   }

   public static boolean isFloat(Object type) {
      return type == Float.class || type == Float.TYPE || (type instanceof CFClass && ((CFClass) type).isFloat()) ||
             (type instanceof PrimitiveType && isFloat(((PrimitiveType) type).getRuntimeClass()));
   }

   public static boolean isLong(Object type) {
      return type == Long.class || type == Long.TYPE || (type instanceof CFClass && ((CFClass) type).isLong()) ||
             (type instanceof PrimitiveType && isLong(((PrimitiveType) type).getRuntimeClass()));
   }

   public static boolean isInteger(Object type) {
      return type == Integer.class || type == Integer.TYPE || (type instanceof CFClass && ((CFClass) type).isInteger()) ||
              (type instanceof PrimitiveType && isInteger(((PrimitiveType) type).getRuntimeClass()));
   }

   public static boolean isShort(Object type) {
      return type == Short.class || type == Short.TYPE || (type instanceof CFClass && ((CFClass) type).isShort()) ||
             (type instanceof PrimitiveType && isShort(((PrimitiveType) type).getRuntimeClass()));
   }

   public static boolean isByte(Object type) {
      return type == Byte.class || type == Byte.TYPE || (type instanceof CFClass && ((CFClass) type).isByte()) ||
             (type instanceof PrimitiveType && isByte(((PrimitiveType) type).getRuntimeClass()));
   }

   public static boolean isAnInteger(Object type) {
      return isInteger(type) || isShort(type) || isByte(type) || isLong(type);
   }

   private static boolean numberTypesAssignableFrom(Object lhsType, Object rhsType, boolean assignmentSemantics) {
      // Any number can be assigned to a double or a number
      if (isDouble(lhsType) || isNumber(lhsType)) {
         return true;
      }

      // Any number but a double can be assigned to a float
      if (isFloat(lhsType))
         return !isDouble(rhsType);

      // Any integer type can be assigned to a long during an assignment but
      // when matching parameters of a method, it must be a long
      if (isLong(lhsType)) {
         if (assignmentSemantics)
            return !isFloat(rhsType);
         else
            return isAnInteger(rhsType);
      }

      // Integer types can't be assigned with longs or floats during assignments but not matching methods
      assert isAnInteger(lhsType);
      if (assignmentSemantics)
         return !isLong(rhsType) && !isFloat(rhsType) && !isDouble(rhsType);
      else {
         // Java allows silent conversion up but not down
         if (isInteger(lhsType))
            return isInteger(rhsType) || isShort(rhsType) || isByte(rhsType);
         if (isShort(lhsType))
            return isShort(rhsType) || isByte(rhsType);
         if (isByte(lhsType))
            return isByte(rhsType);

         throw new UnsupportedOperationException();
      }
   }

   public static boolean implementsType(Object implType, String fullTypeName) {
      if (implType instanceof ITypeDeclaration)
         return ((ITypeDeclaration) implType).implementsType(fullTypeName);
      else {
         Class implClass = (Class) implType;

         Class typeClass = RTypeUtil.loadClass(implClass.getClassLoader(), fullTypeName, false);

         return typeClass != null && typeClass.isAssignableFrom(implClass);
      }
   }

   /**
    * As we are inheriting definitions we may find both src/dst types have the same annotation.
    * This method merges the definitions.  When we are merging up to a more specific layer, we only
    * copy definitions not found in the destination layer (replace=false).  When we are merging from
    * a more specific to a more general definition (the modify declaration), we replace conflicting
    * definitions in the dest.
    */
   public static Object mergeAnnotations(Object mainAnnot, Object overAnnot, boolean replace) {
      boolean any = false;

      Annotation newAnnotation = null;

      // Three types of annotations:
      // Marker: no value - nothing to merge
      // SingleElementAnnotation - if replace, we replace the value otherwise do nothing
      // InstInit Annotation - merge the name/values replacing values if replace is true

      if (ModelUtil.isComplexAnnotation(overAnnot)) {
         if (!ModelUtil.isComplexAnnotation(mainAnnot))
            return replace ? overAnnot : mainAnnot; // Weird case - an @Foo(a=1,b=2) overriding @Foo(3) or @Foo - use the more specific one or do we need a way to clear the

         List<AnnotationValue> overriddenValues = ModelUtil.getAnnotationComplexValues(overAnnot);
         List<AnnotationValue> thisElementValues = ModelUtil.getAnnotationComplexValues(mainAnnot);
         int thisSz = thisElementValues.size();
         for (int i = 0; i < overriddenValues.size(); i++) {
            AnnotationValue av = overriddenValues.get(i);

            int tix;
            AnnotationValue tv = null;
            for (tix = 0; tix < thisSz; tix++) {
               tv = thisElementValues.get(tix);
               if (tv.identifier.equals(av.identifier)) {
                  break;
               }
            }
            if (tix != thisSz) {
               if (replace) {
                  if (tv != null)
                     thisElementValues.remove(tv);
                  thisElementValues.add(av);
               }
               // else - use mainAnnot version
            }
            // Overridden value not found in main - first time clone the element, then add the overridden guys to this new list only if their' not in main
            else {
               if (newAnnotation == null) {
                  newAnnotation = Annotation.createFromAnnotation(mainAnnot);
               }
               List<Object> newValues = (List<Object>) newAnnotation.elementValue;
               newValues.add(av.deepCopy(ISemanticNode.CopyNormal, null));
            }
         }
         if (newAnnotation != null)
            return newAnnotation;
      }
      else if (replace) {
         Object overrideSV = ModelUtil.getAnnotationSingleValue(overAnnot);
         if (overrideSV != null)
            return overrideSV;
      }
      return mainAnnot;
   }

   public static Object getAnnotation(Object definition, String annotationName) {
      PerfMon.start("getAnnotation");
      try {
      if (definition instanceof IDefinition) {
         return ((IDefinition) definition).getAnnotation(annotationName);
      }
      else {
         // TODO: fix annotation names
         Class annotationClass;
         if (annotationName.equals("Bindable") || annotationName.equals("sc.bind.Bindable"))
            annotationClass = Bindable.class;
         else if (annotationName.equals("BindSettings"))
            annotationClass = BindSettings.class;
         else
            annotationClass = RDynUtil.loadClass(annotationName);
         if (annotationClass == null) {
            annotationClass = RDynUtil.loadClass("sc.obj." + annotationName);
            if (annotationClass == null) {
               // NOTE: assuming that the layers are defined properly, compiled classes can't have non-compiled annotations so just return null, e.g. searching for a JSSettings or MainInit annotation on a compiled class.
               return null;
            }
         }

         java.lang.annotation.Annotation jlannot;
         if (definition instanceof Class)
            jlannot = ((Class) definition).getAnnotation(annotationClass);
         else if (definition instanceof AnnotatedElement)
            jlannot = ((AnnotatedElement) definition).getAnnotation(annotationClass);
         // For bindable annotation at least, we need to get the setter and right now getPropertyMember returns the get over the set
         else if (definition instanceof IBeanMapper) {
            IBeanMapper mapper = (IBeanMapper) definition;
            Object ret = null;
            Object sel;
            if ((sel = mapper.getGetSelector()) != null) {
               ret = getAnnotation(sel, annotationName);
               if (ret != null)
                  return ret;
            }
            if ((sel = mapper.getSetSelector()) != null) {
               ret = getAnnotation(sel, annotationName);
               if (ret != null)
                  return ret;
            }
            if ((sel = mapper.getField()) != null) {
               ret = getAnnotation(sel, annotationName);
               if (ret != null)
                  return ret;
            }
            return null;
         }
         else if (definition instanceof VariableDefinition)
            return getAnnotation(((VariableDefinition) definition).getDefinition(), annotationName);
         else if (definition instanceof ParamTypedMember)
            return getAnnotation(((ParamTypedMember) definition).getMemberObject(), annotationName);
         else if (definition instanceof java.lang.Enum || definition instanceof ITypeDeclaration)
            return null;
         else
            throw new UnsupportedOperationException();

         return jlannot;
      }
      }
      finally {
         PerfMon.end("getAnnotation");
      }
   }

   public static Object getAnnotationValue(Object annotation, String s) {
      if (annotation instanceof IAnnotation)
         return ((IAnnotation) annotation).getAnnotationValue(s);
      else if (annotation instanceof java.lang.annotation.Annotation)
         return TypeUtil.invokeMethod(annotation, RTypeUtil.getMethod(annotation.getClass(), s), (Object[])null);
      throw new UnsupportedOperationException();
   }

   public static boolean isComplexAnnotation(Object annotation) {
      if (annotation instanceof IAnnotation)
         return ((IAnnotation) annotation).isComplexAnnotation();
      else if (annotation instanceof java.lang.annotation.Annotation) {
         Class cl = annotation.getClass();

         Method[] annotMethods = cl.getDeclaredMethods();
         // An annotation with one "value()" method is simple, no methods is a marker.
         return annotMethods != null && annotMethods.length != 0 && (annotMethods.length != 1 || !annotMethods[0].getName().equals("value"));
      }
      throw new UnsupportedOperationException();
   }

   public static Object[] parametersToTypeArray(List<? extends Object> parameters, ITypeParamContext ctx) {
      int size = parameters == null ? 0 : parameters.size();
      // Perf tuneup: could move logic to do comparisons into the cache so we don't allocate the temporary array here
      Object[] parameterTypes = new Object[size];
      for (int i = 0; i < size; i++)
         parameterTypes[i] = ModelUtil.getVariableTypeDeclaration(parameters.get(i));
      return parameterTypes;
   }

   public static Object definesMethod(Object td, String name, List<? extends Object> parameters, ITypeParamContext ctx, Object refType, boolean isTransformed) {
      Object res;
      if (td instanceof ITypeDeclaration) {
         if ((res = ((ITypeDeclaration)td).definesMethod(name, parameters, ctx, refType, isTransformed)) != null)
            return res;
      }
      else if (td instanceof Class) {
         Object[] parameterTypes = parametersToTypeArray(parameters, ctx);
         if ((res = ModelUtil.getMethod(td, name, refType, ctx, parameterTypes)) != null)
            return res;
      }
      return null;
   }

   public static Object declaresConstructor(Object td, List<?> parameters, ITypeParamContext ctx) {
      return declaresConstructor(td, parameters, ctx, null);
   }

   public static Object declaresConstructor(Object td, List<?> parameters, ITypeParamContext ctx, Object refType) {
      Object res;
      if (td instanceof ITypeDeclaration) {
         if ((res = ((ITypeDeclaration)td).declaresConstructor(parameters, ctx)) != null)
            return res;
      }
      else if (td instanceof Class) {
         int paramsLen = parameters == null ? 0 : parameters.size();
         Object[] types = parametersToTypeArray(parameters, ctx);
         Object[] list = getConstructors(td, null);
         res = null;
         Object enclType = ModelUtil.getEnclosingInstType(td);
         int paramStart = 0;
         // The compiled class will have the outer class as an instance parameter.  When calling from the source world though, we don't have
         // that parameter in the list.  Skip that parameter.
         if (enclType != null)
            paramStart = 1;
         for (int i = 0; i < list.length; i++) {
            Object toCheck = list[i];
            Object[] parameterTypes = ModelUtil.getParameterTypes(toCheck);
            int paramLen = (parameterTypes == null ? 0 : parameterTypes.length) - paramStart;
            if (paramLen == 0 && paramsLen == 0) {
               if (refType == null || checkAccess(refType, toCheck))
                  res = ModelUtil.pickMoreSpecificMethod(res, toCheck, null);
            }
            else {
               int j;
               int last = paramLen - 1 + paramStart;
               if (paramLen != paramsLen) {
                  // If the last guy is not a repeating parameter, it can't match
                  if (last < 0 || !ModelUtil.isVarArgs(toCheck) || !ModelUtil.isArray(parameterTypes[last]) || paramsLen < last)
                     continue;
               }
               for (j = 0; j < paramsLen; j++) {
                  Object paramType;
                  if (j > last) {
                     if (!ModelUtil.isArray(paramType = parameterTypes[last]))
                        break;
                  }
                  else
                     paramType = parameterTypes[j+paramStart];
                  if (types[j] != null && !ModelUtil.isAssignableFrom(paramType, types[j], false, ctx)) {
                     // Repeating parameters... if the last parameter is an array match if the component type matches
                     if (j >= last && ModelUtil.isArray(paramType)) {
                        if (!ModelUtil.isAssignableFrom(ModelUtil.getArrayComponentType(paramType), types[j], false, ctx)) {
                           break;
                        }
                     }
                     else
                        break;
                  }
               }
               if (j == paramsLen) {
                  if (refType == null || checkAccess(refType, toCheck))
                     res = ModelUtil.pickMoreSpecificMethod(res, toCheck, types);
               }
            }
         }
         return res;
      }
      else
         throw new UnsupportedOperationException();
      return null;
   }

   public static Object definesConstructor(Object td, List<?> parameters, ITypeParamContext ctx) {
      return definesConstructor(td, parameters, ctx, null, false);
   }

   public static Object definesConstructor(Object td, List<?> parameters, ITypeParamContext ctx, Object refType, boolean isTransformed) {
      Object res;
      if (td instanceof ITypeDeclaration) {
         if ((res = ((ITypeDeclaration)td).definesConstructor(parameters, ctx, isTransformed)) != null)
            return res;
      }
      /*
      else if (td instanceof Class) {
         int size = parameters == null ? 0 : parameters.size();
         // Perf tuneup: could move logic to do comparisons into the cache so we don't allocate the temporary array here
         Class[] parameterTypes = new Class[size];
         for (int i = 0; i < size; i++)
            parameterTypes[i] = ModelUtil.getCompiledClass(parameters.get(i));
         // Does this need any more flexible matching ala ModelUtil.getMethod?
         if ((res = RTypeUtil.getConstructor((Class) td, parameterTypes)) != null)
            return res;
      }
      */
      else if (td instanceof Class) {
         int paramsLen = parameters == null ? 0 : parameters.size();
         Object[] list = getConstructors(td, null);
         res = null;
         for (int i = 0; i < list.length; i++) {
            Object toCheck = list[i];
            Object[] parameterTypes = ModelUtil.getParameterTypes(toCheck);
            int paramLen = parameterTypes == null ? 0 : parameterTypes.length;
            if (paramLen == 0 && paramsLen == 0) {
               if (refType == null || checkAccess(refType, toCheck))
                  res = ModelUtil.pickMoreSpecificMethod(res, toCheck, null);
            }
            else {
               int j;
               int last = paramLen - 1;
               if (paramLen != paramsLen) {
                  // If the last guy is not a repeating parameter, it can't match
                  if (last < 0 || !ModelUtil.isVarArgs(toCheck) || !ModelUtil.isArray(parameterTypes[last]) || paramsLen < last)
                     continue;
               }
               for (j = 0; j < paramsLen; j++) {
                  Object paramType;
                  if (j > last) {
                     if (!ModelUtil.isArray(paramType = parameterTypes[last]))
                        break;
                  }
                  else
                     paramType = parameterTypes[j];
                  if (parameters.get(j) != null && !ModelUtil.isAssignableFrom(paramType, parameters.get(j), false, ctx)) {
                     // Repeating parameters... if the last parameter is an array match if the component type matches
                     if (j >= last && ModelUtil.isArray(paramType)) {
                        if (!ModelUtil.isAssignableFrom(ModelUtil.getArrayComponentType(paramType), parameters.get(j), false, ctx)) {
                           break;
                        }
                     }
                     else
                        break;
                  }
               }
               if (j == paramsLen) {
                  if (refType == null || checkAccess(refType, toCheck))
                     res = ModelUtil.pickMoreSpecificMethod(res, toCheck, parameters.toArray());
               }
            }
         }
         return res;
      }
      else
         throw new UnsupportedOperationException();
      return null;
   }

   public static Object[] getConstructors(Object td, Object refType) {
      while (ModelUtil.hasTypeParameters(td))
         td = ModelUtil.getParamTypeBaseType(td);
      if (td instanceof ITypeDeclaration)
         return ((ITypeDeclaration) td).getConstructors(refType);
      else if (td instanceof Class) {
         Object[] res = RTypeUtil.getConstructors((Class) td);
         if (refType != null && res != null) {
            ArrayList<Object> accessibles = null;
            for (Object constr:res) {
               if (!checkAccess(refType, constr)) {
                  if (accessibles == null) {
                     accessibles = new ArrayList<Object>(res.length-1);
                     for (Object toAdd:res) {
                        if (toAdd == constr)
                           break;
                        accessibles.add(toAdd);
                     }
                  }
               }
               else if (accessibles != null)
                  accessibles.add(constr);
            }
            if (accessibles != null)
               return accessibles.toArray();
         }
         return res;
      }
      else
         throw new UnsupportedOperationException();
   }

   public static boolean transformNewExpression(LayeredSystem sys, Object boundType) {
      // For types which have the component interface, if it is either an object or has a new template
      // we need to transform new Foo into a call to the newFoo method that was generated.
      return ModelUtil.isComponentInterface(sys, boundType);
   }

   public static boolean isObjectType(Object boundType) {
      return (boundType instanceof TypeDeclaration && ((TypeDeclaration) boundType).getDeclarationType() == DeclarationType.OBJECT) || hasObjectTypeAnnotation(boundType);
   }

   public static boolean isObjectProperty(Object boundType) {
      return boundType != null && (boundType instanceof TypeDeclaration && ((TypeDeclaration) boundType).isObjectProperty());
   }

   /** Is this an object type which overrides a previous getX/setX method */
   public static boolean isObjectSetProperty(Object boundType) {
      return boundType != null && (boundType instanceof BodyTypeDeclaration && ((BodyTypeDeclaration) boundType).isObjectSetProperty());
   }

   public static boolean hasObjectTypeAnnotation(Object boundType) {
      Object annotation = getAnnotation(boundType, "sc.obj.TypeSettings");
      if (annotation == null) {

         // It's possible we should treat getX without setX as constant by default?   I suspect the values of those
         // will change but we would not be able to detect that by default anyway.
         return false;
      }
      else {
         Boolean b = (Boolean) getAnnotationValue(annotation, "objectType");
         if (b == null)
            return false;
         return b;
      }
   }

   /** If given a top-level object type, returns the object instance.  If given a class, creates an instance of that class using the zero arg constructor.  */
   public static Object getObjectInstance(Object typeObj) {
      Object inst;
      if (ModelUtil.isObjectType(typeObj)) {
         Object runtimeType = ModelUtil.getRuntimeType(typeObj);
         if (runtimeType instanceof TypeDeclaration) {
            inst = ((TypeDeclaration) runtimeType).getObjectInstance();
         }
         else
            inst = DynUtil.getStaticProperty(runtimeType, CTypeUtil.decapitalizePropertyName(getClassName(typeObj)));
      }
      else {
         ExecutionContext ctx;
         if (typeObj instanceof ITypeDeclaration)
            ctx = new ExecutionContext(ModelUtil.getJavaModel(typeObj));
         else
            ctx = new ExecutionContext();
         inst = createInstance(typeObj, null, new ArrayList<Expression>(0), ctx);
      }
      return inst;
   }

   public static Object getAndRegisterGlobalObjectInstance(Object typeObj) {
      Object inst = getObjectInstance(typeObj);
      // Register this as a top-level object with the system under the type name.
      if (inst != null && ModelUtil.getLiveDynamicTypes(typeObj)) {
         DynUtil.addDynObject(ModelUtil.getTypeName(typeObj), inst);
      }
      return inst;
   }

   public static boolean isField(Object varObj) {
      if (varObj instanceof ParamTypedMember)
         return isField(((ParamTypedMember) varObj).getMemberObject());
      if (varObj instanceof PropertyAssignment)
         return isField(((PropertyAssignment) varObj).assignedProperty);
      return varObj instanceof Field || (varObj instanceof IBeanMapper && isField(((IBeanMapper) varObj).getPropertyMember())) || varObj instanceof FieldDefinition || varObj instanceof CFField ||
          (varObj instanceof VariableDefinition && (((VariableDefinition) varObj).getDefinition()) instanceof FieldDefinition);
   }

   public static boolean hasField(Object varObj) {
      if ((varObj instanceof IBeanMapper) && ((IBeanMapper) varObj).getField() != null)
         return true;
      return false;
   }

   public static boolean isProperty(Object member) {
      if (member instanceof ParamTypedMember)
         member = ((ParamTypedMember) member).getMemberObject();
      return member instanceof PropertyAssignment || member instanceof IBeanMapper || isField(member) || isGetSetMethod(member);
   }

   public static boolean isReadableProperty(Object member) {
      return isProperty(member) && (hasGetMethod(member)  || isField(member));
   }

   public static boolean isWritableProperty(Object member) {
      return isProperty(member) && (hasSetMethod(member)  || isField(member));
   }

   public static boolean isGetSetMethod(Object member) {
      if (member instanceof PropertyAssignment)
         member = ((PropertyAssignment) member).getAssignedProperty();
      if (member instanceof Method) {
         Method meth = (Method) member;
         return RTypeUtil.isGetMethod(meth) || RTypeUtil.isSetMethod(meth);
      }
      else if (member instanceof IMethodDefinition) {
         IMethodDefinition methDef = (IMethodDefinition) member;
         return methDef.getPropertyName() != null;
      }
      return false;
   }

   public static boolean isPropertyGetSet(Object member) {
      if (member instanceof ParamTypedMember)
          return isPropertyGetSet(((ParamTypedMember) member).getMemberObject());
      if (member instanceof PropertyAssignment)
         return isPropertyGetSet(((PropertyAssignment) member).getAssignedProperty());
      if (member instanceof IBeanMapper) {
         IBeanMapper mapper = ((IBeanMapper) member);
         return mapper.hasSetterMethod() || mapper.hasAccessorMethod();
      }
      return isGetSetMethod(member);
   }

   public static boolean hasGetMethod(Object member) {
      if (member instanceof ParamTypedMember)
         member = ((ParamTypedMember) member).getMemberObject();
      if (member instanceof PropertyAssignment)
         member = ((PropertyAssignment) member).getAssignedProperty();
      if (member instanceof IBeanMapper && ((IBeanMapper) member).hasAccessorMethod())
         return true;
      if (member instanceof IMethodDefinition)
         return ((IMethodDefinition) member).hasGetMethod();
      // TODO: handle Method here - usually it will be an IBeanMapper
      return false;
   }

   public static boolean hasSetMethod(Object member) {
      if (member instanceof ParamTypedMember)
         member = ((ParamTypedMember) member).getMemberObject();
      if (member instanceof PropertyAssignment)
         member = ((PropertyAssignment) member).getAssignedProperty();
      if (member instanceof IBeanMapper && ((IBeanMapper) member).hasSetterMethod())
         return true;
      if (member instanceof IMethodDefinition)
         return ((IMethodDefinition) member).hasSetMethod();
      return false;
   }

   public static Object getGetMethodFromSet(Object member) {
      if (member instanceof ParamTypedMember)
         member = ((ParamTypedMember) member).getMemberObject();
      if (member instanceof PropertyAssignment)
         member = ((PropertyAssignment) member).getAssignedProperty();
      if (member instanceof IBeanMapper && ((IBeanMapper) member).hasAccessorMethod())
         return ((IBeanMapper) member).getGetSelector();
      if (member instanceof IMethodDefinition)
         return ((IMethodDefinition) member).getGetMethodFromSet();
      // TODO: handle Method here - usually it will be an IBeanMapper
      return null;
   }

   public static Object getSetMethodFromGet(Object member) {
      if (member instanceof ParamTypedMember)
         member = ((ParamTypedMember) member).getMemberObject();
      if (member instanceof PropertyAssignment)
         member = ((PropertyAssignment) member).getAssignedProperty();
      if (member instanceof IBeanMapper)
         return((IBeanMapper) member).getGetSelector();
      if (member instanceof IMethodDefinition)
         return ((IMethodDefinition) member).getSetMethodFromGet();
      // TODO: handle Method here - usually it will be an IBeanMapper
      return null;
   }

   public static boolean isPropertyIs(Object propObj) {
      if (propObj instanceof ParamTypedMember)
         return isPropertyIs(((ParamTypedMember) propObj).getMemberObject());
      if (propObj instanceof PropertyAssignment)
         return isPropertyIs(((PropertyAssignment) propObj).getAssignedProperty());
      else if (ModelUtil.isObjectType(propObj))
         return false;
      if (propObj instanceof VariableDefinition)
         return false;
      return propObj instanceof IBeanMapper ? ((IBeanMapper) propObj).isPropertyIs() : getMethodName(propObj).startsWith("is");
   }

   public static Object getPropertyType(Object prop) {
      if (prop instanceof ParamTypedMember)
         prop = ((ParamTypedMember) prop).getMemberObject();
      if (prop instanceof IBeanMapper)
         return ((IBeanMapper) prop).getPropertyType();
      else if (prop instanceof PropertyAssignment) {
         Object propType = ((PropertyAssignment) prop).getPropertyDefinition();
         if (propType == null)
            return Object.class; // Unresolved property - for lookupUIIcon just return something generic here
         return getPropertyType(propType);
      }
      else if (ModelUtil.isField(prop))
         return getFieldType(prop);
      else if (ModelUtil.isMethod(prop)) {
         if (isGetMethod(prop))
            return getReturnType(prop);
         else if (isSetMethod(prop))
            return getSetMethodPropertyType(prop);
         else if (isGetIndexMethod(prop))
            return getReturnType(prop); // TODO: should be an array of return type?
         else if (isSetIndexMethod(prop))
            return getSetMethodPropertyType(prop); // TODO: should be an array
      }
      else if (prop instanceof TypeDeclaration)
         return prop;
      else if (prop instanceof EnumConstant)
         return ((EnumConstant) prop).getEnclosingType();
      throw new UnsupportedOperationException();
   }

   public static Object getFieldType(Object field) {
      if (field instanceof ParamTypedMember)
         field = ((ParamTypedMember) field).getMemberObject();
      if (field instanceof Field)
         return ((Field) field).getType();
      else if (field instanceof FieldDefinition)
         return ((FieldDefinition) field).type.getTypeDeclaration();
      else if (field instanceof VariableDefinition)
         return getFieldType(((VariableDefinition) field).getDefinition());
      else
         throw new UnsupportedOperationException();
   }

   public static boolean isMethod(Object member) {
      if (member instanceof ParamTypedMember)
         member = ((ParamTypedMember) member).getMemberObject();
      return member instanceof Method || member instanceof IMethodDefinition;
   }

   public static int computeFrameSize(List<Statement> statements) {
      int frameSize = 0;
      if (statements != null) {
         int sz = statements.size();
         for (int i = 0; i < sz; i++) {
            Statement s = statements.get(i);
            if (s instanceof VariableStatement)
               frameSize += ((VariableStatement) s).definitions.size();
         }
      }
      return frameSize;
   }

   public static ExecResult execStatements(ExecutionContext ctx, List<Statement> statements) {
      if (statements == null)
         return ExecResult.Next;

      int sz = statements.size();
      ExecResult res = ExecResult.Next;
      Statement currentStatement = null;
      for (int i = 0; i < sz; i++) {
         try {
            currentStatement = statements.get(i);
            res = currentStatement.exec(ctx);
         }
         catch (RuntimeException exc) {
            throw wrapRuntimeException(currentStatement, exc);
         }
         if (res != ExecResult.Next)
            return res;
      }
      return res;
   }

   public static RuntimeException wrapRuntimeException(Statement statement, RuntimeException exc) {
      Class cl = exc.getClass();
      Constructor constr = RTypeUtil.getConstructor(cl, String.class, Throwable.class);
      boolean addThrowable = true;
      boolean newExc = false;
      if (constr == null) {
         constr = RTypeUtil.getConstructor(cl, String.class);
         if (constr == null)
            return exc;
         addThrowable = false;
      }

      String message = exc.getMessage();
      boolean first = false;
      if (message == null) {
         message = "dyn stack:\n";
         first = true;
         newExc = true;
      }
      else if (!message.contains("dyn stack")) {
          message = message + ": dyn stack:\n";
         first = true;
         newExc = true;
      }

      if (first || statement instanceof AbstractMethodDefinition) {
         message += statement.toLocationString() + "\n";
         newExc = true;
      }

      if (!message.contains("Compiled stack")) {
         message += "Compiled stack:\n";
         message += LayerUtil.getExceptionStack(exc);
         newExc = true;
      }

      if (!newExc)
         return exc;

      if (addThrowable)
         return (RuntimeException) PTypeUtil.createInstance(cl, null, message, exc);
      else
         return (RuntimeException) PTypeUtil.createInstance(cl, null, message);
   }

   public static Object getMethod(Object currentObject, String s) {
      // TODO: support a sc-table in the dynamic object and interpret a MethodDefinition here
      // See ClassDeclaration.createInstance.  Here we need to implement overriding of dynamic methods.
      // The execution context probably needs to track the current type declaration so we can do the "super"
      // definition in IdentifierExpression.  
      return RTypeUtil.getMethod(currentObject.getClass(), s);
   }

   public static Object createInstance(Object boundType, String sig, List<Expression> arguments, ExecutionContext ctx) {
      if (boundType instanceof Class) {
         Object[] values = constructorArgListToValues(boundType, arguments, ctx, null);
         return PTypeUtil.createInstance((Class) boundType, sig, values);
      }
      else if (boundType instanceof TypeDeclaration) {
         TypeDeclaration decl = (TypeDeclaration) boundType;
         return decl.createInstance(ctx, sig, arguments);
      }
      else if (boundType instanceof CFClass) {
         CFClass cfClass = (CFClass) boundType;
         Class rtClass = cfClass.getCompiledClass();
         if (rtClass != null)
            return createInstance(rtClass, sig, arguments, ctx);
         else
            throw new IllegalArgumentException("Class: " + cfClass.getFullTypeName() + " is not in the system classpath and cannot be instantiated by the interpreter");
      }
      throw new UnsupportedOperationException();
   }

   public static void execTemplateDeclarations(StringBuilder sb, ExecutionContext ctx, List<Object> declarations) {
      if (declarations == null)
         return;

      if (sb == null)
         throw new IllegalArgumentException("template output method called with null value for the 'out' StringBuilder parameter");
      
      for (int i = 0; i < declarations.size(); i++) {
         Object def = declarations.get(i);
         if (def instanceof IString)
            sb.append(def.toString());
         else if (def instanceof IValueNode) {
            do {
               // A glue expression can evaluate into a Template.  Need to use eval again instead of explicitly
               Object val = ((IValueNode )def).eval(null, ctx);
               if (val != null) {
                  if (val instanceof IValueNode)
                     def = val;
                  else {
                     sb.append(val.toString());
                     break;
                  }
               }
               else
                  break;
            } while (true);
         }
         else if (def instanceof TemplateStatement) {
            ModelUtil.execStatements(ctx, ((TemplateStatement) def).statements);
         }
         else if (def instanceof Element) {
            // Typically we transform templates and eval the transformed version so should not hit this?
            System.out.println("*** Error not evaluating HTML elements");
            // TODO: should be evaluating the template declarations nested inside of this element - in attributes or in the body
            sb.append(def.toString());
         }
      }
   }

   public static AccessLevel getAccessLevel(Object def, boolean explicitOnly) {
      return getAccessLevel(def, explicitOnly, null);
   }

   public static AccessLevel getAccessLevel(Object def, boolean explicitOnly, JavaSemanticNode.MemberType mtype) {
      if (def instanceof IDefinition)
         return ((IDefinition) def).getAccessLevel(explicitOnly);
      else if (def instanceof IBeanMapper) {
         IBeanMapper mapper = (IBeanMapper) def;
         if (mtype == null)
            def = mapper.getPropertyMember();
         else {
            if (mtype == JavaSemanticNode.MemberType.GetMethod) {
               def = mapper.getGetSelector();
               if (def == null) {
                  if (mapper instanceof IBeanIndexMapper)
                     def = ((IBeanIndexMapper) mapper).getIndexedGetSelector();
               }
               if (def == null)
                  def = mapper.getField();
               if (def == null)
                  def = mapper.getSetSelector();
            }
            else if (mtype == JavaSemanticNode.MemberType.SetMethod || mtype == JavaSemanticNode.MemberType.Assignment) {
               def = mapper.getSetSelector();
               if (mapper instanceof IBeanIndexMapper)
                  def = ((IBeanIndexMapper) mapper).getIndexedSetSelector();
               if (def == null)
                  def = mapper.getField();
            }
            else if (mtype == JavaSemanticNode.MemberType.Field || mtype == JavaSemanticNode.MemberType.Enum) {
               def = mapper.getField();
               if (def == null)
                  def = mapper.getGetSelector();
            }
         }
      }
      else if (def instanceof VariableDefinition)
         return getAccessLevel(((VariableDefinition) def).getDefinition(), explicitOnly);

      if (def == null)
         return null;

      int modifiers = getModifiers(def);
      if (Modifier.isPublic(modifiers))
         return AccessLevel.Public;
      else if (Modifier.isProtected(modifiers))
         return AccessLevel.Protected;
      else if (Modifier.isPrivate(modifiers))
         return AccessLevel.Private;
      return null;
   }

   public static boolean hasModifier(Object def, String s) {
      if (def instanceof IDefinition)
         return ((IDefinition) def).hasModifier(s);
      else if (def instanceof IBeanMapper)
         def = ((IBeanMapper) def).getPropertyMember();
      else {
         while (ModelUtil.hasTypeParameters(def))
            def = ModelUtil.getParamTypeBaseType(def);
      }

      if (def instanceof VariableDefinition)
         return hasModifier(((VariableDefinition) def).getDefinition(), s);

      return PTypeUtil.hasModifier(def, s);
   }

   public static SemanticNodeList<AnnotationValue> getAnnotationComplexValues(Object annotObj) {
      if (annotObj instanceof IAnnotation)
         return ((IAnnotation) annotObj).getElementValueList();
      else {
         java.lang.annotation.Annotation lannot = (java.lang.annotation.Annotation) annotObj;

         Class cl = lannot.getClass();
         Method[] annotValues = cl.getDeclaredMethods();

         SemanticNodeList<AnnotationValue> values = new SemanticNodeList<AnnotationValue>(annotValues.length);
         for (int i = 0; i < annotValues.length; i++) {
            Method annotMeth = annotValues[i];
            if (ModelUtil.hasModifier(annotMeth, "static"))
               continue;
            String name = annotValues[i].getName();
            // TODO: find some better way to eliminate the Object class methods from the annotation class.  Presumably it does not change often but should put this in a static table at least or better yet, find some way to get it from the runtime.
            if (name.equals("equals") || name.equals("toString") || name.equals("hashCode") || name.equals("annotationType") || name.equals("isProxyClass") || name.equals("wait") || name.equals("getClass") || name.equals("notify") || name.equals("notifyAll"))
               continue;
            AnnotationValue av = new AnnotationValue();
            av.identifier = name;
            av.elementValue = TypeUtil.invokeMethod(lannot, annotValues[i], (Object[])null);
            values.add(av);
         }
         return values;
      }
   }

   public static Object getAnnotationSingleValue(Object annotObj) {
      if (annotObj instanceof IAnnotation)
         return ((IAnnotation) annotObj).getElementSingleValue();
      else {
         java.lang.annotation.Annotation lannot = (java.lang.annotation.Annotation) annotObj;

         Class cl = lannot.getClass();
         Method[] annotValues = cl.getMethods();
         if (annotValues.length != 1) {
            return TypeUtil.invokeMethod(lannot, TypeUtil.resolveMethod(cl, "value", null), (Object[])null);
         }

         return TypeUtil.invokeMethod(lannot, annotValues[0], (Object[])null);
      }
   }

   public static BindingDirection initBindingDirection(String operator) {
      if (operator == null) return null;
      int cix = operator.indexOf(":");
      if (cix != -1) {
         if (cix == 0) {
            if (operator.length() == 3)
               return BindingDirection.BIDIRECTIONAL;
            else
               return BindingDirection.FORWARD;
         }
         else
            return BindingDirection.REVERSE;
      }
      return null;
   }

   public static Object getEnclosingInstType(Object compClass) {
      Object type = getEnclosingType(compClass);
      // Inner enums are implicity static
      if (isEnumType(compClass))
         return null;
      return type == null ? null : ModelUtil.hasModifier(compClass, "static") ? null : type;
   }

   public static Object getEnclosingType(Object srcMember) {
      if (srcMember instanceof IDefinition) {
         return ((IDefinition) srcMember).getEnclosingIType();
      }
      else if (srcMember instanceof Member) {
         return ((Member) srcMember).getDeclaringClass();
      }
      else if (srcMember instanceof BeanMapper) {
         return ((BeanMapper) srcMember).getPropertyMember().getDeclaringClass();
      }
      else if (srcMember instanceof VariableDefinition) {
         return ((VariableDefinition) srcMember).getEnclosingType();
      }
      else if (srcMember instanceof Class) {
         return ((Class) srcMember).getDeclaringClass();
      }
      else if (srcMember instanceof Template)
         return null;
      else if (srcMember instanceof Parameter)
         return ((Parameter) srcMember).getEnclosingType();
      else if (srcMember instanceof java.lang.Enum)
         return ((java.lang.Enum) srcMember).getDeclaringClass();
      throw new UnsupportedOperationException();
   }

   public static String unescapeJavaString(String str) {
      StringBuilder sb = new StringBuilder(str.length());
      int len = str.length();
      for (int i = 0; i < len; i++) {
         char charVal = str.charAt(i);
         if (charVal == '\\') {
            i++;
            switch (str.charAt(i)) {
               case 'b':
                  charVal = '\b';
                  break;
               case 't':
                  charVal = '\t';
                  break;
               case 'n':
                  charVal = '\n';
                  break;
               case 'f':
                  charVal = '\f';
                  break;
               case 'r':
                  charVal = '\r';
                  break;
               case '"':
                  charVal = '\"';
                  break;
               case '\\':
                  charVal = '\\';
                  break;
               case '\'':
                  charVal = '\'';
                  break;
               case 'u':
                  String hexStr = str.substring(i+1,i+5);
                  i += 4;
                  try {
                     charVal = (char) Integer.parseInt(hexStr, 16);
                  }
                  catch (NumberFormatException exc) {
                     System.err.println("**** Invalid character unicode escape: " + hexStr + " should be a hexadecimal number");
                  }
                  break;
               case '0':
               case '1':
               case '2':
               case '3':
               case '4':
               case '5':
               case '6':
               case '7':
                  // Special case for characters: \0
                  if (str.length() == i + 1)
                     charVal = '\0';
                  else {
                     String octStr = str.substring(i,i+3);
                     i += 3;
                     try {
                        charVal = (char) Integer.parseInt(octStr, 8);
                     }
                     catch (NumberFormatException exc) {
                        System.err.println("**** Invalid character octal escape: " + octStr + " should be an octal number");
                     }
                  }
                  break;
            }
         }
         sb.append(charVal);
      }
      return sb.toString();
   }

   public static Object getOuterObject(Object toConstructType, ExecutionContext ctx) {
      Object toConstructCompiled = ModelUtil.getCompiledClass(toConstructType);
      if (toConstructCompiled == null) {
         toConstructCompiled = toConstructType; // A dynamic type
      }
      Object enclType = ModelUtil.getEnclosingInstType(toConstructCompiled);
      if (enclType != null) {
         return ctx.findThisType(enclType);
      }
      return null;
   }

   /** Returns true for classes which have a constructor that takes a BodyTypeDeclaration */
   public static boolean needsTypeDeclarationParam(Class compClass) {
      Constructor[] ctors = RTypeUtil.getConstructors(compClass);
      if (ctors == null)
         return false;
      for (Constructor ctor:ctors) {
         Class[] subParamTypes = ctor.getParameterTypes();
         if (subParamTypes != null && subParamTypes.length > 0) {
            if (BodyTypeDeclaration.class.isAssignableFrom(subParamTypes[0]))
               return true;
         }
      }
      return false;
   }

   public static Object[] constructorArgListToValues(Object toConstructType, List<Expression> arguments, ExecutionContext ctx, Object outerObj) {
      Object enclType = ModelUtil.getEnclosingInstType(toConstructType);
      int size = arguments == null ? 0 : arguments.size();
      if (enclType != null)
         size++;
      Object[] argValues = new Object[size];

      int start = 0;
      if (enclType != null) {
         argValues[0] = outerObj == null ? ctx.findThisType(enclType) : outerObj;
         if (argValues[0] == null) {
            Object tmpRes = ctx.findThisType(enclType);
            throw new IllegalArgumentException("No enclosing type: " + ModelUtil.getTypeName(enclType) + " to construct inner type: " + toConstructType);
         }
         start = 1;
      }
      if (arguments != null) {
         for (int i = start; i < size; i++) {
            Expression arg = arguments.get(i-start);
            argValues[i] = arg.eval(arg.getRuntimeClass(), ctx);
         }
      }
      return argValues;
   }

   public static Object[] expressionListToValues(List<Expression> arguments, ExecutionContext ctx) {
      int size = arguments.size();
      Object[] argValues = new Object[size];
      for (int i = 0; i < size; i++) {
         Expression arg = arguments.get(i);
         argValues[i] = arg.eval(arg.getRuntimeClass(), ctx);
      }
      return argValues;
   }

   public static Object callMethod(Object thisObj, Object method, Object...argValues) {
      if (method instanceof Method) {
         Method jMethod = (Method) method;
         return TypeUtil.invokeMethod(thisObj, jMethod, argValues);
      }
      else if (method instanceof AbstractMethodDefinition) {
         return ((AbstractMethodDefinition) method).callVirtual(thisObj, argValues);
      }
      else if (method instanceof CFMethod) {
         Method rtMeth = ((CFMethod) method).getRuntimeMethod();

         if (rtMeth == null)
            throw new IllegalArgumentException("Can't invoke method: " + method + " No runtime class found");

         return ModelUtil.callMethod(thisObj, rtMeth, argValues);
      }
      else if (method instanceof ParamTypedMethod) {
         return callMethod(thisObj, ((ParamTypedMethod) method).method, argValues);
      }
      else if (method instanceof String) {
         Object methodObj = ModelUtil.getMethod(thisObj, (String) method);
         if (methodObj == null) {
            throw new IllegalArgumentException("No method: " + method + " on: " + thisObj);
         }
         return callMethod(thisObj, methodObj, argValues);

      }
      else
         throw new UnsupportedOperationException();
   }

   /** A simple direct invoke of either an interpreted or compiled method once values are bound to parameters */
   public static Object invokeMethod(Object thisObject, Object method, Object[] argValues, ExecutionContext ctx) {
      // Compiled
      if (method instanceof Method) {
         Method jMethod = (Method) method;
         return TypeUtil.invokeMethod(thisObject, jMethod, argValues);
      }
      // Interpreted
      else if (method instanceof AbstractMethodDefinition) {
         AbstractMethodDefinition rtmeth = (AbstractMethodDefinition) method;

         return rtmeth.invoke(ctx, argValues == null ? Collections.EMPTY_LIST : Arrays.asList(argValues));
      }
      else if (method instanceof CFMethod) {
         Method rtMeth = ((CFMethod) method).getRuntimeMethod();

         if (rtMeth == null)
            throw new IllegalArgumentException("Can't invoke method: " + method + " No runtime class found");

         return ModelUtil.invokeMethod(thisObject, rtMeth, argValues, ctx);
      }
      else if (method instanceof ParamTypedMethod) {
         return invokeMethod(thisObject, ((ParamTypedMethod) method).method, argValues, ctx);
      }
      else
         throw new UnsupportedOperationException();
   }

   private static Object invokeMethodWithValues(Object thisObject, Object method, List<Expression> arguments,
                                     Class expectedType, ExecutionContext ctx, boolean repeatArgs, boolean findMethodOnThis, Object[] argValues) {
      // Compiled
      if (method instanceof Method) {
         Method jMethod = (Method) method;
         if (repeatArgs && argValues.length > 0) {
            Class[] types = jMethod.getParameterTypes();
            int last = types.length - 1;
            Class lastType = types[last];
            // Possibly repeating parameter
            if (jMethod.isVarArgs() && ModelUtil.isArray(lastType)) {
               Object[] newArgValues = new Object[types.length];
               for (int i = 0; i < last; i++) {
                  newArgValues[i] = argValues[i];
               }

               // Use the type of the expression, not the type of the value to emulate compile time binding
               if (!ModelUtil.isAssignableFrom(lastType, arguments.get(arguments.size()-1).getTypeDeclaration())) {
                  Object[] array = (Object[]) Array.newInstance(lastType.getComponentType(), arguments.size() - last);
                  for (int i = last; i < argValues.length; i++)
                     array[i - last] = argValues[i];
                  newArgValues[last] = array;
                  argValues = newArgValues;
               }
               else if (last >= arguments.size() && newArgValues[last] == null) {
                  newArgValues[last] = Array.newInstance(lastType.getComponentType(), 0);
                  argValues = newArgValues;
               }
            }
         }
         return TypeUtil.invokeMethod(thisObject, jMethod, argValues);
      }
      // Interpreted
      else if (method instanceof AbstractMethodDefinition) {
         AbstractMethodDefinition rtmeth = (AbstractMethodDefinition) method;

         if (!rtmeth.isDynMethod()) {
            Object realRTMethod = rtmeth.getRuntimeMethod();
            if (realRTMethod != rtmeth) {
               return invokeMethodWithValues(thisObject, realRTMethod, arguments, expectedType, ctx, repeatArgs, findMethodOnThis, argValues);
            }
         }


         // TODO: handle repeating arguments
         boolean isStatic = false;
         try {
            if (thisObject != null) {
               if (DynUtil.isType(thisObject)) {
                  ctx.pushStaticFrame(thisObject);
                  isStatic = true;
               }
               else
                  ctx.pushCurrentObject(thisObject);

               if (!isStatic && findMethodOnThis) {
                  Object concreteType = getObjectsType(thisObject);
                  Object overMeth = ModelUtil.definesMethod(concreteType, rtmeth.name, rtmeth.getParameterList(), null, null, false);

                  if (overMeth != rtmeth && overMeth != null) {
                     return invokeMethodWithValues(thisObject, overMeth, arguments, expectedType, ctx, repeatArgs, findMethodOnThis, argValues);
                  }
               }
            }
            return rtmeth.invoke(ctx, Arrays.asList(argValues));
         }
         finally {
            if (thisObject != null) {
               if (isStatic)
                  ctx.popStaticFrame();
               else
                  ctx.popCurrentObject();
            }
         }
      }
      else if (method instanceof CFMethod) {
         Method rtMeth = ((CFMethod) method).getRuntimeMethod();

         if (rtMeth == null)
            throw new IllegalArgumentException("Can't invoke method: " + method + " No runtime class found");

         return ModelUtil.invokeMethod(thisObject, rtMeth, arguments, expectedType, ctx, repeatArgs, findMethodOnThis);
      }
      else if (method instanceof ParamTypedMethod) {
         return invokeMethod(thisObject, ((ParamTypedMethod) method).method, arguments, expectedType, ctx, repeatArgs, findMethodOnThis);
      }
      else
         throw new UnsupportedOperationException();
   }

   /** Invokes a method given a list of arguments, the Method object and args to eval.  Handles repeated arguments */
   public static Object invokeMethod(Object thisObject, Object method, List<Expression> arguments,
                                     Class expectedType, ExecutionContext ctx, boolean repeatArgs, boolean findMethodOnThis) {
      Object[] argValues = expressionListToValues(arguments, ctx);

      return invokeMethodWithValues(thisObject, method, arguments, expectedType, ctx, repeatArgs, findMethodOnThis, argValues);
   }

   public static boolean isComponentType(Object type) {
      if (type instanceof Class) {
         Class cl = (Class) type;
         return IComponent.class.isAssignableFrom(cl) || IAltComponent.class.isAssignableFrom(cl);
      }
      else if (type instanceof ITypeDeclaration) {
         ITypeDeclaration td = (ITypeDeclaration) type;
         return td.isComponentType();
      }
      return false;
   }

   /** Fields in the original model might get converted to get/set */
   public static boolean needsGetSet(Object boundType) {
      // The identifier can resolve to a "field" but the param typed member is a get method
      if (boundType instanceof ParamTypedMember) {
         boundType = ((ParamTypedMember) boundType).getMemberObject();
         return boundType instanceof MethodDefinition &&
                 ((MethodDefinition) boundType).hasGetMethod();
      }
      if (boundType instanceof PropertyAssignment)
         boundType = ((PropertyAssignment) boundType).getAssignedProperty();
      VariableDefinition varDef;
      return (boundType instanceof VariableDefinition) && ((varDef = (VariableDefinition) boundType).needsGetSet() || varDef.isDynamicType());
   }

   public static boolean needsSet(Object boundType) {
      // The identifier can resolve to a "field" but the param typed member is a get method
      if (boundType instanceof ParamTypedMember) {
         boundType = ((ParamTypedMember) boundType).getMemberObject();
         return boundType instanceof MethodDefinition &&
                 ((MethodDefinition) boundType).hasSetMethod();
      }
      if (boundType instanceof PropertyAssignment)
         boundType = ((PropertyAssignment) boundType).getAssignedProperty();
      VariableDefinition varDef;
      return (boundType instanceof VariableDefinition) && ((varDef = (VariableDefinition) boundType).needsGetSet() || varDef.isDynamicType());
   }

   /** Returns true if this property is marked as bindable as a type-level annotation.  Use isBindable(Object propObj) to check for annotations on the property object itself. */
   public static boolean isBindable(LayeredSystem system, Object parentType, String propName) {
      // Search the class hierarchy for all TypeSettings annotations and if any of them declare this property bindable at the type level
      // we treat it as bindable.
      ArrayList<Object> typeSetAnnots = ModelUtil.getAllInheritedAnnotations(system, parentType, "sc.obj.TypeSettings", false, null, false);
      if (typeSetAnnots != null) {
         for (Object typeSetAnnot:typeSetAnnots) {
            String[] bindablePropNames = (String[]) ModelUtil.getAnnotationValue(typeSetAnnot, "bindableProps");
            if (bindablePropNames != null) {
               for (String bp:bindablePropNames)
                  if (bp.equals(propName))
                     return true;
            }

         }
      }
      return false;
   }

   public static boolean isBindable(Object assignedProperty) {
      if (assignedProperty instanceof VariableDefinition) {
         return ((VariableDefinition) assignedProperty).needsBindable();
      }
      if (assignedProperty == null)
         return false;
      return getAnnotation(assignedProperty, "sc.bind.Bindable") != null;
   }

   public static boolean isManualBindable(Object assignedProperty) {
      Object bindAnnot = getAnnotation(assignedProperty, "Bindable");
      return bindAnnot != null && !ModelUtil.isAutomaticBindingAnnotation(bindAnnot);
   }

   public static boolean isAutomaticBindable(Object assignedProperty) {
      if (assignedProperty instanceof VariableDefinition) {
         VariableDefinition vdef = (VariableDefinition) assignedProperty;
         return vdef.needsBindable() && vdef.needsGetSet();
      }
      if (assignedProperty instanceof Field) {
         return false;
      }
      if (assignedProperty == null)
         return false;
      Object bindAnnot = getAnnotation(assignedProperty, "Bindable");
      return bindAnnot != null && ModelUtil.isAutomaticBindingAnnotation(bindAnnot);
   }

   public static boolean isArrayLength(Object assignedProperty) {
      if (assignedProperty instanceof ArrayLengthBeanMapper)
         return true;

      if (assignedProperty == ArrayTypeDeclaration.LENGTH_FIELD)
         return true;

      return false;
   }

   public static boolean isConstant(Object assignedProperty) {
      if (assignedProperty instanceof BeanMapper && ((BeanMapper) assignedProperty).isConstant())
         return true;

      boolean isField;
      if ((isField = ModelUtil.isField(assignedProperty)) && ModelUtil.hasModifier(assignedProperty, "final"))
         return true;

      if (ModelUtil.isObjectType(assignedProperty))
         return true;

      Object annotation = getAnnotation(assignedProperty, "sc.obj.Constant");
      if (annotation == null && !isField && !(assignedProperty instanceof PropertyAssignment)) {
         if (!(assignedProperty instanceof IBeanMapper) && ModelUtil.isGetMethod(assignedProperty)) {
            Object setMethod = ModelUtil.getSetMethodFromGet(assignedProperty);
            if (setMethod != null)
               annotation = ModelUtil.getAnnotation(setMethod, "sc.obj.Constant");
         }
      }
      if (annotation == null) {
         // It's possible we should treat getX without setX as constant by default?   I suspect the values of those
         // will change but we would not be able to detect that by default anyway.
         return false;
      }

      Boolean b = (Boolean) getAnnotationValue(annotation, "value");
      if (b == null)
         return true;
      return b;
   }

   public static Class getCompiledClass(Object fieldType) {
      TypeDeclaration typeDecl;
      // Skips any types defined in annotation layers
      while (fieldType instanceof TypeDeclaration && (typeDecl = (TypeDeclaration) fieldType).getJavaModel().isAnnotationModel())
         fieldType = typeDecl.getDerivedTypeDeclaration();

      if (fieldType instanceof ITypeDeclaration)
         return ((ITypeDeclaration) fieldType).getCompiledClass();
      else if (fieldType instanceof Class)
         return (Class) fieldType;
      else if (fieldType instanceof TypeParameter)
         return getCompiledClass(((TypeParameter) fieldType).getTypeDeclaration());
      else if (hasTypeParameters(fieldType))
         return getCompiledClass(getParamTypeBaseType(fieldType));
      else if (fieldType == null)
         return null;
      else if (fieldType instanceof java.lang.reflect.Type) {
         return typeToClass((java.lang.reflect.Type) fieldType);
      }
      throw new UnsupportedOperationException();
   }

   public static Object getInnerType(Object type, String innerTypeName, TypeContext ctx) {
      int ix;
      do {
         Object nextType = null;
         ix = innerTypeName.indexOf(".");
         String nextTypeName;
         if (ix != -1) {
            nextTypeName = innerTypeName.substring(0, ix);
            innerTypeName = innerTypeName.substring(ix+1);
         }
         else {
            nextTypeName = innerTypeName;
         }
         if (type instanceof ITypeDeclaration) {
            nextType = ((ITypeDeclaration) type).getInnerType(nextTypeName, ctx);
         }
         else if (type instanceof Class) {
            nextType = RTypeUtil.getInnerClass((Class) type, nextTypeName);
         }
         else if (type instanceof TypeParameter) {
            nextType = getInnerType(((TypeParameter) type).getTypeDeclaration(), nextTypeName, ctx);
         }
         else if (type != null)
            throw new UnsupportedOperationException();
         if (nextType == null) 
            return null;
         type = nextType;
      } while (ix != -1);
      return type;
   }

   public static Object[] getAllInnerTypes(Object type, String modifier, boolean thisClassOnly) {
      if (type instanceof Class)
         return RTypeUtil.getAllInnerClasses((Class) type, modifier); // TODO: implement thisClassOnly - currently not needed for compiled types
      else if (type instanceof ITypeDeclaration) {
         List<Object> types = ((ITypeDeclaration) type).getAllInnerTypes(modifier, thisClassOnly);
         return types == null ? null : types.toArray(new Object[types.size()]);
      }
      else
         return null;
   }

   public static String getCompiledClassName(Object type) {
      if (type instanceof ITypeDeclaration) {
         return ((ITypeDeclaration) type).getCompiledClassName();
      }
      return getTypeName(type);
   }

   public static String getCompiledTypeName(Object type) {
      String dims = null;
      if (type instanceof ITypeDeclaration) {
         return ((ITypeDeclaration) type).getCompiledTypeName();
      }
      return getTypeName(type, true, true);
   }

   public static String getRuntimeTypeName(Object type) {
      if (type instanceof TypeDeclaration) {
         TypeDeclaration typeDecl = (TypeDeclaration) type;
         return typeDecl.getRuntimeTypeName();
      }
      return getTypeName(type);
   }

   public static String modifiersToString(Object definition, boolean includeAnnotations, boolean includeAccess, boolean includeFinal, boolean includeScopes, boolean abs, JavaSemanticNode.MemberType type) {
      if (definition instanceof VariableDefinition)
         definition = ((VariableDefinition) definition).getDefinition();
      if (definition instanceof IDefinition) {
         return ((IDefinition) definition).modifiersToString(includeAnnotations, includeAccess, includeFinal, includeScopes, abs, type);
      }
      else {
         if (definition instanceof IBeanMapper)
            definition = ((IBeanMapper) definition).getPropertyMember();

         String str = Modifier.toString(getModifiers(definition));
         if (includeAnnotations) {
            // TODO: need to add this code
         }
         return str;
      }
   }

   /**
    * These next three functions take info from a method and turn it into a property name if it is a property of
    * this type.  Note that we can't use getTypeDeclaration here since we need this info before we start the
    * component so all determinations must be done using the type name.
    */
   public static String isGetMethod(String name, Object[] parameterTypes, Object returnType) {
      String typeName;
      if (name.startsWith("get") || name.startsWith("is")) {
         if (returnType != null && ModelUtil.typeIsVoid(returnType))
            return null;
         if (parameterTypes == null || parameterTypes.length == 0 ||
             (parameterTypes.length == 1 && name.startsWith("get") && typeIsBoolean(parameterTypes[0])))
           return CTypeUtil.decapitalizePropertyName(name.charAt(0) == 'g' ? name.substring(3) : name.substring(2));
      }
      return null;
   }

   public static String convertGetMethodName(String name) {
      String rest = null;
      if (name.startsWith("get")) {
         rest = name.substring(3);
      }
      else if (name.startsWith("is")) {
         rest = name.substring(2);
      }
      if (rest != null && rest.length() > 0) {
         return CTypeUtil.decapitalizePropertyName(rest);
      }
      return null;
   }

   /** For set methods, we allow both void setX(int) and "? setX(int)".  Some folks may need int setX(int) and others
    *  do this setX(y) to chain methods. */
   public static String isSetMethod(String name, Object[] paramJavaTypes, Object returnType) {
      if (name.startsWith("set") && name.length() >= 4 && paramJavaTypes != null && (paramJavaTypes.length == 1 ||
          (paramJavaTypes.length == 2 && isInteger(paramJavaTypes[0])))
              /*
              && (returnType == null || ModelUtil.typeIsVoid(returnType) ||
               ModelUtil.getTypeName(returnType).equals(ModelUtil.getTypeName(paramJavaTypes[0])))
               */
              ) {
         return CTypeUtil.decapitalizePropertyName(name.substring(3));
      }
      return null;
   }

   public static boolean isSetMethod(Object method) {
      if (method instanceof IMethodDefinition)
         return ((IMethodDefinition) method).isSetMethod();
      else if (method instanceof Method)
         return RTypeUtil.isSetMethod((Method) method);
      else
         throw new UnsupportedOperationException();
   }

   public static boolean isGetMethod(Object method) {
      if (method instanceof ParamTypedMember)
         return isGetMethod(((ParamTypedMember) method).getMemberObject());
      if (method instanceof IMethodDefinition)
         return ((IMethodDefinition) method).isGetMethod();
      if (method instanceof IBeanMapper || method instanceof VariableDefinition || method instanceof FieldDefinition || method instanceof Field) // Returning false here... isConstant already checks both get and set for mappers so it's not a get method for that purpose.
         return false;
      else if (method instanceof Method)
         return RTypeUtil.isGetMethod((Method) method);
      else if (method instanceof ITypeDeclaration)
         return false;
      else if (method instanceof PropertyAssignment)
         return isGetMethod(((PropertyAssignment) method).getAssignedProperty());
      else
         throw new UnsupportedOperationException();
   }

   public static boolean isGetIndexMethod(Object method) {
      if (method instanceof IMethodDefinition)
         return ((IMethodDefinition) method).isGetIndexMethod();
      else if (method instanceof Method)
         return RTypeUtil.isGetIndexMethod((Method) method);
      else
         throw new UnsupportedOperationException();
   }

   public static boolean isSetIndexMethod(Object method) {
      if (method instanceof IMethodDefinition)
         return ((IMethodDefinition) method).isSetIndexMethod();
      else if (method instanceof Method)
         return RTypeUtil.isSetIndexMethod((Method) method);
      else
         throw new UnsupportedOperationException();
   }

   public static String isSetIndexMethod(String name, Object[] paramJavaTypes, Object returnType) {
      if (name.startsWith("set") && paramJavaTypes != null && paramJavaTypes.length == 2 &&
          (returnType == null || ModelUtil.typeIsVoid(returnType)) &&
          ModelUtil.typeIsInteger(paramJavaTypes[0])) {
         return CTypeUtil.decapitalizePropertyName(name.substring(3));
      }
      return null;
   }

   public static String isGetIndexMethod(String name, Object[] paramJavaTypes, Object returnType) {
      if (name.startsWith("get") && paramJavaTypes != null && paramJavaTypes.length == 1 &&
              (returnType != null && !ModelUtil.typeIsVoid(returnType)) &&
              ModelUtil.typeIsInteger(paramJavaTypes[0])) {
         return CTypeUtil.decapitalizePropertyName(name.substring(3));
      }
      return null;
   }

   public static boolean typeIsVoid(Object type) {
      if (type instanceof JavaType)
         return ((JavaType) type).isVoid();
      return type == Void.class || type == Void.TYPE;
   }

   public static boolean typeIsInteger(Object type) {
      if (type instanceof JavaType) {
         String typeName = ((JavaType) type).getFullTypeName();
         return typeName != null && (typeName.equals("java.lang.Integer") || typeName.equals("int"));
      }
      return type == Void.class || type == Void.TYPE;
   }

   public static boolean typeIsBoolean(Object type) {
      if (type instanceof JavaType) {
         String typeName = ((JavaType) type).getFullTypeName();
         return typeName != null && (typeName.equals("java.lang.Boolean") || typeName.equals("boolean"));
      }
      return type == Void.class || type == Void.TYPE;
   }

   public static boolean typeIsString(Object type) {
      if (type instanceof JavaType) {
         String typeName = ((JavaType) type).getFullTypeName();
         return typeName != null && (typeName.equals("java.lang.String") || typeName.equals("String"));
      }
      return type == String.class;
   }

   public static boolean typeIsStringArray(Object type) {
      if (type instanceof JavaType) {
         String typeName = ((JavaType) type).getFullTypeName();
         return typeName != null && (typeName.equals("java.lang.String[]") || typeName.equals("String[]"));
      }
      return type == String.class;
   }

   public static String toDefinitionString(Object typeObj) {
      if (typeObj instanceof SemanticNode)
         return ((SemanticNode) typeObj).toDefinitionString();
      return typeObj.toString();
   }

   public static String argumentsToString(List<? extends ITypedObject> arguments) {
      if (arguments == null)
         return "(null)";
      StringBuilder sb = new StringBuilder();
      boolean first = true;
      sb.append("(");
      for (ITypedObject to:arguments) {
         if (first)
            first = false;
         else
            sb.append(",");
         Object type;
         if (to == null || (type = to.getTypeDeclaration()) == null)
            sb.append("null");
         else {
            sb.append(to.getAbsoluteGenericTypeName(null, true));
            //sb.append(ModelUtil.getTypeName(type));
         }
      }
      sb.append(")");
      return sb.toString();
   }

   // Should take a list of ITypedObject as the parameter type
   public static Object[] listToTypes(List<?> list) {
      if (list == null)
         return null;

      Object[] types = new Object[list.size()];
      for (int i = 0; i < types.length; i++)
         types[i] = ((ITypedObject)list.get(i)).getTypeDeclaration();
      return types;
   }

   public static Object[] varListToTypes(List<?> list) {
      if (list == null)
         return null;

      Object[] types = new Object[list.size()];
      for (int i = 0; i < types.length; i++) {
         Object t = list.get(i);
         if (t != null)
            types[i] = ModelUtil.getVariableTypeDeclaration(t);// TODO: performance: this method is probably slow
      }
      return types;
   }

   public static boolean hasParameterizedReturnType(Object method) {
      if (method instanceof Method) {
         Type returnType = ((Method) method).getGenericReturnType();
         return returnType instanceof TypeVariable;
      }
      else if (method instanceof IMethodDefinition) {
         Object retType = ((IMethodDefinition) method).getReturnType();
         return retType instanceof TypeParameter || ModelUtil.hasUnboundTypeParameters(retType);
      }
      throw new UnsupportedOperationException();
   }

   /**
    * If we are looking for a type with information about type parameters, we need to use a different
    * method for the native Class/Method etc. as they do not store the type param stuff in the same place.
    */
   public static Object getParameterizedType(Object member, JavaSemanticNode.MemberType type) {
      if (type == JavaSemanticNode.MemberType.SetMethod) {
         if (member instanceof IMethodDefinition) {
            Object[] paramTypes = ((IMethodDefinition) member).getParameterTypes();
            return paramTypes[0];
         }
         else if (member instanceof IBeanMapper)
            return ((IBeanMapper) member).getGenericType();
         else if (member instanceof Method) {
            Type[] parameterTypes = ((Method) member).getGenericParameterTypes();
            if (parameterTypes.length != 1)
               throw new IllegalArgumentException("Set method with wrong number of parameters: " + member);
            return parameterTypes[0];
         }
      }
      else if (type == JavaSemanticNode.MemberType.GetMethod || type == JavaSemanticNode.MemberType.Field || type == JavaSemanticNode.MemberType.Variable) {
         if (member instanceof Field)
            return ((Field) member).getGenericType();
         else if (member instanceof ITypedObject)
            return ((ITypedObject) member).getTypeDeclaration();
         else if (member instanceof IBeanMapper)
            return ((IBeanMapper) member).getGenericType();
         else if (member instanceof ITypeDeclaration)
            return member;
         else if (member instanceof Method)
            return ((Method) member).getGenericReturnType();
         else if (member instanceof IMethodDefinition)
            return ((IMethodDefinition) member).getReturnType();
         else if (member instanceof Class)
            return member;
      }
      else if (type == JavaSemanticNode.MemberType.ObjectType) {
         return member;
      }
      if (member instanceof ParamTypedMember)
         return member;
      throw new UnsupportedOperationException();
   }

   public static Object getParameterizedReturnType(Object method) {
      if (method instanceof Method)
         return ((Method) method).getGenericReturnType();
      else if (method instanceof IMethodDefinition)
         return ((IMethodDefinition) method).getReturnType();
      throw new UnsupportedOperationException();
   }

   // This defines a more general form
   public static boolean isParameterizedType(Object type) {
      // TODO: should we include ClassTypes here with type arguments?
      return type instanceof TypeParameter || type instanceof ParameterizedType || type instanceof TypeVariable;
   }

   public static boolean isTypeVariable(Object type) {
      return type instanceof TypeParameter || type instanceof TypeVariable;
   }

   public static boolean hasParameterizedType(Object member, JavaSemanticNode.MemberType type) {
      return isParameterizedType(getParameterizedType(member, type));
   }

   public static String getTypeParameterString(Object type) {
      List<?> tps = ModelUtil.getTypeParameters(type);
      if (tps == null)
         return "";
      StringBuilder sb = null;
      for (Object o:tps) {
         if (sb == null) {
            sb = new StringBuilder();
            sb.append("<");
         }
         else
            sb.append(", ");
         sb.append(ModelUtil.getTypeParameterName(o));
      }
      if (sb != null) {
         sb.append(">");
         return sb.toString();
      }
      return "";
   }

   /** Returns TypeParameter or TypeVariable */
   public static List<?> getTypeParameters(Object type) {
      if (type instanceof ITypeDeclaration)
         return ((ITypeDeclaration) type).getClassTypeParameters();
      else if (type instanceof Class)
         return Arrays.asList(((Class) type).getTypeParameters());
      else if (type instanceof TypeParameter)
         return null;
      else if (type instanceof TypeVariable)
         return null;
      else if (type instanceof ParameterizedType)
         return getTypeParameters(((ParameterizedType) type).getRawType());
      else
         throw new UnsupportedOperationException();
   }

   public static String getTypeParameterName(Object typeParam) {
      if (typeParam instanceof TypeParameter)
         return ((TypeParameter) typeParam).name;
      else if (typeParam instanceof TypeVariable) {
         TypeVariable tvar = (TypeVariable) typeParam;
         return tvar.getName();
      }
      else if (typeParam instanceof Class) {
         return null;
      }
      else
         throw new UnsupportedOperationException();
   }

   public static Object getTypeParameterDefault(Object typeParam) {
      if (typeParam instanceof TypeParameter) {
         return ((TypeParameter) typeParam).getTypeDeclaration();
      }
      else if (typeParam instanceof TypeVariable) {
         TypeVariable tv = (TypeVariable) typeParam;
         GenericDeclaration genericDecl = tv.getGenericDeclaration();
         return tv.getBounds()[0];
      }
      else
         throw new UnsupportedOperationException();
   }

   public static int getTypeParameterPosition(Object typeParam) {
      if (typeParam instanceof TypeParameter)
         return ((TypeParameter) typeParam).getPosition();
      else if (typeParam instanceof TypeVariable) {
         TypeVariable tvar = (TypeVariable) typeParam;
         return arrayIndexOf(tvar.getGenericDeclaration().getTypeParameters(), tvar);
      }
      else
         throw new UnsupportedOperationException();
   }

   public static int getTypeParameterPosition(Object rootType, String typeParamName) {
      List<?> typeParameters = ModelUtil.getTypeParameters(rootType);
      if (typeParameters != null) {
         int ix = 0;
         for (Object tp:typeParameters) {
            if (ModelUtil.getTypeParameterName(tp).equals(typeParamName))
               return ix;
            ix++;
         }
      }
      return -1;
   }

   private static int arrayIndexOf(Object[] array, Object element) {
      for (int i = 0; i < array.length; i++)
         if (array[i] == element)
            return i;
      return -1;
   }

   public static String getGenericTypeName(Object resultType, Object member, boolean includeDims) {
      if (member instanceof Member) {
         return RTypeUtil.getGenericTypeName(resultType, member, includeDims);
      }
      else if (member instanceof IBeanMapper) {
         Object pVal = ((IBeanMapper) member).getGenericType();
         String res = resolveGenericTypeName(getEnclosingType(member), resultType, pVal, includeDims);
         if (res == null)
            return "Object";
         return res;
      }
      else if (member instanceof ITypedObject) {
         return ((ITypedObject) member).getGenericTypeName(resultType, includeDims);
      }
      else
         throw new IllegalArgumentException();
   }

   public static String resolveGenericTypeName(Object srcType, Object resultType, Object genType, boolean includeDims) {
      if (genType instanceof GenericArrayType) {
         GenericArrayType gat = (GenericArrayType) genType;
         String componentName = resolveGenericTypeName(srcType, resultType, gat.getGenericComponentType(), includeDims);
         if (includeDims)
            return componentName + "[]";
         else
            return componentName;
      }
      if (genType instanceof ParameterizedType) {
         ParameterizedType pt = (ParameterizedType) genType;
         java.lang.reflect.Type[] args = pt.getActualTypeArguments();
         StringBuilder sb = new StringBuilder();
         sb.append(resolveGenericTypeName(srcType, resultType, pt.getRawType(), includeDims));
         boolean tpErased = false;
         StringBuilder tpNames = new StringBuilder();
         boolean first = true;
         for (java.lang.reflect.Type at:args) {
            // Do not propagate includeDims inside of the <> since it is part of the type args then
            Object tpName = resolveGenericTypeName(srcType, resultType, at, true);
            if (tpName == null) {
               // Do all args need to be unmapped for us to erase the type parameters?
               tpErased = true;
               break;
            }
            if (first)
               first = false;
            else
               tpNames.append(",");
            tpNames.append(tpName);
         }
         if (!tpErased) {
            sb.append("<");
            sb.append(tpNames);
            sb.append(">");
         }
         return sb.toString();
      }
      else if (genType instanceof Class) {
         Class cl = (Class) genType;
         if (cl.isArray()) {
            Class componentClass = cl.getComponentType();
            String componentName = resolveGenericTypeName(srcType, resultType, componentClass, includeDims);
            if (includeDims)
               return componentName + "[]";
            else
               return componentName;
         }
         return ((Class) genType).getName();
      }
      else if (ModelUtil.isTypeVariable(genType)) {
         String varName = ModelUtil.getTypeParameterName(genType);

         Object res = ModelUtil.resolveTypeParameter(srcType, resultType, varName);
         if (res == null)
            return null;
         if (ModelUtil.isTypeVariable(res))
            return ModelUtil.getTypeParameterName(res);
         else if (res instanceof String)
            return (String) res;
         else
            return resolveGenericTypeName(srcType, resultType, res, true);
      }
      else if (genType instanceof ITypeDeclaration) {
         return ((ITypeDeclaration) genType).getFullBaseTypeName();
      }
      else if (genType instanceof JavaType) {
         return ((JavaType) genType).getGenericTypeName(resultType, includeDims);
      }
      else
         throw new UnsupportedOperationException();
   }

   public static List<Object> getExtendsJavaTypePath(Object srcType, Object resultType) {
      List<Object> res = null;
      while (resultType != null && srcType != resultType) {
         if (res == null)
            res = new ArrayList<Object>();

         Object next = ModelUtil.getSuperclass(resultType);
         if (next != null)
            res.add(ModelUtil.getExtendsJavaType(resultType));
         resultType = next;
      }
      if (resultType != srcType)
         System.out.println("*** did not find expected path between types");
      return res;
   }

   public static Object resolveTypeParameter(Object srcType, Object resultType, String typeParamName) {
      int srcIx = ModelUtil.getTypeParameterPosition(srcType, typeParamName);
      if (srcIx == -1) {
         System.err.println("*** Unresolveable type parameter!");
         return null;
      }
      List<Object> extTypes = getExtendsJavaTypePath(srcType, resultType);
      if (extTypes == null || extTypes.size() == 0)
         return getTypeParameters(srcType).get(srcIx);

      Object nextType;
      for (int i = extTypes.size()-1; i >= 0; i--) {
         Object ext = extTypes.get(i);
         List<?> typeArgs = ModelUtil.getTypeArguments(ext);
         if (typeArgs == null)
            return null;
         Object typeArg = typeArgs.get(srcIx);
         if (ModelUtil.isTypeVariable(typeArg)) {
            typeParamName = ModelUtil.getTypeParameterName(typeArg);

            if (i == 0)
               nextType = resultType;
            else {
               nextType = extTypes.get(i-1);
               if (nextType instanceof JavaType)
                  nextType = ((JavaType) nextType).getTypeDeclaration();
            }

            srcIx = ModelUtil.getTypeParameterPosition(nextType, typeParamName);
         }
         else
            return typeArg;
      }
      return typeParamName;
   }

   public static List<?> getTypeArguments(Object extJavaType) {
      if (extJavaType instanceof JavaType) {
         return ((JavaType) extJavaType).getResolvedTypeArguments();
      }
      else if (extJavaType instanceof Type) {
         System.out.println("*** TODO: retrieve typeArguments from compiled type");
      }
      else
         throw new UnsupportedOperationException();
      return null;
   }

   public static String getAbsoluteGenericTypeName(Object resultType, Object member, boolean includeDims) {
      if (member instanceof Member) {
         return RTypeUtil.getGenericTypeName(resultType, member, includeDims);
      }
      else if (member instanceof IBeanMapper) {
         return ((IBeanMapper) member).getGenericTypeName(resultType, includeDims);
      }
      else if (member instanceof ITypedObject) {
         return ((ITypedObject) member).getAbsoluteGenericTypeName(resultType, includeDims);
      }
      else
         throw new IllegalArgumentException();
   }

   public static Object definesMember(Object type, String name, EnumSet<JavaSemanticNode.MemberType> mtypes, Object refType, TypeContext ctx) {
      return definesMember(type, name, mtypes, refType, ctx, false, false);
   }

   public static Object definesMember(Object type, String name, EnumSet<JavaSemanticNode.MemberType> mtypes, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      Object res;
      if (type instanceof ITypeDeclaration) {
         if ((res = ((ITypeDeclaration) type).definesMember(name, mtypes, refType, ctx, skipIfaces, isTransformed)) != null)
            return res;
      }
      else if (type instanceof TypeParameter) {
         // TODO... feret out more type info about the type parameter based on the TypeParamContext and
         // the extends and super things in type param's definition's definition.
         return null;
      }
      else {
         // No way to pull property assignments out of class definitions now
         if (mtypes.contains(JavaSemanticNode.MemberType.Initializer))
            return null;

         Class classDecl = (Class) type;
         IBeanMapper mapper;
         IBeanMapper rm;
         for (JavaSemanticNode.MemberType mtype:mtypes) {
            switch (mtype) {
               case Field:
                  // Java does not let us reflect the length field of an array so we need to handle
                  // that special case here
                  if (name.equals("length") && classDecl.isArray())
                     return ArrayTypeDeclaration.LENGTH_FIELD;
                  if ((mapper = PTypeUtil.getPropertyMapping(classDecl, name)) != null && mapper.getField() != null &&
                       (refType == null || checkAccess(refType, mapper.getField())))
                     return mapper;

                  //Object theField;

                  //if ((res = RTypeUtil.getField(classDecl, name)) != null && ((refType == null || checkAccess(refType, res))))
                  //   return res;
                  //if ((res = TypeUtil.getPropertyMapping(classDecl, name, null, null)) != null &&
                  //        res instanceof IBeanMapper && (theField = ((IBeanMapper) res).getField()) != null &&
                  //        (refType == null || checkAccess(refType, theField))) {
                   //  if (RTypeUtil.getField(classDecl, name) != theField)
                   //     System.out.println("*** fields don';t match");
                   //  return theField;
                  //}

                  //if ((res = RTypeUtil.getField(classDecl, name)) != null && ((refType == null || checkAccess(refType, res))))
                  //   return res;

                  break;
               case GetMethod:
                  if ((res = TypeUtil.getPropertyMapping(classDecl, name, null, null)) != null &&
                       res instanceof IBeanMapper && (rm = (IBeanMapper) res).hasAccessorMethod() &&
                     (refType == null || checkAccess(refType, rm.getPropertyMember())))
                     return res;
                  break;
               case SetMethod:
                  if ((mapper = PTypeUtil.getPropertyMapping(classDecl, name)) != null &&
                       mapper.hasSetterMethod() && (refType == null || checkAccess(refType, mapper.getPropertyMember())))
                     return mapper;
                  break;
               case Enum:
                  if ((res = RTypeUtil.getEnum(classDecl, name)) != null)
                     return res;
                  break;
               case GetIndexed:
                  IBeanIndexMapper im;
                  if ((res = TypeUtil.getPropertyMapping(classDecl, name, null, null)) != null &&
                          res instanceof IBeanIndexMapper && (im = (IBeanIndexMapper) res).hasIndexedAccessorMethod() &&
                          (refType == null || checkAccess(refType, im.getPropertyMember())))
                     return res;
                  break;
               case SetIndexed:
                  if ((mapper = PTypeUtil.getPropertyMapping(classDecl, name)) != null && mapper instanceof IBeanIndexMapper &&
                          ((IBeanIndexMapper) mapper).hasIndexedSetterMethod() && (refType == null || checkAccess(refType, mapper.getPropertyMember())))
                     return mapper;
                  break;
            }
         }
      }
      return null;
   }

   public static boolean isTypeInLayer(Object type, Layer layer) {
      if (type instanceof BodyTypeDeclaration) {
         BodyTypeDeclaration td = (BodyTypeDeclaration) type;
         do {
            Layer typeLayer = td.getLayer();
            if (typeLayer == layer)
               return true;

            BodyTypeDeclaration nextType = td.getModifiedType();
            if (nextType == null)
               return false;
            td = nextType;
         } while (true);
      }
      return false;
   }

   public static Object getInheritedAnnotation(LayeredSystem system, Object superType, String annotationName) {
      return getInheritedAnnotation(system, superType, annotationName, false);
   }

   public static Object getInheritedAnnotationValue(LayeredSystem system, Object type, String annotName, String attributeName) {
      Object annot = getInheritedAnnotation(system, type, annotName);
      if (annot == null)
         return null;
      return ModelUtil.getAnnotationValue(annot, attributeName);
   }

   public static Object getInheritedAnnotation(LayeredSystem system, Object superType, String annotationName, boolean skipCompiled) {
      return getInheritedAnnotation(system, superType, annotationName, skipCompiled, null, false);
   }

   public static ArrayList<Object> getAllInheritedAnnotations(LayeredSystem system, Object superType, String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve) {
      if (superType instanceof ITypeDeclaration)
         return ((ITypeDeclaration) superType).getAllInheritedAnnotations(annotationName, skipCompiled, refLayer, layerResolve);
      else {
         ArrayList<Object> res = null;
         Class superClass = (Class) superType;
         Class annotationClass = RDynUtil.loadClass(annotationName);
         if (annotationClass == null) {
            annotationClass = RDynUtil.loadClass("sc.obj." + annotationName);
         }

         // TODO: fix annotation type name resolution problems
         if (annotationClass == null) {
            // Assuming layer dependencies are correct, compiled classes can't have non-compiled annotations so just return null (e.g. MainInit on a compiled class)
            return null;
         }
         while (superClass != null) {
            java.lang.annotation.Annotation jlannot = superClass.getAnnotation(annotationClass);
            if (jlannot != null) {
               if (res == null)
                  res = new ArrayList<Object>();
               res.add(jlannot);
            }

            // As we walk up the type hierarchy looking for annotations we need to see if there is
            // a source version for any of these types.  That way, you can add annotations to a type in
            // a modified layer without modifying every class that implements that type.
            Class next = superClass.getSuperclass();
            if (next != null) {
               Object nextType = findTypeDeclaration(system, next.getName(), refLayer, layerResolve);
               if (nextType != null && nextType instanceof TypeDeclaration) {
                  if (nextType == superType) {
                     System.err.println("*** Loop in inheritance tree: " + next.getName());
                     return null;
                  }
                  ArrayList<Object> newRes = ((TypeDeclaration) nextType).getAllInheritedAnnotations(annotationName, skipCompiled, refLayer, layerResolve);
                  if (newRes != null)
                     res = appendLists(res, newRes);
               }
            }
            Class[] ifaces = superClass.getInterfaces();
            for (Class iface:ifaces) {
               Object nextIFace = findTypeDeclaration(system, iface.getName(), refLayer, layerResolve);
               if (nextIFace != null && nextIFace instanceof TypeDeclaration) {
                   ArrayList<Object> newRes = ((TypeDeclaration) nextIFace).getAllInheritedAnnotations(annotationName, skipCompiled, refLayer, layerResolve);
                  res = appendLists(res, newRes);
               }
            }
            superClass = next;
         }
         return res;
      }
   }

   public static ArrayList<Object> appendLists(ArrayList<Object> origList, ArrayList<Object> newList) {
      if (origList == null)
         return newList;
      if (newList == null)
         return origList;
      ArrayList<Object> res = new ArrayList<Object>(origList.size() + newList.size());
      res.addAll(origList);
      res.addAll(newList);
      return res;
   }

   public static Object getInheritedAnnotation(LayeredSystem system, Object superType, String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve) {
      if (superType instanceof ITypeDeclaration)
         return ((ITypeDeclaration) superType).getInheritedAnnotation(annotationName, skipCompiled, refLayer, layerResolve);
      else {
         Class superClass = (Class) superType;
         Class annotationClass = RDynUtil.loadClass(annotationName);
         if (annotationClass == null) {
            annotationClass = RDynUtil.loadClass("sc.obj." + annotationName);
         }

         // TODO: fix annotation type name resolution problems
         if (annotationClass == null) {
            // Assuming layer dependencies are correct, compiled classes can't have non-compiled annotations so just return null (e.g. MainInit on a compiled class)
            return null;
         }
         while (superClass != null) {
            java.lang.annotation.Annotation jlannot = superClass.getAnnotation(annotationClass);
            if (jlannot != null)
               return jlannot;

            // As we walk up the type hierarchy looking for annotations we need to see if there is
            // a source version for any of these types.  That way, you can add annotations to a type in
            // a modified layer without modifying every class that implements that type.
            Class next = superClass.getSuperclass();
            if (next != null) {
               Object nextType = findTypeDeclaration(system, next.getName(), refLayer, layerResolve);
               if (nextType != null && nextType instanceof TypeDeclaration) {
                  if (nextType == superType) {
                     System.err.println("*** Loop in inheritance tree: " + next.getName());
                     return null;
                  }
                  return ((TypeDeclaration) nextType).getInheritedAnnotation(annotationName, skipCompiled, refLayer, layerResolve);
               }
            }
            Class[] ifaces = superClass.getInterfaces();
            for (Class iface:ifaces) {
               Object nextIFace = findTypeDeclaration(system, iface.getName(), refLayer, layerResolve);
               if (nextIFace != null && nextIFace instanceof TypeDeclaration)
                  return ((TypeDeclaration) nextIFace).getInheritedAnnotation(annotationName, skipCompiled, refLayer, layerResolve);
            }
            superClass = next;
         }
      }
      return null;
   }

   public static Object findTypeDeclaration(LayeredSystem sys, String typeName, Layer refLayer, boolean layerResolve) {
      if (sys == null)
         sys = LayeredSystem.getCurrent();
      return sys.getTypeDeclaration(typeName, false, refLayer, layerResolve);
   }

   public static Object resolveSrcTypeDeclaration(LayeredSystem sys, Object type) {
      return resolveSrcTypeDeclaration(sys, type, false, true);
   }

   /**
    * In some cases, you might have a class which corresponds to an annotation layer or something.  Use this
    * method to ensure you get the most specific src type for that class name.  If cachedOnly is true, we
    * do not load the src file just to retrieve the src description.  If you are using the runtime descriptions of the type
    * this can be a lot faster.  But if you have loaded the src, it's best to use the most accurate type.
    */
   public static Object resolveSrcTypeDeclaration(LayeredSystem sys, Object type, boolean cachedOnly, boolean srcOnly) {
      if (type instanceof ParamTypeDeclaration)
         type = ((ParamTypeDeclaration) type).getBaseType();
      if (type instanceof Class || type instanceof ParameterizedType) {
         String typeName = ModelUtil.getTypeName(type);
         if (sys != null) {
            Object res = cachedOnly ? sys.getCachedTypeDeclaration(typeName, null, null, false, true) : sys.getSrcTypeDeclaration(typeName, null, true, false, srcOnly);
            if (res != null)
               return res;
         }
      }
      return type;
   }

   /** For modify operations, returns the modified type declaration, i.e. with the same type name */
   public static Object getSuperclass(Object type) {
      if (type instanceof Class)
         return ((Class) type).getSuperclass();
      else if (type instanceof ITypeDeclaration)
         return ((ITypeDeclaration) type).getDerivedTypeDeclaration();
      else
         throw new UnsupportedOperationException();
   }

   /** For modify operations, returns the actual super class or extends class of the current type, i.e. never the same type name */
   public static Object getExtendsClass(Object type) {
      if (type instanceof Class)
         return ((Class) type).getSuperclass();
      else if (type instanceof ITypeDeclaration)
         return ((ITypeDeclaration) type).getExtendsTypeDeclaration();
      else
         throw new UnsupportedOperationException();
   }

   /** For modify types, if it's modify inherited it returns the modified type, since that's really a new type in the compiled world. */
   public static Object getCompiledExtendsTypeDeclaration(Object type) {
      if (type instanceof Class)
         return ((Class) type).getSuperclass();
      else if (type instanceof BodyTypeDeclaration)
         return ((BodyTypeDeclaration) type).getCompiledExtendsTypeDeclaration();
      else
         throw new UnsupportedOperationException();
   }

   public static Object getExtendsJavaType(Object type) {
      if (type instanceof Class)
         return ((Class) type).getGenericSuperclass();
      else if (type instanceof ITypeDeclaration)
         return ((ITypeDeclaration) type).getExtendsType();
      else
         throw new UnsupportedOperationException();
   }

   public static Object[] getCompiledImplTypes(Object type) {
      if (type instanceof Class)
         return ((Class) type).getInterfaces();
      else if (type instanceof BodyTypeDeclaration)
         return ((BodyTypeDeclaration) type).getCompiledImplTypes();
      else
         throw new UnsupportedOperationException();
   }

   public static Object[] getCompiledImplJavaTypes(Object type) {
      if (type instanceof Class)
         return ((Class) type).getGenericInterfaces();
      else if (type instanceof BodyTypeDeclaration)
         return ((BodyTypeDeclaration) type).getCompiledImplJavaTypes();
      else
         throw new UnsupportedOperationException();
   }

   public static String getOperator(Object fieldDef) {
      if (fieldDef instanceof VariableDefinition)
         return ((VariableDefinition) fieldDef).operator;
      if (fieldDef instanceof IBeanMapper)
         return "=";
      if (fieldDef instanceof Field)
         return "=";
      if (fieldDef instanceof IMethodDefinition)
         return "=";
      else if (fieldDef instanceof PropertyAssignment)
         return ((PropertyAssignment) fieldDef).operator;
      else if (fieldDef instanceof ParamTypedMember)
         return getOperator(((ParamTypedMember) fieldDef).getMemberObject());
      else if (fieldDef instanceof Method) {
         return "=";
      }
      else if (fieldDef instanceof EnumConstant) {
         return "=";
      }
      throw new UnsupportedOperationException("Invalid property type: " + fieldDef);
   }

   public static String getPropertyName(Object fieldDef) {
      if (fieldDef instanceof VariableDefinition)
         return ((VariableDefinition) fieldDef).variableName;
      if (fieldDef instanceof IBeanMapper)
         return ((IBeanMapper) fieldDef).getPropertyName();
      if (fieldDef instanceof Field)
         return ((Field) fieldDef).getName();
      if (fieldDef instanceof IMethodDefinition)
         return ((IMethodDefinition) fieldDef).getPropertyName();
      if (fieldDef instanceof IFieldDefinition)
         return ((IFieldDefinition) fieldDef).getFieldName();
      else if (fieldDef instanceof PropertyAssignment)
         return ((PropertyAssignment) fieldDef).propertyName;
      else if (fieldDef instanceof ParamTypedMember)
         return getPropertyName(((ParamTypedMember) fieldDef).getMemberObject());
      else if (fieldDef instanceof TypeDeclaration) {
         TypeDeclaration type = ((TypeDeclaration) fieldDef);
         String typeName = type.getTypeClassName();
         if (!(type.isEnumConstant()))
            return CTypeUtil.decapitalizePropertyName(typeName);
         return typeName;
      }
      else if (fieldDef instanceof Method) {
         return RTypeUtil.getPropertyNameFromSelector(fieldDef);
      }
      else if (fieldDef instanceof EnumConstant) {
         return ((EnumConstant) fieldDef).getTypeName();
      }
      // In the UI we use property name to refer to classes as well
      else if (fieldDef instanceof Class)
         return CTypeUtil.getClassName(((Class) fieldDef).getName());
      throw new UnsupportedOperationException("Invalid property type: " + fieldDef);
   }

   public static Layer getLayerForMember(LayeredSystem sys, Object member) {
      return getLayerForType(sys, getEnclosingType(member));
   }

   public static Layer getLayerForType(LayeredSystem sys, Object type) {
      if (type instanceof BodyTypeDeclaration)
         return ((BodyTypeDeclaration) type).getLayer();
      if (sys != null && type instanceof Class) {
         SrcEntry srcEnt = sys.getSrcFileFromTypeName(ModelUtil.getTypeName(type), true, null, true, null);
         if (srcEnt != null)
            return srcEnt.layer;
      }
      return null;
   }

   public static Layer getPropertyLayer(Object propDef) {
      if (propDef instanceof VariableDefinition)
         return ((VariableDefinition) propDef).getEnclosingType().getLayer();
      if (propDef instanceof Statement) {
         return ((Statement) propDef).getEnclosingType().getLayer();
      }
      if (propDef instanceof MethodDefinition)
         return ((MethodDefinition) propDef).getEnclosingType().getLayer();
      if (propDef instanceof IBeanMapper)
         return getPropertyLayer(((IBeanMapper) propDef).getPropertyMember());
      if (propDef instanceof ParamTypedMember)
         return getPropertyLayer(((ParamTypedMember) propDef).getMemberObject());
      return null;
   }

   public static boolean isStringOrChar(Object typeDeclaration) {
      return ModelUtil.isAssignableFrom(String.class, typeDeclaration) ||
             ModelUtil.isAssignableFrom(Character.class, typeDeclaration);
   }

   public static boolean isCompiledClass(Object type) {
      return type instanceof CFClass || type instanceof Class;
   }

   /** Returns true for EnumConstants */
   public static boolean isEnum(Object varObj) {
      if (varObj instanceof EnumConstant)
         return true;
      if (varObj instanceof CFField && ((CFField) varObj).isEnumConstant())
         return true;
      if (varObj instanceof ModifyDeclaration && ((ModifyDeclaration) varObj).isEnumConstant())
         return true;
      if (varObj instanceof DynEnumConstant)
         return true;
      if (varObj instanceof Field)
         return ((Field) varObj).isEnumConstant();
      if (varObj instanceof IBeanMapper) {
         Object field = ((IBeanMapper) varObj).getField();
         return field != null && isEnum(field);
      }
      return (varObj instanceof java.lang.Enum);
   }

   public static boolean isInterface(Object obj) {
      if (obj instanceof Class)
         return ((Class) obj).isInterface();
      else if (obj instanceof CFClass)
         return ((CFClass) obj).isInterface();
      else
         return obj instanceof TypeDeclaration && ((TypeDeclaration) obj).getDeclarationType() == DeclarationType.INTERFACE;
   }

   public static boolean isAnnotation(Object obj) {
      if (obj instanceof Class)
         return ((Class) obj).isAnnotation();
      else if (obj instanceof CFClass)
         return ((CFClass) obj).isAnnotation();
      return obj instanceof AnnotationTypeDeclaration;
   }

   public static boolean isEnumType(Object varObj) {
      return varObj instanceof EnumDeclaration || ((varObj instanceof Class) && ((Class) varObj).isEnum()) ||
             ((varObj instanceof CFClass) && ((CFClass) varObj).isEnum()) || (varObj instanceof ModifyDeclaration && isEnumType(((ModifyDeclaration) varObj).getModifiedType()));
   }

   public static Object getEnumTypeFromEnum(Object enumObj) {
      if (enumObj instanceof EnumConstant)
         return enumObj;
      if (enumObj instanceof CFField)
         return ((CFField) enumObj).getEnclosingIType();
      if (enumObj instanceof java.lang.Enum)
         return enumObj.getClass(); // TODO: this is a bit funky here... this returns the class for the specific enum constant wheras most of these return the parent enum type.  Should we use getDeclaringClass() to be more consistent?  But at least some callers, like identifier expression, seem to really need the type corresponding to the enum constant.
      if (enumObj instanceof EnumDeclaration) {
         return enumObj;
      }
      if (enumObj instanceof Field) {
         return ((Field) enumObj).getGenericType();
      }
      if (enumObj instanceof IBeanMapper)
         return ((IBeanMapper) enumObj).getGenericType();
      if (enumObj instanceof ModifyDeclaration) {
         ModifyDeclaration enumMod = (ModifyDeclaration) enumObj;
         if (enumMod.isEnumConstant())
            return enumMod.getEnclosingIType();
         System.err.println("*** Invalid type for enum");
         return null;
      }
      else
         return null;
   }

   public static String getMethodName(Object methObj) {
      if (methObj instanceof Method)
         return ((Method) methObj).getName();
      else if (methObj instanceof IMethodDefinition)
         return ((IMethodDefinition) methObj).getMethodName();
      // For constructors, we use just the class name part of the whole type name since methods cannot have that name
      else if (methObj instanceof Constructor)
         return CTypeUtil.getClassName(((Constructor) methObj).getName());
      else
         throw new UnsupportedOperationException();
   }

   public static String getThrowsClause(Object methObj) {
      if (methObj instanceof Constructor)
         return typesToThrowsClause(((Constructor) methObj).getExceptionTypes());
      else if (methObj instanceof Method) {
         return typesToThrowsClause(((Method) methObj).getExceptionTypes());
      }
      else if (methObj instanceof IMethodDefinition)
         return ((IMethodDefinition) methObj).getThrowsClause();
      // For constructors
      else if (methObj instanceof AbstractMethodDefinition)
         return ((AbstractMethodDefinition) methObj).getThrowsClause();
      else
         throw new UnsupportedOperationException();
   }

   public static IBeanMapper[] getPropertyMappers(Object typeObj) {
      if (typeObj instanceof Class) {
         return TypeUtil.getProperties((Class) typeObj, null);
      }
      else if (typeObj instanceof ITypeDeclaration) {
         DynType cache = ((ITypeDeclaration) typeObj).getPropertyCache();
         return cache.getPropertyList();
      }
      throw new UnsupportedOperationException();
   }

   public static IBeanMapper[] getStaticPropertyMappers(Object typeObj) {
      if (typeObj instanceof Class) {
         return TypeUtil.getStaticProperties((Class) typeObj);
      }
      else if (typeObj instanceof ITypeDeclaration) {
         DynType cache = ((ITypeDeclaration) typeObj).getPropertyCache();
         return cache.getStaticPropertyList();
      }
      throw new UnsupportedOperationException();
   }

   public static Object[] getDeclaredMergedPropertiesAndTypes(Object type, String modifier, boolean includeModified) {
      Object[] types = getAllInnerTypes(type, modifier, true);
      Object[] props = getDeclaredMergedProperties(type, modifier, includeModified);
      return mergeTypesAndProperties(type, types, props);
   }

   public static Object[] getMergedPropertiesAndTypes(Object type, String modifier) {
      Object[] types = getAllInnerTypes(type, modifier, false);
      Object[] props = getMergedProperties(type, modifier);
      return mergeTypesAndProperties(type, types, props);
   }

   /** Note: This merges the types but does not merge the properties.  Assumes that's been done in getMerged/DeclaredProperties */
   public static Object[] mergeTypesAndProperties(Object type, Object[] types, Object[] props) {
      ArrayList<Object> res = new ArrayList<Object>();
      if (props != null) {
         for (int i = 0; i < props.length; i++) {
            Object prop = props[i];
            if (prop instanceof Class || prop instanceof BodyTypeDeclaration)
               continue; // Objects already included above
            res.add(props[i]);
         }
      }
      if (types != null) {
         for (int i = 0; i < types.length; i++) {
            Object innerType = types[i];
            String innerTypeName = ModelUtil.getClassName(innerType);
            // If it's a compiled anonymous inner type, nothing we can do for it.
            if (Character.isDigit(innerTypeName.charAt(0)))
                 continue;
            Object mergedType = ModelUtil.getInnerType(type, innerTypeName, null);
            if (mergedType == null) {
               // Some inconsistency in how enum constants are treated here...
               mergedType = ModelUtil.getEnum(type, innerTypeName);
               if (mergedType == null)
                  System.err.println("*** can't find merged type!");
            }
            else
               types[i] = mergedType;
            res.add(types[i]);
         }
      }
      return res.toArray(new Object[res.size()]);
   }

   public static Object[] getDeclaredMergedProperties(Object type, String modifier, boolean includeModified) {
      Object[] props = getDeclaredProperties(type, modifier, true, includeModified);
      if (props == null)
         return new Object[0];
      for (int i = 0; i < props.length; i++) {
         Object prop = props[i];
         if (prop != null) {
            String propName = ModelUtil.getPropertyName(prop);
            Object def = ModelUtil.definesMember(type, propName, JavaSemanticNode.MemberType.AllSet, null, null);
            if (def == null) {
               System.err.println("*** Can't resolve merged property");
               def = ModelUtil.definesMember(type, propName, JavaSemanticNode.MemberType.AllSet, null, null);
            }
            // Reverse bindings do not replace the previous definition so don't let them do that here
            else if (!ModelUtil.isReverseBinding(def))
               props[i] = def;
         }
      }
      return props;
   }

   public static boolean isReverseBinding(Object def) {
      if (!(def instanceof PropertyAssignment))
         return false;

      PropertyAssignment pa = (PropertyAssignment) def;
      return pa.bindingDirection != null && !pa.bindingDirection.doForward() && pa.bindingDirection.doReverse();
   }

   public static Object[] getMergedProperties(Object type, String modifier) {
      Object[] props = getProperties(type, modifier);
      if (props != null) {
         for (int i = 0; i < props.length; i++) {
            Object prop = props[i];
            if (prop != null) {
               String propName = ModelUtil.getPropertyName(prop);
               Object def = ModelUtil.definesMember(type, propName, JavaSemanticNode.MemberType.AllSet, null, null);
               if (def == null) {
                  System.err.println("*** Can't resolve merged property: " + propName + " in type: " + ModelUtil.getTypeName(type));
                  def = ModelUtil.definesMember(type, propName, JavaSemanticNode.MemberType.AllSet, null, null);
               }
               else if (!ModelUtil.isReverseBinding(def))
                  props[i] = def;
            }
         }
      }
      return props;
   }

   public static Object[] getProperties(Object typeObj, String modifier) {
      return getProperties(typeObj, modifier, false);
   }

   public static Object[] getProperties(Object typeObj, String modifier, boolean includeAssigns) {
      if (typeObj instanceof Class) {
         return TypeUtil.getProperties((Class) typeObj, modifier);
      }
      else if (typeObj instanceof ITypeDeclaration) {
         List<Object> props = ((ITypeDeclaration) typeObj).getAllProperties(modifier, includeAssigns);
         return props == null ? null : props.toArray(new Object[props.size()]);
      }
      throw new UnsupportedOperationException();
   }

   public static Object[] getDeclaredProperties(Object typeObj, String modifier, boolean includeAssigns, boolean includeModified) {
      if (typeObj instanceof Class) {
         return RTypeUtil.getDeclaredProperties((Class) typeObj, modifier);
      }
      else if (typeObj instanceof BodyTypeDeclaration) {
         List<Object> props = ((BodyTypeDeclaration) typeObj).getDeclaredProperties(modifier, includeAssigns, includeModified);
         return props == null ? null : props.toArray(new Object[props.size()]);
      }
      throw new UnsupportedOperationException();
   }

   public static Object[] getDeclaredPropertiesAndTypes(Object typeObj, String modifier) {
      Object[] types = ModelUtil.getAllInnerTypes(typeObj, modifier, true);
      ArrayList<Object> res = new ArrayList();
      if (typeObj instanceof Class) {
         Object[] props = RTypeUtil.getDeclaredProperties((Class) typeObj, null);
         if (props != null) {
            for (int i = 0; i < props.length; i++) {
               Object prop = props[i];
               if (prop instanceof Class)
                  continue;
               res.add(prop);
            }
         }
      }
      else if (typeObj instanceof BodyTypeDeclaration) {
         List<Object> props = ((BodyTypeDeclaration) typeObj).getDeclaredProperties(null, true, false);
         if (props != null) {
            for (int i = 0; i < props.size(); i++) {
               Object prop = props.get(i);
               if (prop instanceof BodyTypeDeclaration)
                  continue;
               res.add(prop);
            }
         }
      }
      else
         throw new UnsupportedOperationException();
      if (types != null)
         res.addAll(Arrays.asList(types));
      return res.toArray();
   }

   public static Object[] getPropertiesAndTypes(Object typeObj, String modifier) {
      ArrayList<Object> res = new ArrayList();
      if (typeObj instanceof Class) {
         Object[] props = TypeUtil.getProperties((Class) typeObj, null);
         if (props != null) {
            for (int i = 0; i < props.length; i++) {
               Object prop = props[i];
               if (prop instanceof Class)
                  continue;
               res.add(prop);
            }
         }
      }
      else if (typeObj instanceof ITypeDeclaration) {
         List<Object> props = ((ITypeDeclaration) typeObj).getAllProperties(null, true);
         if (props != null) {
            for (int i = 0; i < props.size(); i++) {
               Object prop = props.get(i);
               if (prop instanceof BodyTypeDeclaration)
                  continue;
               res.add(prop);
            }
         }
      }
      else
         throw new UnsupportedOperationException();
      Object[] types = ModelUtil.getAllInnerTypes(typeObj, modifier, false);
      if (types != null)
         res.addAll(Arrays.asList(types));
      return res.toArray();
   }

   public static Object[] getFields(Object typeObj, String modifier, boolean hasModifier, boolean dynamicOnly, boolean includeObjs, boolean includeAssigns, boolean includeModified) {
      if (typeObj instanceof Class)
         return RTypeUtil.getFields((Class) typeObj, modifier, hasModifier);
      else if (typeObj instanceof ITypeDeclaration) {
         List<Object> props = ((ITypeDeclaration) typeObj).getAllFields(modifier, hasModifier, dynamicOnly, includeObjs, includeAssigns, includeModified);
         return props == null ? null : props.toArray(new Object[props.size()]);
      }
      throw new UnsupportedOperationException();
   }

   public static List removePropertiesInList(List declProps, List remProps, boolean byName) {
      if (remProps == null || declProps == null)
         return declProps;
      if (!(declProps instanceof ArrayList) && declProps.size() > 0)
         declProps = new ArrayList(declProps);
      for (int i = 0; i < declProps.size(); i++) {
         Object prop = declProps.get(i);
         if (prop == null)
            continue;
         int ix = propertyIndexOf(remProps, prop, byName);
         if (ix != -1) {
            declProps.remove(i);
            i--;
         }
      }
      return declProps;
   }

   public static List mergeProperties(List modProps, List declProps, boolean replace) {
      return mergeProperties(modProps, declProps, replace, false);
   }

   /** Merges property lists, modifying the first argument, adding any new entries, replacing entries in declProps */
   public static List mergeProperties(List modProps, List declProps, boolean replace, boolean includeAssigns) {
      if (modProps == null)
         return declProps;
      if (declProps == null)
         return modProps;
      if (!(modProps instanceof ArrayList) && declProps.size() > 0)
         modProps = new ArrayList(modProps);
      for (int i = 0; i < declProps.size(); i++) {
         Object prop = declProps.get(i);
         if (prop == null)
            continue;
         if (includeAssigns && isReverseBinding(prop))
            modProps.add(prop);
         else {
            int ix = propertyIndexOf(modProps, prop, true);
            if (ix == -1)
               modProps.add(prop);
            else if (replace)
               modProps.set(ix, prop);
            else {
               // Get method overrides the field
               Object modProp = modProps.get(ix);
               if (ModelUtil.isGetMethod(prop) && ModelUtil.isField(modProp))
                  modProps.set(ix, prop);
            }
         }
      }
      return modProps;
   }

   public static int propertyIndexOf(List props, Object prop, boolean byName) {
      if (props == null || prop == null)
         return -1;
      for (int i = 0; i < props.size(); i++) {
         Object cprop = props.get(i);
         if (cprop == null)
            continue;
         if (byName) {
            String name = ModelUtil.getPropertyName(cprop);
            if (name.equals(ModelUtil.getPropertyName(prop)))
               return i;
         }
         else {
            if (prop == cprop)
               return i;
         }
      }
      return -1;
   }

   public static List<Object> mergeMethods(List modMeths, List declMeths) {
      if (modMeths == null)
         return declMeths;
      if (declMeths == null)
         return modMeths;
      if (!(modMeths instanceof ArrayList) && declMeths.size() > 0)
         modMeths = new ArrayList(modMeths);
      for (int i = 0; i < declMeths.size(); i++) {
         Object prop = declMeths.get(i);
         int ix = methodIndexOf(modMeths, prop);
         if (ix == -1) {
            modMeths.add(prop);
         }
         else {
            modMeths.set(ix, prop);
         }
      }
      return modMeths;
   }

   private static int methodIndexOf(List props, Object prop) {
      if (props == null)
         return -1;
      for (int i = 0; i < props.size(); i++)
         if (ModelUtil.overridesMethod(props.get(i), prop))
            return i;
      return -1;
   }

   public static List mergeInnerTypes(List modProps, List declProps) {
      if (modProps == null)
         return declProps;
      if (declProps == null)
         return modProps;
      if (!(modProps instanceof ArrayList) && declProps.size() > 0)
         modProps = new ArrayList(modProps);
      for (int i = 0; i < declProps.size(); i++) {
         Object prop = declProps.get(i);
         int ix = innerTypeIndexOf(modProps, prop);
         if (ix == -1)
            modProps.add(prop);
         else
            modProps.set(ix, prop);
      }
      return modProps;
   }

   private static int innerTypeIndexOf(List props, Object prop) {
      if (props == null)
         return -1;
      // This compares just the class name part of the type name.  That's because we'll inherit an inner type through
      // a base type which has a different full type name.  We still consider that the same type though.
      for (int i = 0; i < props.size(); i++)
         if (ModelUtil.getClassName(props.get(i)).equals(ModelUtil.getClassName(prop)))
            return i;
      return -1;
   }

   /** Takes an array of methods, fields etc. and dumps it to standard out for debugging purposes.
    *  Semantic nodes use their language representation.  Fields, Methods, etc. are ok as they are in Java with toString.
    */
   public static String arrayToString(Object[] list) {
      if (list == null)
         return "";
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < list.length; i++) {
         Object elem = list[i];
         if (elem == null)
            continue;
         sb.append(elementToString(elem, false));
         sb.append("\n");
      }
      return sb.toString();
   }

   public static String elementWithTypeToString(Object elem, boolean baseNameOnly) {
      StringBuilder sb = new StringBuilder();
      if (elem instanceof Method) {
         sb.append("method in type: " + elementToString(ModelUtil.getEnclosingType(elem), baseNameOnly) + ": ");
      }
      else if (elem instanceof Field) {
         sb.append("field: in type: " + elementToString(ModelUtil.getEnclosingType(elem), baseNameOnly) + ": ");
      }
      else if (elem instanceof IBeanMapper) {
         IBeanMapper mapper = (IBeanMapper) elem;
         sb.append(elementWithTypeToString(mapper.getPropertyMember(), baseNameOnly));
         return sb.toString();
      }
      else if (elem instanceof BodyTypeDeclaration) {
      }
      else if (elem instanceof JavaSemanticNode) {
      }
      sb.append(elementToString(elem, baseNameOnly));
      return sb.toString();
   }

   public static String elementToString(Object elem, boolean baseNameOnly) {
      StringBuilder sb = new StringBuilder();
      if (elem instanceof Method) {
         Method meth = (Method) elem;
         sb.append(getTypeName(meth.getReturnType()) + " " + meth.getName() + parameterTypesToString(meth.getParameterTypes()));
      }
      else if (elem instanceof Field) {
         Field f = (Field) elem;
         sb.append(f.getType() + " " + f.getName());
      }
      else if (elem instanceof IBeanMapper) {
         IBeanMapper mapper = (IBeanMapper) elem;
         sb.append(ModelUtil.getTypeName(mapper.getPropertyType()) + " " + mapper.getPropertyName());
      }
      else if (elem instanceof BodyTypeDeclaration) {
         // For the typeName, include just the part specific to the layer.  i.e. strip off the package prefix.
         TypeDeclaration td = (TypeDeclaration) elem;
         Layer l = td.getLayer();
         String typeName = ModelUtil.getTypeName(elem);
         if (l != null && l.packagePrefix != null && l.packagePrefix.length() > 0) {
            if (typeName.startsWith(l.packagePrefix))
              typeName = typeName.substring(l.packagePrefix.length() + 1);
         }
         if (baseNameOnly)
            typeName = CTypeUtil.getClassName(typeName);
         BodyTypeDeclaration type = (BodyTypeDeclaration) elem;
         if (type instanceof ModifyDeclaration)
            sb.append(typeName);
         else {
            sb.append(type.getDeclarationType().keyword + " " + typeName);
         }
         Object ext = type.getExtendsTypeDeclaration();
         if (ext != null) {
            sb.append(" extends ");
            sb.append(CTypeUtil.getClassName(ModelUtil.getTypeName(ext)));
         }
      }
      else if (elem instanceof JavaSemanticNode)
         sb.append(((JavaSemanticNode) elem).toDeclarationString());
      else
         sb.append(elem);
      return sb.toString();
   }

   public static String elementName(Object elem) {
      StringBuilder sb = new StringBuilder();
      if (elem instanceof Method) {
         Method meth = (Method) elem;
         sb.append(meth.getName() + parameterTypesToString(meth.getParameterTypes()));
      }
      else if (elem instanceof Field) {
         Field f = (Field) elem;
         sb.append(f.getName());
      }
      else if (elem instanceof IBeanMapper) {
         IBeanMapper mapper = (IBeanMapper) elem;
         sb.append(mapper.getPropertyName());
      }
      else if (elem instanceof BodyTypeDeclaration) {
         // For the typeName, include just the part specific to the layer.  i.e. strip off the package prefix.
         TypeDeclaration td = (TypeDeclaration) elem;
         Layer l = td.getLayer();
         String typeName = ModelUtil.getTypeName(elem);
         if (l.packagePrefix != null) {
            if (typeName.startsWith(l.packagePrefix))
               typeName = typeName.substring(l.packagePrefix.length() + 1);
         }
         typeName = CTypeUtil.getClassName(typeName);
         sb.append(typeName);
      }
      else
         sb.append(elem);
      return sb.toString();
   }

   public static ClientTypeDeclaration getClientTypeDeclaration(Object type) {
      if (type == null)
         return null;
      if (type instanceof ClientTypeDeclaration)
         return (ClientTypeDeclaration) type;
      if (type instanceof BodyTypeDeclaration)
         return ((BodyTypeDeclaration) type).getClientTypeDeclaration();
      return null;
   }

   public static Object setElementValue(Object type, Object instance, Object elem, String text, boolean updateInstances, boolean valueIsExpr) {
      String origText = text;
      if (elem instanceof IBeanMapper) {
         return setElementValue(type, instance, ((IBeanMapper) elem).getPropertyMember(), text, updateInstances, valueIsExpr);
      }
      if (type instanceof BodyTypeDeclaration && elem instanceof IVariableInitializer) {
         text = text.trim();
         String op;
         if (text.length() == 0) {
            op = null;
            text = "";
         }
         else if (text.startsWith("=:")) {
            op = "=:";
            text = text.substring(2).trim();
         }
         else if (text.startsWith("=")) {
            op = "=";
            text = text.substring(1).trim();
         }
         else if (text.startsWith(":=:")) {
            op = ":=:";
            text = text.substring(3).trim();
         }
         else if (text.startsWith(":=")) {
            op = ":=";
            text = text.substring(2).trim();
         }
         else {
            op = ModelUtil.getOperator(elem);
            if (op == null)
               op = "=";
         }

         if (text.endsWith(";"))
            text = text.substring(0,text.length()-1);

         if (!valueIsExpr && text.length() > 0)
            text = RTypeUtil.fromString(ModelUtil.getRuntimeType(ModelUtil.getPropertyType(elem)), text);

         BodyTypeDeclaration typeDef = (BodyTypeDeclaration) type;
         JavaModel model = typeDef.getJavaModel();

         MessageHandler handler = new MessageHandler();
         IMessageHandler oldHandler = model.getErrorHandler();
         try {
            model.setErrorHandler(handler);

            Object exprObj = parseCommandString(model.getLanguage(), text, ((JavaLanguage) model.getLanguage()).variableInitializer);
            if (exprObj == null)
               op = null;

            Expression expr = (Expression) exprObj;

            // PropetyAssignment or VariableDefinition
            JavaSemanticNode node = (JavaSemanticNode) elem;

            PropertyAssignment newAssign = null;

            // If we inherited this definition, we need to create a new one to set it
            if (node.getEnclosingType() != type) {
               String propName = ModelUtil.getPropertyName(node);
               if (expr == null) {
                  newAssign = OverrideAssignment.create(propName);
               }
               else {
                  newAssign = PropertyAssignment.create(propName, expr, op);
               }
               elem = newAssign;
               typeDef.addBodyStatementIndent(newAssign);
            }
            else {
               // Need to convert "override name = x" to "name = x"
               if (node instanceof OverrideAssignment && expr != null) {
                  newAssign = (OverrideAssignment) node;
                  elem = PropertyAssignment.create(newAssign.propertyName, expr, op);
                  node.parentNode.replaceChild(node, elem);
               }
               // And convert name = x to override name when setting x = null;
               else if (expr == null && node instanceof PropertyAssignment && !(node instanceof OverrideAssignment)) {
                  newAssign = (PropertyAssignment) node;
                  elem = OverrideAssignment.create(newAssign.propertyName);
                  node.parentNode.replaceChild(node, elem);
               }
               else {
                  ((IVariableInitializer) node).updateInitializer(op, expr);

                  if (node instanceof PropertyAssignment) {
                     PropertyAssignment updateAssign = (PropertyAssignment) node;
                     // If this definition was generated, also update the source statement
                     PropertyAssignment fromAssign = (PropertyAssignment) updateAssign.fromStatement;
                     if (fromAssign != null) {
                        setElementValue(type, instance, updateAssign.fromStatement, origText, false, valueIsExpr);
                     }
                     Attr fromAttr = updateAssign.fromAttribute;
                     if (fromAttr != null) {
                        /** Node - this is SemanticNode.setProperty with a wrapper due to the name conflict with IDynObject */
                        fromAttr.setSemanticProperty("value", op + " " + text);
                     }
                  }
                  // If this definition was generated and it was a field, need to find the original variable definition in the original field and update that
                  else if (node instanceof VariableDefinition) {
                     VariableDefinition updateVar = (VariableDefinition) node;
                     Statement updateDef = updateVar.getDefinition();
                     ISrcStatement fromStatement = updateDef.fromStatement;
                     if (fromStatement != null) {
                        if (fromStatement instanceof FieldDefinition) {
                           FieldDefinition otherField = (FieldDefinition) fromStatement;
                           for (VariableDefinition otherVar:otherField.variableDefinitions) {
                              if (otherVar.variableName.equals(updateVar.variableName)) {
                                 setElementValue(type, instance, otherVar, origText, false, valueIsExpr);
                                 break;
                              }
                           }
                        }
                     }
                  }
               }
            }

            if (ModelUtil.isProperty(elem)) {
               ExecutionContext ctx = new ExecutionContext(typeDef.getJavaModel());
               if (instance != null)
                  ctx.pushCurrentObject(instance);

               LayeredSystem sys = model.getLayeredSystem();

               UpdateInstanceInfo info = null;
               if (updateInstances)
                  info = sys.newUpdateInstanceInfo();
               typeDef.updatePropertyForType((JavaSemanticNode) elem, ctx, BodyTypeDeclaration.InitInstanceType.Init, updateInstances, info);

               // Need to rebuild here to be sure all transformed types are transformed before we try to generate the JS that updates the clients
               //sys.rebuildSystem();

               if (!typeDef.isDynamicType())
                  model.transformModel();

               if (info != null)
                  info.updateInstances(ctx);

               model.validateSavedModel(false);
               // Notify code that this model has changed by sending a binding event.
               model.markChanged();

               if (handler.err != null)
                  throw new IllegalArgumentException(handler.err);
            }
            else
               throw new UnsupportedOperationException();

            return newAssign;
         }
         finally {
            model.setErrorHandler(oldHandler);
         }
      }
      else
         throw new UnsupportedOperationException();
   }

   public static Object getPreviousDefinition(Object def) {
      if (def instanceof PropertyAssignment)
         return ((PropertyAssignment) def).getPreviousDefinition();
      else if (def instanceof VariableDefinition)
         return ((VariableDefinition) def).getPreviousDefinition();
      else if (def instanceof MethodDefinition)
         return ((MethodDefinition) def).getPreviousDefinition();
      throw new UnsupportedOperationException();
   }

   public static Object parseCommandString(Language cmdLang, String command, Parselet start) {
      // Skip empty lines, though make sure somewhitespace gets in there
      if (command.trim().length() == 0) {
         return null;
      }

      if (!start.initialized) {
         ParseUtil.initAndStartComponent(start);
      }
      Parser p = new Parser(cmdLang, new StringReader(command));
      Object parseTree = p.parseStart(start);
      if (parseTree instanceof ParseError) {
         ParseError err = (ParseError) parseTree;
         throw new IllegalArgumentException(err.errorStringWithLineNumbers(command));
      }
      // The parser did not consume all of the input - ordinarily an error but for the command interpreter,
      // we'll just save the extra stuff for the next go around.
      else if (!p.eof || p.peekInputChar(0) != '\0') {
         throw new IllegalArgumentException("Unable to parse: " + command + " as: " + start);
      }
      return ParseUtil.nodeToSemanticValue(parseTree);
   }

   public static String elementValueString(Object elem) {
      StringBuilder sb = new StringBuilder();
      if (elem instanceof IBeanMapper) {
         IBeanMapper mapper = (IBeanMapper) elem;
         Object member = mapper.getPropertyMember();
         if (member instanceof VariableDefinition)
            return elementValueString(member);
      }
      else if (elem instanceof VariableDefinition) {
         Expression expr = ((VariableDefinition) elem).getInitializerExpr();
         if (expr != null)
            sb.append(expr.toLanguageString().trim());
      }
      else if (elem instanceof PropertyAssignment) {
         Expression expr = ((PropertyAssignment) elem).getInitializerExpr();
         if (expr != null)
            sb.append(expr.toLanguageString().trim());
      }
      return sb.toString().trim();
   }

   public static String instanceValueString(Object inst, Object value, ExecutionContext ctx) {
      if (ModelUtil.isReadableProperty(value)) {
         Object val;
         String propName = ModelUtil.getPropertyName(value);
         if (ModelUtil.hasModifier(value, "static")) {
            if (value instanceof PropertyAssignment)
               value = ((PropertyAssignment) value).getAssignedProperty();
            if (value instanceof VariableDefinition)
               val = ((VariableDefinition) value).getStaticValue(ctx);
            else if (value instanceof IBeanMapper)
               val = "";
            else
               throw new UnsupportedOperationException();
         }
         else
            val = DynUtil.getProperty(inst, propName);
         if (val == null)
            return "";
         Class valClass = val.getClass();
         if (valClass.isArray()) {
            if (valClass.getComponentType().isPrimitive()) {
               val = TypeUtil.primArrayToObjArray(val);
            }
            return StringUtil.arrayToString((Object[]) val);
         }
         return val.toString().trim();
      }
      return "";
   }

   private static String parameterTypesToString(Class[] ptypes) {
      StringBuilder sb = new StringBuilder();
      sb.append("(");
      boolean first = true;
      if (ptypes != null) {
         for (Class c:ptypes) {
            if (!first)
               sb.append(", ");
            sb.append(c.getName());
            first = false;
         }
      }
      sb.append(")");
      return sb.toString();
   }

   public static Expression getPropertyInitializer(Object extType, String name) {
      if (extType instanceof TypeDeclaration)
         return ((TypeDeclaration) extType).getPropertyInitializer(name);

      // no way to get this info for compiled models... could hack it for statics I guess?
      // this is just for diagnostics anyway.
      return null;
   }

   public static Object getRuntimeMethod(Object method) {
      if (method instanceof AbstractMethodDefinition)
         return ((AbstractMethodDefinition) method).getRuntimeMethod();
      else if (method instanceof Method)
         return (Method) method;
      else if (method instanceof CFMethod)
         return ((CFMethod) method).getRuntimeMethod();
      else if (method instanceof ParamTypedMethod)
         return getRuntimeMethod(((ParamTypedMethod) method).method);
      else {
         if (method == null)
            throw new NullPointerException();
         else
            System.err.println("**** method type is: " + method.getClass());
         throw new UnsupportedOperationException();
      }
   }

   public static Object getRuntimePropertyMapping(Object propType) {
      if (propType instanceof VariableDefinition)
         return ((VariableDefinition) propType).getRuntimePropertyMapping();
      return propType;
   }

   /** Allows $ to match . in type names */
   public static boolean typeNamesEqual(String fte, String fullTypeName) {
      if (fte == fullTypeName)
         return true;

      int fteLen;
      if ((fteLen = fte.length()) != fullTypeName.length())
         return false;

      for (int i = 0; i < fteLen; i++) {
         char c1 = fte.charAt(i);
         char c2 = fullTypeName.charAt(i);
         if (c1 != c2 && ((c1 != '.' && c1 != '$') || (c2 != '.' && c2 != '$')))
            return false;
      }
      return true;
   }

   public static void addCompletionCandidate(Set<String> candidates, String prospect) {
      if (prospect.startsWith("_") || prospect.contains("$"))
         return;
      candidates.add(prospect);
   }

   public static void suggestMembers(JavaModel model, Object type, String prefix, Set<String> candidates, boolean includeGlobals,
                                     boolean includeProps, boolean includeMethods) {
      if (type != null) {
         if (includeProps) {
            Object[] props = getProperties(type, null);
            if (props != null) {
               for (int i = 0; i < props.length; i++) {
                  Object prop = props[i];
                  if (prop != null) {
                     String pname = ModelUtil.getPropertyName(prop);
                     if (pname.startsWith(prefix))
                        addCompletionCandidate(candidates, pname);
                  }
               }
            }
         }

         if (includeMethods) {
            Object[] meths = getAllMethods(type, null, false, false, false);
            if (meths != null) {
               for (int i = 0; i < meths.length; i++) {
                  String mname = ModelUtil.getMethodName(meths[i]);
                  if (mname.startsWith(prefix))
                     addCompletionCandidate(candidates, mname);
               }
            }
         }
         Object[] types = getAllInnerTypes(type, null, false);
         if (types != null) {
            for (int i = 0; i < types.length; i++) {
               String mname = CTypeUtil.getClassName(ModelUtil.getTypeName(types[i]));
               if (mname.startsWith(prefix))
                  addCompletionCandidate(candidates, mname);
            }
         }
      }
      if (includeGlobals) {
         Object encType = getEnclosingType(type);

         if (encType != null) // Include members that are visible in the namespace
            suggestMembers(model, encType, prefix, candidates, includeGlobals, includeProps, includeMethods);
         else // only for the root - search the global ones
            model.findMatchingGlobalNames(prefix, candidates);
      }
   }

   public static void suggestTypes(JavaModel model, String prefix, String lastIdent, Set<String> candidates, boolean includeGlobals) {
      if (prefix == null)
         prefix = "";
      Set<String> files = model.layeredSystem.getFilesInPackage(prefix);
      if (files != null) {
         for (String file:files) {
            if (file.startsWith(lastIdent)) {
               // Remove inner classes
               int dix = file.indexOf("$");
               int uix = file.indexOf("__");
               // We skip stub classes which have __ in the name
               if (dix == -1 && (uix == -1 || uix == 0 || uix >= file.length()-2))
                  candidates.add(file);
            }
         }
      }
      if (includeGlobals) {
         if (lastIdent.equals(""))
            model.findMatchingGlobalNames(prefix, candidates);
         else
            model.layeredSystem.findMatchingGlobalNames(null, model.getLayer(), lastIdent, candidates, false, false);
      }

      String absName, pkgName, baseName;
      if (lastIdent.equals("")) {
         absName = prefix;
         pkgName = prefix;
         baseName = "";
      }
      else {
         absName = CTypeUtil.prefixPath(prefix, lastIdent);
         pkgName = prefix;
         baseName = lastIdent;
      }

      model.findMatchingGlobalNames(absName, pkgName, baseName, candidates);
      model.layeredSystem.findMatchingGlobalNames(null, model.getLayer(), absName, pkgName, baseName, candidates, false, false);
   }

   public static void suggestVariables(IBlockStatement enclBlock, String prefix, Set<String> candidates) {
      List<Statement> statements = enclBlock.getBlockStatements();
      if (statements != null) {
         for (Statement st:statements) {
            if (st instanceof VariableStatement) {
               VariableStatement varSt = (VariableStatement) st;
               if (varSt.definitions != null) {
                  for (VariableDefinition varDef:varSt.definitions) {
                     String varName = varDef.variableName;
                     if (varName != null && varName.startsWith(prefix)) {
                        candidates.add(varName);
                     }
                  }
               }
            }
         }
      }
      AbstractMethodDefinition enclMethod = ((JavaSemanticNode)enclBlock).getEnclosingMethod();
      if (enclMethod != null && enclMethod.parameters != null) {
         for (Parameter param:enclMethod.parameters.getParameterList()) {
            String varName = param.variableName;
            if (varName != null && varName.startsWith(prefix))
               candidates.add(varName);
         }
      }
      enclBlock = enclBlock.getEnclosingBlockStatement();
      if (enclBlock != null)
         suggestVariables(enclBlock, prefix, candidates);
   }

   public static Object getReturnType(Object method) {
      if (method instanceof IMethodDefinition)
         return ((IMethodDefinition) method).getReturnType();
      else if (method instanceof Constructor)
         return ((Constructor) method).getDeclaringClass();
      else if (method instanceof Method)
         return ((Method) method).getReturnType();
      else
         throw new UnsupportedOperationException();
   }

   public static Object getReturnJavaType(Object method) {
      if (method instanceof IMethodDefinition)
         return ((IMethodDefinition) method).getReturnJavaType();
      else if (method instanceof Constructor)
         return ((Constructor) method).getDeclaringClass();
      else if (method instanceof Method)
         return ((Method) method).getReturnType();
      else
         throw new UnsupportedOperationException();
   }

   public static Object[] getParameterTypes(Object method) {
      if (method instanceof IMethodDefinition)
         return ((IMethodDefinition) method).getParameterTypes();
      else if (method instanceof Method)
         return ((Method) method).getParameterTypes();
      else if (method instanceof Constructor)
         return ((Constructor) method).getParameterTypes();
      else
         throw new UnsupportedOperationException();
   }

   public static Object[] getParameterJavaTypes(Object method) {
      if (method instanceof IMethodDefinition)
         return ((IMethodDefinition) method).getParameterJavaTypes();
      else if (method instanceof Method)
         return ((Method) method).getParameterTypes();
      else if (method instanceof Constructor)
         return ((Constructor) method).getParameterTypes();
      else
         throw new UnsupportedOperationException();
   }

   public static DeclarationType getDeclarationType(Object type) {
      if (type instanceof ITypeDeclaration)
         return ((ITypeDeclaration) type).getDeclarationType();
      if (ModelUtil.isObjectType(type))
         return DeclarationType.OBJECT;
      if (ModelUtil.isEnum(type))
         return DeclarationType.ENUM;
      if (ModelUtil.isInterface(type))
         return DeclarationType.INTERFACE;
      return DeclarationType.CLASS;
   }

   public static CoalescedHashMap getMethodCache(Object type) {
      if (type instanceof Class)
         return RTypeUtil.getMethodCache((Class) type);
      else if (type instanceof CFClass)
         return ((CFClass) type).getMethodCache();
      else if (type instanceof TypeDeclaration)
         return ((TypeDeclaration) type).getMethodCache();
      else
         throw new UnsupportedOperationException();
   }

   public static Object getBindableAnnotation(Object def) {
      return ModelUtil.getAnnotation(def, "Bindable");
   }

   public static boolean isAutomaticBindingAnnotation(Object annotationObj) {
      Object manualObj = ModelUtil.getAnnotationValue(annotationObj, "manual");
      return manualObj == null || !(manualObj instanceof Boolean) ||
              !((Boolean) manualObj);
   }

   public static Object resolveAnnotationReference(JavaModel model, Object def) {
      Object encType = ModelUtil.getEnclosingType(def);
      if (encType == null) {
         return def;
      }
      Object res;
      String propName;
      String typeName;
      Object specEncType = model.layeredSystem.getTypeDeclaration(typeName = ModelUtil.getTypeName(encType));
      if (specEncType == null && (specEncType = model.findTypeDeclaration(typeName, false)) == null) {
         System.err.println("**** resolveSpecificReference failed for: " + def);
         res = def;
      }
      else if (specEncType == encType)
         res = def;
      else if (def instanceof IMethodDefinition && ((IMethodDefinition) def).getPropertyName() != null) {
         propName = ((IMethodDefinition) def).getPropertyName();
         if (propName != null) {
            res = ModelUtil.definesMember(specEncType, ModelUtil.getPropertyName(def), JavaSemanticNode.MemberType.PropertyAssignmentSet, null, null);
            if (res == null) {
               System.out.println("*** no member in specEncType");
               res = def;
            }
         }
         else
            res = def;
      }
      else if (ModelUtil.isField(def)) {
         res = ModelUtil.definesMember(specEncType, ModelUtil.getPropertyName(def), JavaSemanticNode.MemberType.PropertyAssignmentSet, null, null);
         if (res == null) {
            System.out.println("*** no field in specEncType");
            res = def;
         }
      }
      else if (ModelUtil.isMethod(def)) {
         Object[] params = ModelUtil.getParameterTypes(def);
         List lp;
         if (params == null)
            lp = null;
         else
            lp = Arrays.asList(params);
         res = ModelUtil.definesMethod(specEncType, ModelUtil.getMethodName(def), lp, null, null, false);
         if (res == null) {
            System.out.println("*** no method in specEncType");
            res = def;
         }
      }
      // Object reference
      else {
         res = def;
      }
      return res;
   }

   public static String getFullBaseTypeName(Object typeDeclaration) {
      if (typeDeclaration instanceof ITypeDeclaration) {
         return ((ITypeDeclaration) typeDeclaration).getFullBaseTypeName();
      }
      else {
         String typeName = ModelUtil.getTypeName(typeDeclaration);
         int ix = typeName.indexOf("[]");
         if (ix != -1)
            return typeName.substring(0, ix);
         return typeName;
      }
   }

   public static String[] getParameterNames(Object meth) {
      String[] names;
      if (meth instanceof AbstractMethodDefinition) {
         Parameter parameters = ((AbstractMethodDefinition)meth).parameters;
         if (parameters == null)
            return null;
         return parameters.getParameterNames();
      }
      else {
         Object[] types = ModelUtil.getParameterTypes(meth);
         if (types == null)
            return null;
         names = new String[types.length];
         for (int i = 0; i < names.length; i++) {
            names[i] = "_p" + i;
         }
      }
      return names;
   }

   public static String getParameterDecl(Object meth) {
      Object[] types = getParameterTypes(meth);
      Object[] names = getParameterNames(meth);
      if (types == null || types.length == 0)
         return "";
      int len = types.length;
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < len; i++) {
         if (i != 0)
            sb.append(", ");
         sb.append(ModelUtil.getTypeName(types[i]));
         sb.append(" ");
         sb.append(names[i]);
      }
      return sb.toString();
   }

   public static int getModifiers(Object def) {
      if (def instanceof Member)
         return ((Member) def).getModifiers();
      else if (def instanceof Class)
         return ((Class) def).getModifiers();
      else if (def instanceof ParamTypedMethod)
         return getModifiers(((ParamTypedMethod) def).method);
      throw new UnsupportedOperationException();
   }

   public static Object getRootType(Object typeObj) {
      Object nextObj = typeObj;
      Object lastParent = typeObj;
      do {
         nextObj = ModelUtil.getEnclosingType(nextObj);
         if (nextObj != null)
            lastParent = nextObj;
      } while (nextObj != null);
      return lastParent;
   }

   public static Object[] getExceptionTypes(Object meth) {
      if (meth instanceof Method)
         return ((Method) meth).getExceptionTypes();
      else if (meth instanceof IMethodDefinition) {
         return ((IMethodDefinition) meth).getExceptionTypes();
      }
      else
         throw new IllegalArgumentException("Bad method type: " + meth);
   }

   public static boolean isComponentInterface(LayeredSystem sys, Object typeDecl) {
      if (!ModelUtil.isComponentType(typeDecl))
         return false;

      // TODO: right now, for Jetty there are conflicts so it can't implement the start method since it has one.
      // turns out, it does the lifecycle management we need through lifecycle.  This is not the clearest way
      // to spec it but if the overrideStartName is set, it means it does not implement the interface and we
      // can't treat it as a component in the code.
      Object compilerSettings = ModelUtil.getInheritedAnnotation(sys, typeDecl, "sc.obj.CompilerSettings", false, typeDecl instanceof ITypeDeclaration ? ((BodyTypeDeclaration) typeDecl).getLayer() : null, false);
      if (compilerSettings == null)
         return true;
      String overrideStartName;
      if ((overrideStartName = (String) ModelUtil.getAnnotationValue(compilerSettings, "overrideStartName")) == null ||
          overrideStartName.length() == 0)
         return true;
      return false;
   }

   public static Object getAnnotationValue(Object type, String annotationName, String valueName) {
      Object annot = getAnnotation(type, annotationName);
      if (annot != null) {
         return getAnnotationValue(annot, valueName);
      }
      return null;
   }

   /** Returns any scope name set on this type or field.  Note that scopes can only be set on source descriptions.  If you need scope info for other than code generation, i.e. at runtime, you set the annotationName for the scope process and then check for the annotation at runtime. */
   public static String getScopeName(Object def) {
      if (def instanceof Definition) {
         return ((Definition) def).getScopeName();
      }
      else if (def instanceof Class)
         return (String) getAnnotationValue(def, "sc.obj.Scope", "name");
      return null;
   }

   /** Returns any scope name set on this type or field.  Note that scopes can only be set on source descriptions.  If you need scope info for other than code generation, i.e. at runtime, you set the annotationName for the scope process and then check for the annotation at runtime. */
   public static String getInheritedScopeName(LayeredSystem sys, Object def) {
      if (sys == null)
         sys = LayeredSystem.getCurrent();
      def = resolveSrcTypeDeclaration(sys, def, false, false);
      if (def instanceof BodyTypeDeclaration) {
         return ((BodyTypeDeclaration) def).getInheritedScopeName();
      }
      else if (def instanceof Class) {
         return (String) getInheritedAnnotationValue(sys, def, "sc.obj.Scope", "name");
      }
      return null;
   }

   /** Takes name of the form: typeName<paramType> and returns just the type object for "typeName", ignoring the parameters */
   public static Object getTypeFromTypeOrParamName(BodyTypeDeclaration srcType, String fieldTypeName) {
      int ix = fieldTypeName.indexOf("<");
      String fieldBaseType;
      if (ix != -1) {
         fieldBaseType = TypeUtil.stripTypeParameters(fieldTypeName);
         int eix = fieldTypeName.indexOf(">");
         if (eix == -1) {
            return null;
         }
      }
      else {
         fieldBaseType = fieldTypeName;
      }
      JavaModel model;
      Object fieldType = (model = srcType.getJavaModel()).findTypeDeclaration(fieldBaseType, false);
      if (fieldType == null) {
         TypeDeclaration encType = srcType.getEnclosingType();
         if (encType != null) {
            fieldTypeName = encType.mapTypeParameterNameToTypeName(fieldTypeName);
            fieldType = model.findTypeDeclaration(fieldTypeName, false);
         }
      }
      return fieldType;
   }

   /** Takes name of the form: typeName<paramType> and returns the JavaType which represents that expression */
   public static JavaType getJavaTypeFromTypeOrParamName(BodyTypeDeclaration srcType, String fieldTypeName) {
      JavaModel model;
      int ix = fieldTypeName.indexOf("<");
      String fieldBaseType;
      String[] fieldTypeParamNames;
      if (ix != -1) {
         fieldBaseType = TypeUtil.stripTypeParameters(fieldTypeName);
         int eix = fieldTypeName.indexOf(">");
         if (eix == -1) {
            srcType.displayError("scopeFieldDefs format error for type: " + fieldTypeName);
            return null;
         }
         else {
            fieldTypeParamNames = StringUtil.split(fieldTypeName.substring(ix + 1, eix), ',');
         }
      }
      else {
         fieldBaseType = fieldTypeName;
         fieldTypeParamNames = null;
      }
      Object fieldType = (model = srcType.getJavaModel()).findTypeDeclaration(fieldBaseType, false);
      if (fieldType == null) {
         TypeDeclaration encType = srcType.getEnclosingType();
         if (encType != null) {
            fieldTypeName = encType.mapTypeParameterNameToTypeName(fieldTypeName);
            fieldType = model.findTypeDeclaration(fieldTypeName, false);
         }
      }
      if (fieldType == null)
         return null;

      if (fieldTypeParamNames != null) {
         JavaType[] fieldTypeParams = new JavaType[fieldTypeParamNames.length];
         for (int i = 0; i < fieldTypeParamNames.length; i++) {
            fieldTypeParams[i] = getJavaTypeFromTypeOrParamName(srcType, fieldTypeParamNames[i]);
         }
         return JavaType.createFromTypeParams(ModelUtil.getTypeName(fieldType), fieldTypeParams);
      }
      return JavaType.createJavaType(fieldType);
   }

   public static Object getStaticPropertyValue(Object staticType, String firstIdentifier) {
      if (staticType instanceof TypeDeclaration) {
         return ((TypeDeclaration) staticType).getStaticProperty(firstIdentifier);
      }
      else if (staticType instanceof Class) {
         return TypeUtil.getStaticValue((Class) staticType, firstIdentifier);
      }
      else if (staticType == null)
         throw new NullPointerException("No static type where static property: " + firstIdentifier + " expected");
      else
         throw new UnsupportedOperationException();
   }

   public static boolean isChainedReferenceInitializer(MethodDefinition mdef) {
      Parameter params;
      if (mdef.propertyName != null && mdef.propertyMethodType.isGet() &&
              (params = mdef.parameters) != null && params.getNumParameters() == 1 && mdef.parameters.getParameterNames()[0].equals("doInit"))
         return true;

      // TODO: probably need a better test than that - should be in the IComponent interface
      if (mdef.name.equals("init") && mdef.getNumParameters() == 0)
         return true;

      // Is this a newX method that we generated.  TODO: this could be cleaner too: maybe use an annotation?
      if (mdef.name.startsWith("new") && mdef.getNumParameters() >= 1 && mdef.parameters.getParameterNames()[0].equals("doInit"))
         return true;

      return false;
   }

   public static boolean isDynamicProperty(Object propType) {
      if (propType instanceof BodyTypeDeclaration) {
         BodyTypeDeclaration pt = (BodyTypeDeclaration) propType;
         BodyTypeDeclaration enc = pt.getEnclosingType();
         if (enc == null)
            return ModelUtil.isDynamicType(propType);  // Top level object
         if (pt.getEnclosingType().isDynamicType() && !pt.isCompiledProperty(pt.typeName, false, false))
            return true;
         return false;
      }
      else {
         return ModelUtil.isDynamicType(propType);
      }
   }

   public static boolean isDynamicType(Object encType) {
      if (encType instanceof JavaSemanticNode)
         return ((JavaSemanticNode) encType).isDynamicType();
      else if (encType instanceof DynBeanMapper)
         return isDynamicType(((DynBeanMapper) encType).getPropertyMember());
      else if (encType instanceof ITypeDeclaration)
         return ((ITypeDeclaration) encType).isDynamicType();
      return false;
   }

   public static boolean isDynamicNew(Object boundType) {
      return boundType instanceof ITypeDeclaration && ((ITypeDeclaration) boundType).isDynamicNew();
   }

   public static Object getTypeFromInstance(Object arg) {
      if (arg instanceof IDynObject)
         return ((DynObject) arg).getDynType();
      else
         return arg.getClass();
   }

   public static String getRuntimeClassName(Object extendsType) {
      if (extendsType instanceof TypeDeclaration)
         return ((TypeDeclaration) extendsType).getCompiledClassName();
      else
         return getTypeName(extendsType);
   }

   public static String getSignature(Object type) {
      if (type instanceof JavaType)
         return ((JavaType) type).getSignature();
      else if (type instanceof ITypeDeclaration)
         return ModelUtil.getTypeName(type);
      else if (type instanceof Class) {
         return RTypeUtil.getSignature((Class) type);
      }
      else
         throw new UnsupportedOperationException();
   }

   public static String getTypeSignature(Object obj) {
      if (obj == null)
         return null;
      if (obj instanceof IMethodDefinition) {
         return ((IMethodDefinition) obj).getTypeSignature();
      }
      else {
         Object[] types = getParameterTypes(obj);
         if (types == null)
            return null;
         StringBuilder sb = new StringBuilder();
         for (Object pt:types) {
            sb.append(ModelUtil.getSignature(pt));
         }
         return sb.toString();
      }
   }

   public static DynType getPropertyCache(Object extType) {
      if (extType instanceof ITypeDeclaration)
         return ((ITypeDeclaration) extType).getPropertyCache();
      else if (extType instanceof Class)
         return TypeUtil.getPropertyCache((Class) extType);
      else
         throw new UnsupportedOperationException();
   }

   public static IBeanMapper getPropertyMapping(Object type, String propName) {
      if (type instanceof Class)
         return PTypeUtil.getPropertyMapping((Class) type, propName);
      else if (type instanceof ITypeDeclaration) {
         if (ModelUtil.isDynamicType(type))
            return ((ITypeDeclaration) type).getPropertyCache().getPropertyMapper(propName);
         else {
            ITypeDeclaration itype = (ITypeDeclaration) type;
            Class cl = itype.getCompiledClass();
            Object extType;
            // If there's no compiled class for this type, we'll should at least try the compiled class for any extends type.  Otherwise, we fail less gracefully than we should when you add a field to a compiled type that was previously omitted.  Another fix for this case wold be to actually try and transform/generate that new class
            if (cl == null && (extType = itype.getExtendsTypeDeclaration()) != null) {
               return getPropertyMapping(extType, propName);
            }
            return PTypeUtil.getPropertyMapping(cl, propName);
         }
      }
      else if (type instanceof String) {
         String typeName = (String) type;
         Object resolvedType = LayeredSystem.getCurrent().getTypeDeclaration(typeName);
         if (resolvedType == null)
            throw new IllegalArgumentException("No type named: " + typeName);
         return getPropertyMapping(resolvedType, propName);
      }
      else
         throw new UnsupportedOperationException();
   }

   public static IBeanMapper getConstantPropertyMapping(Object type, String propName) {
      if (type instanceof Class)
         return TypeUtil.getConstantPropertyMapping((Class) type, propName);
      else if (type instanceof ITypeDeclaration) {
         if (ModelUtil.isDynamicType(type)) {
            IBeanMapper mapper;
            mapper = ((ITypeDeclaration) type).getPropertyCache().getPropertyMapper(propName);
            // Mark this mapping as constant to disable set methods and so we do not have to listen for changes
            mapper.setConstant(true);
            return mapper;
         }
         else {
            Class cl = ((ITypeDeclaration) type).getCompiledClass();
            if (cl == null)
               return null;
            return TypeUtil.getConstantPropertyMapping(cl, propName);
         }
      }
      else if (type instanceof String) {
         String typeName = (String) type;
         Object resolvedType = LayeredSystem.getCurrent().getTypeDeclaration(typeName);
         if (resolvedType == null)
            throw new IllegalArgumentException("No type named: " + typeName);
         return getConstantPropertyMapping(resolvedType, propName);
      }
      else
         throw new UnsupportedOperationException();
   }

  public static Object evalCast(Object propertyType, Object val) {
     if (propertyType instanceof Class) {
        return DynUtil.evalCast((Class) propertyType, val);
     }
     else if (propertyType instanceof ITypeDeclaration) {
        ITypeDeclaration castType = (ITypeDeclaration) propertyType;
        if (val instanceof IDynObject) {
           Object valType = ((IDynObject) val).getDynType();
           if (!ModelUtil.isAssignableFrom(castType, valType))
              throw new ClassCastException("Invalid cast: " + ModelUtil.getTypeName(valType) + " to: " + ModelUtil.getTypeName(castType));
        }
        return val;
     }
     else
        throw new UnsupportedOperationException();
   }

   /**
    * Unlike DynUtil.getType which conditionally strips the type off, here we do it explicitly so that when the
    * runtime gets a java.lang.Class instance we still call getClass
    */
   public static Object getObjectsType(Object obj) {
      if (obj instanceof IDynObject) {
         return ((IDynObject) obj).getDynType();
      }
      else
         return obj.getClass();
   }

   public static boolean evalInstanceOf(Object lhsObj, Object rhs, ITypeParamContext ctx) {
      if (lhsObj == null)
         return false;
      return isAssignableFrom(rhs, getObjectsType(lhsObj), false, ctx);
   }

   public static Object getRuntimeType(Object rootType) {
      if (rootType instanceof Class)
         return rootType;
      else if (rootType instanceof ITypeDeclaration)
         return ((ITypeDeclaration) rootType).getRuntimeType();
      else if (rootType instanceof PropertyAssignment) {
         return ModelUtil.getRuntimeType(((PropertyAssignment) rootType).getTypeDeclaration());
      }
      else
         throw new UnsupportedOperationException();
   }

   public static boolean isInstance(Object srcTypeObj, Object obj) {
      if (obj == null)
         return true;
      return isAssignableFrom(srcTypeObj, DynUtil.getType(obj));
   }

   public static boolean isCompiledMethod(Object override) {
      return override instanceof Method || override instanceof CFMethod || ((override instanceof MethodDefinition) && !((MethodDefinition) override).isDynamicType());
   }

   public static boolean getCompileLiveDynamicTypes(Object typeObj) {
      if (typeObj instanceof TypeDeclaration) {
         TypeDeclaration objType = (TypeDeclaration)typeObj;
         return objType.getLayeredSystem().options.compileLiveDynamicTypes && (objType.layer == null || objType.layer.compileLiveDynamicTypes) && objType.getLiveDynamicTypesAnnotation();
      }
      return false;
   }

   public static boolean getLiveDynamicTypes(Object typeObj) {
      if (typeObj instanceof TypeDeclaration) {
         TypeDeclaration objType = (TypeDeclaration)typeObj;
         return objType.getLayeredSystem().options.liveDynamicTypes && (objType.layer == null || objType.layer.liveDynamicTypes) && objType.getLiveDynamicTypesAnnotation();
      }
      return false;
   }

   public static boolean isDynamicStub(Object extendsType, boolean includeExtends) {
      if (extendsType instanceof TypeDeclaration) {
         return ((TypeDeclaration) extendsType).isDynamicStub(includeExtends);
      }
      return false;
   }

   public static Object resolve(Object typeObj, boolean modified) {
      if (typeObj instanceof BodyTypeDeclaration)
         return ((BodyTypeDeclaration) typeObj).resolve(modified);
      return typeObj;
   }

   /** Prefix for the intValue and intPropertyValue methods in TypeUtil and DynUtil */
   public static String getNumberPrefixFromType(Object type) {
      if (isAnInteger(type))
         return "int";
      else if (isDouble(type))
         return "double";
      else if (isFloat(type))
         return "float";
      else if (isLong(type))
         return "long";
      else if (isBoolean(type))
         return "boolean";
      else if (isCharacter(type))
         return "char";
      throw new UnsupportedOperationException();
   }

   public static String typesToThrowsClause(Object[] throwsTypes) {
      if (throwsTypes == null || throwsTypes.length == 0)
         return "";

      StringBuilder sb = new StringBuilder();
      sb.append("throws ");
      for (int i = 0; i < throwsTypes.length; i++) {
         if (i != 0)
            sb.append(", ");
         sb.append(ModelUtil.getTypeName(throwsTypes[i]));
      }
      return sb.toString();
   }

   public static void refreshBoundProperty(Object type) {
      // TODO: do we need to replace a transformed property reference?  I don't think so because we never use
      // assignedProperty at runtime.
   }

   public static Object refreshBoundIdentifierType(Object boundType) {
      if (boundType instanceof TypeDeclaration)
           return refreshBoundType(boundType);
      // TODO: do we need to re-resolve any identifier references that are children types?  Variables, fields, methods?
      // Theoretically their type should not change and we should not be interpreting these references so I'm thinking
      // we are ok for now.
      return boundType;
   }

   public static Object refreshBoundType(Object boundType) {
      if (boundType instanceof TypeDeclaration) {
         TypeDeclaration td = (TypeDeclaration) boundType;
         return td.refreshBoundType(boundType);
      }
      return boundType;
   }

   public static Object getEnum(Object currentType, String nextName) {
      if (currentType instanceof Class)
         return RTypeUtil.getEnum((Class) currentType, nextName);
      else if (currentType instanceof ITypeDeclaration) {
         ITypeDeclaration typeDecl = (ITypeDeclaration) currentType;
         if (typeDecl.isEnumeratedType())
            return typeDecl.getEnumConstant(nextName);
         return null;
      }
      else throw new UnsupportedOperationException();
   }

   public static Object getRuntimeEnum(Object boundType) {
      if (boundType instanceof java.lang.Enum)
         return boundType;
      if (boundType instanceof BodyTypeDeclaration && ((BodyTypeDeclaration) boundType).isEnumConstant())
         return ((BodyTypeDeclaration) boundType).getRuntimeEnum();
      if (boundType instanceof Field) {
         Field f = (Field) boundType;
         return DynUtil.getStaticProperty(f.getDeclaringClass(), f.getName());
      }
      if (boundType instanceof IBeanMapper) {
         IBeanMapper mapper = (IBeanMapper) boundType;
         return getRuntimeEnum(mapper.getField());
      }
      throw new UnsupportedOperationException();
   }

   public static void startType(BodyTypeDeclaration bt) {
      JavaModel model = bt.getJavaModel();
      if (model != null && !model.isStarted())
         ParseUtil.initAndStartComponent(model);
      else
         ParseUtil.initAndStartComponent(bt);
   }

   public static StringBuilder convertToCommaSeparatedStrings(Set<String> names) {
      StringBuilder sb = new StringBuilder();
      boolean first = true;
      for (String n:names) {
         if (!first)
            sb.append(",");
         else
            first = false;
         sb.append("\"");
         sb.append(n);
         sb.append("\"");
      }
      return sb;
   }

   public static boolean isAbstractType(Object type) {
      return (hasModifier(type, "abstract") || isInterface(type) || isAbstractElement(type));
   }

   public static boolean isAbstractElement(Object type) {
      if (type instanceof TypeDeclaration) {
         TypeDeclaration td = (TypeDeclaration) type;
         if (td.element != null)
            return td.element.isAbstract();
      }
      return false;
   }

   public static boolean isAbstractMethod(Object meth) {
      return (hasModifier(meth, "abstract") || isInterface(getEnclosingType(meth)));
   }

   public static Object getReverseBindingMethod(Object method) {
      Object settings = RDynUtil.getAnnotation(method, BindSettings.class);
      String reverseMethodName;
      if (settings != null && (reverseMethodName = (String) RDynUtil.getAnnotationValue(settings, "reverseMethod")).length() > 0) {
         Object[] methods = RDynUtil.getMethods(RDynUtil.getDeclaringClass(method), reverseMethodName);
         if (methods == null) {
            System.err.println("*** Method: " + method + " BindSettings.reverseMethod annotation refers to a non-existent method: " + reverseMethodName);
            return null;
         }
         Object returnType = DynUtil.getReturnType(method);
         for (Object invMeth:methods) {
            Object[] ptypes = DynUtil.getParameterTypes(invMeth);
            if (ptypes.length >= 1) {
               if (DynUtil.isAssignableFrom(ptypes[0], returnType))
                  return invMeth;
            }
         }
         System.err.println("*** Method: " + method + " BindSettings.reverseMethod: " + reverseMethodName + " needs a signature like: (" + returnType + ", ...) - the first param is the inverse value, the rest are the input parameters");
      }
      else
         System.err.println("*** Method: " + method + " has no BindSettings(reverseMethod=..) annotation - invalid use in bi-directional (:=:) binding");
      return null;
   }

   public static boolean needsCompMethod(Object meth) {
      return (meth instanceof AbstractMethodDefinition) && (((AbstractMethodDefinition) meth).getNeedsDynInvoke());
   }

   public static void markNeedsDynAccess(Object boundType) {
      if (boundType instanceof BodyTypeDeclaration)
         ((BodyTypeDeclaration) boundType).needsDynAccess = true;
      else
         System.err.println("*** Error: unable to make compiled object available at runtime: " + boundType);
   }

   public static boolean needsDynType(Object type) {
      return type instanceof TypeDeclaration && ((TypeDeclaration) type).needsDynType();
   }

   public static boolean isConstructor(Object method) {
      return method instanceof Constructor || method instanceof ConstructorDefinition;
   }

   /** Weird rule in java.  When you have a parameterized return type and no arguments, the type comes from the LHS of the assignment expression
    * (only for assignment expressions apparently)
    */
   public static boolean isLHSTypedMethod(Object boundType) {
      Object[] retVal;
      return ModelUtil.isMethod(boundType) && hasParameterizedReturnType(boundType) &&
             ((retVal = ModelUtil.getParameterTypes(boundType)) == null || retVal.length == 0);
   }


   /** Returns the class which will hold the newX method for a component type that is transformed */
   public static Object getAccessClass(Object base) {
      if (!(base instanceof TypeDeclaration))
         return base;
      TypeDeclaration baseTD = (TypeDeclaration) base;
      TypeDeclaration enclType;
      if ((enclType = baseTD.getEnclosingType()) == null)
         return base;
      return enclType.getClassDeclarationForType();
   }

   public static Object getRuntimeTypeDeclaration(Object type) {
      if (type instanceof BodyTypeDeclaration)
         return ((BodyTypeDeclaration) type).getClassDeclarationForType();
      return type;
   }

   public static boolean isCompiledProperty(Object impl, String name, boolean fieldMode, boolean interfaceMode) {
      if (impl instanceof ITypeDeclaration)
         return ((ITypeDeclaration) impl).isCompiledProperty(name, fieldMode, interfaceMode);
      else {
         // Assume the rest are all compiled types
         return ModelUtil.definesMember(impl, name, JavaSemanticNode.MemberType.PropertyGetSetObj, null, null) != null;
      }
   }

   /** Returns false if this type is optimized away.  i.e. an object instance which contains no methods or fields, just initializations which we can apply at runtime on the base class */
   public static boolean needsOwnClass(Object type, boolean checkComponents) {
      if (type instanceof ITypeDeclaration) {
         ITypeDeclaration typeDef = (ITypeDeclaration) type;
         if (typeDef instanceof TypeDeclaration && ((TypeDeclaration) typeDef).getEnclosingIType() == null)
            return true;
         return (typeDef.needsOwnClass(checkComponents));
      }
      return true;
   }

   public static boolean definesCurrentObject(Object type) {
      if (type instanceof BodyTypeDeclaration)
         return ((BodyTypeDeclaration) type).getDefinesCurrentObject();
      return ModelUtil.isObjectType(type);
   }

   public static String getInnerTypeName(Object typeObj) {
      if (typeObj instanceof ITypeDeclaration)
         return ((ITypeDeclaration) typeObj).getInnerTypeName();
      else if (typeObj instanceof Class) {
         return TypeUtil.getInnerTypeName((Class) typeObj);
      }
      throw new UnsupportedOperationException();
   }

   public static int getTypeOffset(Object type) {
      if (type instanceof Definition) {
         Definition def = (Definition) type;
         IParseNode node = def.getParseNode();
         if (node == null)
            return -1;
         else {
            return node.getStartIndex();
         }
      }
      return -1;
   }

   public static int getLineNumber(Object type) {
      if (type instanceof Definition) {
         Definition def = (Definition) type;
         IParseNode node = def.getParseNode();
         if (node == null)
            return -1;
         else {
            SrcEntry srcFile = def.getJavaModel().getSrcFile();
            if (srcFile == null)
               return -1;
            return ParseUtil.getParseNodeLineNumber(new File(srcFile.absFileName), node);
         }
      }
      return -1;
   }

   /**
    * Used to filter objects in the UI.  Returns true if the type provided has any definitions in a layer with the
    * given code types and functions
    */
   public static boolean matchesLayerFilter(Object type, Collection<CodeType> codeTypes, Collection<CodeFunction> codeFunctions) {
      if (!(type instanceof BodyTypeDeclaration))
         return codeTypes.contains(CodeType.Framework) && codeFunctions.contains(CodeFunction.Program);

      BodyTypeDeclaration typeDecl = (BodyTypeDeclaration) type;
      while (typeDecl != null) {
         if (typeDecl.getLayer().matchesFilter(codeTypes, codeFunctions))
            return true;
         typeDecl = typeDecl.getModifiedType();
      }
      return false;
   }

   public static void getFiltersForType(Object type, Collection<CodeType> codeTypes, Collection<CodeFunction> codeFunctions, boolean allLayers) {
      if (!(type instanceof BodyTypeDeclaration)) {
         codeTypes.add(CodeType.Framework);
         codeFunctions.add(CodeFunction.Program);
         return;
      }

      BodyTypeDeclaration typeDecl = (BodyTypeDeclaration) type;
      while (typeDecl != null) {
         Layer layer = typeDecl.getLayer();
         if (!codeTypes.contains(layer.codeType))
            codeTypes.add(layer.codeType);
         if (!codeFunctions.contains(layer.codeFunction))
            codeFunctions.add(layer.codeFunction);
         typeDecl = typeDecl.getModifiedType();
         if (!allLayers)
            break;
      }
   }

   public static boolean definedInLayer(Object type, Layer layer) {
      if (!(type instanceof BodyTypeDeclaration))
            return false;
      BodyTypeDeclaration typeDecl = (BodyTypeDeclaration) type;
      while (typeDecl != null) {
         if (typeDecl.getLayer() == layer)
            return true;
         typeDecl = typeDecl.getModifiedType();
      }
      return false;
   }

   public static boolean isLayerType(Object type) {
      return type instanceof BodyTypeDeclaration && ((BodyTypeDeclaration) type).isLayerType;
   }

   public static JavaModel getJavaModel(Object node) {
      if (node instanceof JavaSemanticNode)
         return ((JavaSemanticNode) node).getJavaModel();
      return null;
   }

   /** Determines whether or not we display this type in application tree view.  Should we check if the component is
    * validated, has instances, or something?  If so, we need to figure out how to revalidate the view when those things change. */
   public static boolean isApplicationType(Object type) {
      return type instanceof BodyTypeDeclaration;
   }

   public static String validateElement(Parselet element, String str, boolean optional) {
      Object res = element.language.parseString(str, element);
      if (!(res instanceof ParseError)) {
         if (res == null)
            return optional ? null : "Missing value";
         return null;
      }
      return res.toString();
   }

   public static String javaTypeToCompiledString(Object refType, Object type) {
      if (type instanceof JavaType)
         return ((JavaType) type).toCompiledString(refType);
      return ModelUtil.getCompiledTypeName(type);
   }

   public static String evalParameterForType(Object type, Object memberType, String typeParam) {
      if (memberType == type)
         return typeParam;

      Object nextType;
      nextType = ModelUtil.getExtendsClass(type);
      int pos = evalParameterPosition(nextType, memberType, typeParam);
      if (pos == -1) {
         System.err.println("*** Can't evaluate parameter: " + typeParam);
         return typeParam;
      }
      else {
         Object extJavaType = ModelUtil.getExtendsJavaType(type);
         Object typeArg = ModelUtil.getTypeArgument(extJavaType, pos);
         if (ModelUtil.isTypeVariable(typeArg))
            return ModelUtil.getTypeParameterName(typeArg);
         else
            return ModelUtil.javaTypeToCompiledString(type, typeArg);
      }
   }

   public static int evalParameterPosition(Object fromType, Object memberType, String typeParam) {
      if (ModelUtil.sameTypes(fromType, memberType)) {
         List<?> tps = ModelUtil.getTypeParameters(fromType);
         if (tps == null)
            return -1;
         int i = 0;
         for (Object o:tps) {
            String tpN = ModelUtil.getTypeParameterName(o);
            if (tpN.equals(typeParam))
               return i;
            i++;
         }
         return -1;
      }
      else {
         Object extType = ModelUtil.getExtendsClass(fromType);
         int pos = evalParameterPosition(extType, memberType, typeParam);
         if (pos == -1) {
            return -1;
         }
         else {
            Object extJavaType = ModelUtil.getExtendsJavaType(fromType);
            Object typeArg = ModelUtil.getTypeArgument(extJavaType, pos);
            ClassType ct;
            if (typeArg instanceof ClassType && (ct = (ClassType) typeArg).type instanceof TypeParameter)
               typeArg = ct.type;
            if (ModelUtil.isTypeVariable(typeArg)) {
               List<?> tps = ModelUtil.getTypeParameters(fromType);
               if (tps == null)
                  return -1;
               int i = 0;
               for (Object o:tps) {
                  String tpN = ModelUtil.getTypeParameterName(o);
                  if (tpN.equals(typeParam))
                     return i;
                  i++;
               }
            }
         }
      }
      return -1;
   }

   public static Object getTypeArgument(Object javaType, int ix) {
      if (javaType instanceof ClassType)
         return ((ClassType) javaType).getTypeArgument(ix);
      else
         return null;
   }

   /** Converts the type arguments for the compiled class.  Similar to getCompiledClassName but converts the type arguments along the way through the type hierarchy skipping dynamic and skipped classes */
   public static List<JavaType> getCompiledTypeArgs(Object type, List<JavaType> typeArgs) {
      if (type instanceof ITypeDeclaration) {
         return ((ITypeDeclaration) type).getCompiledTypeArgs(typeArgs);
      }
      else if (type instanceof Class) {
         return typeArgs; // No mapping in this case since we have the compiled class
      }
      throw new UnsupportedOperationException();
   }

   public static String getGenericSetMethodPropertyTypeName(Object resultType, Object setMethod, boolean includeDim) {
      if (setMethod instanceof Method) {
         Method setMeth = (Method) setMethod;
         Type[] parameterTypes = setMeth.getGenericParameterTypes();
         if (parameterTypes.length == 0)
            return null;
         String res = resolveGenericTypeName(getEnclosingType(setMethod), resultType, parameterTypes[0], includeDim);
         if (res == null)
            return "Object";
         return res;
      }
      else if (setMethod instanceof IMethodDefinition) {
         IMethodDefinition meth = (IMethodDefinition) setMethod;
         JavaType[] paramTypes = meth.getParameterJavaTypes();
         if (paramTypes.length == 0)
            return null;
         return paramTypes[0].getGenericTypeName(resultType, includeDim);
      }
      else if (setMethod instanceof IBeanMapper) {
         return getGenericSetMethodPropertyTypeName(resultType, ((IBeanMapper) setMethod).getSetSelector(), includeDim);
      }
      throw new UnsupportedOperationException();
   }

   public static Object definesComponentMethod(Object type, String name, Object refType) {
      Object res = ModelUtil.definesMethod(type, name, null, null, refType, false);
      if (res == null)
         res = ModelUtil.definesMethod(type, "_" + name, null, null, refType, false);
      return res;
   }

   public static String getSuperInitTypeCall (LayeredSystem sys, Object objType) {
      Object extType = ModelUtil.getExtendsClass(objType);
      if (extType == null)
         return null;
      if (ModelUtil.needsDynType(extType)) {
         return ModelUtil.getRuntimeTypeName(extType) + "._initType()";
      }
      else if (sys.needsExtDynType(extType)) {
         return sys.buildInfo.getExternalDynTypeName(ModelUtil.getTypeName(extType)) + "._initType()";
      }
      return null;
   }

   public static Object getParamTypeBaseType(Object paramType) {
      if (paramType instanceof ParamTypeDeclaration)
         return ((ParamTypeDeclaration) paramType).getBaseType();
      else if (paramType instanceof ParameterizedType) {
         return ((ParameterizedType) paramType).getRawType();
      }
      return paramType;
   }

   public static int getOuterInstCount(Object pType) {
      if (pType instanceof BodyTypeDeclaration) {
         return ((BodyTypeDeclaration) pType).getOuterInstCount();
      }
      else {
         Object outer = ModelUtil.getEnclosingInstType(pType);
         int ct = 0;
         while (outer != null) {
            ct++;
            outer = ModelUtil.getEnclosingInstType(outer);
         }
         return ct;
      }
   }

   public static Object getSuperMethod(Object method) {
      Object enclType = ModelUtil.getEnclosingType(method);
      Object extClass = ModelUtil.getExtendsClass(enclType);

      if (extClass == null)
         return null;
      Object[] paramTypes = ModelUtil.getParameterTypes(method);
      List<Object> params = paramTypes == null ? null : Arrays.asList(paramTypes);
      // If we're looking for the super of a constructor, use the type name
      String methName = ModelUtil.isConstructor(method) ? CTypeUtil.getClassName(ModelUtil.getTypeName(extClass)) : ModelUtil.getMethodName(method);
      return ModelUtil.definesMethod(extClass, methName, params, null, null, false);
   }

   public static boolean needsClassInit(Object srcType) {
      if (srcType instanceof Class)
         return true;
      if (srcType instanceof BodyTypeDeclaration) {
         return ((BodyTypeDeclaration) srcType).needsClassInit();
      }
      if (srcType instanceof ParamTypeDeclaration) {
         ParamTypeDeclaration pt = (ParamTypeDeclaration) srcType;
         return ModelUtil.needsClassInit(pt.getBaseType());
      }
      else throw new UnsupportedOperationException();
   }

   public static Object resolveSrcMethod(LayeredSystem sys, Object meth, boolean cachedOnly, boolean srcOnly) {
      if (meth instanceof AbstractMethodDefinition)
         return meth;

      Object enclType = ModelUtil.getEnclosingType(meth);
      enclType = ModelUtil.resolveSrcTypeDeclaration(sys, enclType, cachedOnly, srcOnly);
      Object newMeth = ModelUtil.getMethod(enclType, ModelUtil.getMethodName(meth), null, null, ModelUtil.getParameterTypes(meth));
      if (newMeth != null)
         return newMeth;
      return meth;
   }

   public static boolean isProcessableType(Object existing) {
      if (existing instanceof Class)
         return true;
      else if (existing instanceof ITypeDeclaration) {
         Layer layer = ModelUtil.getLayerForType(null, existing);
         if (layer != null) {
            if (layer.compiled)
               return true;
            if (layer.annotationLayer) {
               if (existing instanceof ModifyDeclaration) {
                  Object type = ((ModifyDeclaration) existing).getDerivedTypeDeclaration();
                  if (type != null)
                     return ModelUtil.isProcessableType(type);
                  return false;
               }
            }
         }
         return false;
      }
      return false;
   }

   public static int getNumInnerTypeLevels(Object obj) {
      int ct = 0;
      Object cur = obj;
      Object parent;

      while ((parent = ModelUtil.getEnclosingType(cur)) != null) {
         cur = parent;
         ct++;
      }
      return ct;
   }

   public static int getNdims(Object type) {
      if (type instanceof Class) {
         Class cl = (Class) type;
         if (cl.isArray()) {
            int ndim = 0;
            String className = cl.getName();
            for (int i = 0; i < className.length(); i++)
               if (className.charAt(i) == '[')
                  ndim++;
            return ndim;
         }
         else
            return -1;
      }
      else if (type instanceof JavaType) {
         return ((JavaType) type).getNdims();
      }
      else
         throw new UnsupportedOperationException();
   }

   public static boolean isGlobalScope(LayeredSystem sys, Object typeObj) {
      ScopeDefinition highest = null;
      String scopeName = ModelUtil.getScopeName(typeObj);
      if (scopeName != null) {
         if (sys.isGlobalScope(scopeName))
            return true;
         if (GlobalScopeDefinition.getGlobalScopeDefinition().matchesScope(scopeName))
            return true;
         ScopeDefinition def = ScopeDefinition.getScopeByName(scopeName);
         if (def != null) {
            if (!def.isGlobal())
               return false;
         }
         else if (!scopeName.equals("global")) // TODO: need another way to hook in the set of global scopes for the "process" stage - before this gets set.
            return false;
         else
            return true;

         Object[] innerTypes = getAllInnerTypes(typeObj, null, false);
         if (innerTypes != null) {
            for (Object inner:innerTypes) {
               if (!isGlobalScope(sys, inner))
                  return false;
            }
         }
      }
      return true;
   }

   public static boolean isChangeable(Object prop) {
      Object propType = getPropertyType(prop);
      return propType != null && ModelUtil.isAssignableFrom(IChangeable.class, propType);
   }

   public static void makeBindable(TypeDeclaration type, String propName, boolean needsBindable) {
      Object member = type.definesMember(propName, JavaSemanticNode.MemberType.FieldSet, null, null);
      if (member != null && member instanceof VariableDefinition) {
         // Someone could have added the manual annotation after the fact
         if (!ModelUtil.isManualBindable(member))
            ((VariableDefinition) member).makeBindable(!needsBindable);
         else
            ((VariableDefinition) member).needsDynAccess = true;
      }
      else
         type.addPropertyToMakeBindable(propName, null, null, !needsBindable);
   }

   public static boolean hasUnboundTypeParameters(Object type) {
      if (ModelUtil.hasTypeParameters(type)) {
         int num = getNumTypeParameters(type);
         for (int i = 0; i < num; i++)
            if (ModelUtil.isTypeVariable(ModelUtil.getTypeParameter(type, i)))
               return true;
      }
      return false;
   }

   /**
    * Use this method when you want to get an annotation first on this type, and if not set, use the layer's version.
    * Note that this does not support the inherited flag.  I'm not sure what that would mean... should it also search all dependent layers at the same time?
    * First check all types, then all layers?  Or should inheriting via the layer setting be different than inheriting from the type?
     */
   public static Object getTypeOrLayerAnnotationValue(LayeredSystem sys, Object type, String annotName, String attName) {
      Object settingsObj = ModelUtil.getAnnotation(type, annotName);
      Object value;

      if (settingsObj != null) {
         value = ModelUtil.getAnnotationValue(settingsObj, attName);
         if (value != null)
            return value;
      }

      Layer layer = ModelUtil.getLayerForType(sys, type);
      if (layer != null) {
         settingsObj = ModelUtil.getAnnotation(layer.model.getModelTypeDeclaration(), annotName);
         if (settingsObj != null) {
            return ModelUtil.getAnnotationValue(settingsObj, attName);
         }
      }
      return null;
   }

   public static void initType(Object type) {
      if (type instanceof Class) {
         Class cl = (Class) type;
         // The only way I know of to guarantee we've initialized this class
         try {
            Class.forName(DynUtil.getTypeName(type, false), true, cl.getClassLoader());
         }
         catch (ClassNotFoundException exc) {}
      }
      else {
         ITypeDeclaration td = (ITypeDeclaration) type;
         td.staticInit();
      }
   }

   public static void ensureStarted(Object type, boolean validate) {
      // We always start the model so things get initialized in a consistent order
      if (type instanceof BodyTypeDeclaration) {
         BodyTypeDeclaration td = ((BodyTypeDeclaration) type);
         JavaModel model = td.getJavaModel();
         if (model.isStarted() && !td.isStarted()) {
            if (validate)
               ParseUtil.initAndStartComponent(model);
            else
               ParseUtil.realInitAndStartComponent(model);
         }
         else if (validate) {
            if (!model.isValidated())
               ParseUtil.initAndStartComponent(model);
            else
               ParseUtil.initAndStartComponent(td);
         }
         else {
            ParseUtil.realInitAndStartComponent(model);
         }
      }
      else {
         if (validate)
            ParseUtil.initAndStartComponent(type);
         else
            ParseUtil.realInitAndStartComponent(type);
      }
   }

   public static boolean isTransformedType(Object refType) {
      return refType instanceof ITypeDeclaration && ((ITypeDeclaration) refType).isTransformedType();
   }

   public static boolean hasDynTypeConstructor(Object extType) {
      extType = ModelUtil.getParamTypeBaseType(extType);
      // For Dynamic TypeDeclaration's we will insert the dyn type constructor arg so it's not there.  This check
      // is just to detect when we have a compiled dynamic type but which does not have the constructor parameter
      // we need to actually create it as a dynamic type.
      if (extType instanceof Class || !ModelUtil.isDynamicType(extType)) {
         Object[] constrs = ModelUtil.getConstructors(extType, null);
         if (constrs == null)
            return false;
         for (Object constr:constrs) {
            Object[] paramTypes = ModelUtil.getParameterTypes(constr);
            if (paramTypes != null && paramTypes.length > 0 && ModelUtil.isAssignableFrom(TypeDeclaration.class, paramTypes[0]))
               return true;
         }
         return false;
      }
      else
         return true;
   }

   public static ISemanticNode getTopLevelStatement(ISemanticNode node) {
      Statement st = node instanceof Statement ? (Statement) node : ((JavaSemanticNode) node).getEnclosingStatement();
      ISemanticNode parent = st.getParentNode();

      // We use the block statement that's part of the method to represent the "end of the method"
      if (node instanceof BlockStatement) {
         BlockStatement bst = (BlockStatement) node;
         if (bst.getParentNode() instanceof MethodDefinition)
            return bst;
      }
      if (parent instanceof SemanticNodeList)
         parent = parent.getParentNode();
      if (parent == null || parent instanceof ILanguageModel)
         return st;

      if (parent instanceof Statement) {
         Statement parentStatement = (Statement) parent;
         if (parentStatement.childIsTopLevelStatement(st))
            return st;
      }

      // TODO: what about variable definitions and fields?  Shouldn't the variable definition be the top-level statement?
      return getTopLevelStatement(parent);
   }

   public static void updateFromStatementRefs(SemanticNodeList list, SemanticNodeList<Statement> fromStatements, ISrcStatement defaultSt) {
      if (fromStatements != null) {
         for (int fromIx = 0; fromIx < fromStatements.size(); fromIx++) {
            Statement fromSt = fromStatements.get(fromIx);
            boolean found = false;
            for (Object newSemNode:list) {
               if (newSemNode instanceof Statement) {
                  Statement st = (Statement) newSemNode;
                  if (st.updateFromStatementRef(fromSt, defaultSt)) {
                     found = true;
                     break;
                  }
               }
            }
            if (!found)
               System.err.println("*** Warning - some statements not matched up with the generated source: " + fromSt);
         }
      }
      // Do this even if there are no assignments - any statement which does not have a fromStatement will be set to
      // the default statement.
      if (defaultSt != null) {
         for (Object newSemNode:list) {
            if (newSemNode instanceof Statement) {
               Statement st = (Statement) newSemNode;
               st.updateFromStatementRef(null, defaultSt);
            }
         }
      }
   }
}
