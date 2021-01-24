/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.classfile.CFClass;
import sc.classfile.CFMethod;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.util.StringUtil;

import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class ParamTypedMethod implements ITypedObject, IMethodDefinition, ITypeParamContext {
   public Object method;
   ITypeParamContext paramTypeDecl;
   Object[] methodTypeParams;

   /* For method invocations which bind specific types to parameters - e.g. foo.<type1,type2>methodCall() - this stores type1, type2 */
   List<JavaType> methodTypeArgs;

   // Stores the target type of the method expression.  Since Java8 this is an alternate source
   // for type parameter values used in resolving parameter types of the method invocation.
   Object inferredType;

   // --- These next type definitions are computed and cached the first time used and when inferredType is changed.
   // These are the types of the method as they are declared
   JavaType[] paramJavaTypes;
   // Resolved type parameters
   JavaType[] resolvedParamJavaTypes;
   // The types of the expressions used in this method call
   JavaType[] boundJavaTypes;

   // The resulting types of the parameters of these methods - without defaults for unbound type parameters
   Object[] paramTypes;
   // The resulting types of the parameters of these methods - with type parameters removed (defaults used)
   Object[] boundTypes;

   // Set to true when this type is derived from parameter types which are not defined until the inferredType is set
   // It allows us to match against this parameter even if a type variable is not assigned.
   boolean unboundInferredType = false;
   // Set to true when you have a <T extends X> and T also maps to a conflicting value
   public boolean invalidTypeParameter = false;

   public boolean bindParamTypes = true;

   // This is the type declaration which defines the use of this param typed method
   Object definedInType;

   LayeredSystem system;

   public ParamTypedMethod(LayeredSystem sys, Object meth, ITypeParamContext paramTypeDeclaration, Object definedInType, List<? extends Object> parametersOrExpressions, Object inferredType, List<JavaType> methodTypeArgs) {
      if (meth == null)
         System.out.println("*** Warning null method for method typed parameter");
      system = sys;
      method = meth;
      paramTypeDecl = paramTypeDeclaration;
      this.inferredType = inferredType;
      this.methodTypeArgs = methodTypeArgs;
      methodTypeParams = ModelUtil.getMethodTypeParameters(method);
      this.definedInType = definedInType;

      /*
      if (meth.toString().equals("protected static Key<T> config(Key<T> baseKey, String configValue)  in ShiroWebModuleWith435 layer: killbill.core") &&
         parametersOrExpressions != null && parametersOrExpressions.toString().equals("[Key.get(type), configValue]"))
         System.out.println("***");
      */

      if (parametersOrExpressions != null) {
         boundJavaTypes = ModelUtil.parametersToJavaTypeArray(system, this, parametersOrExpressions, this);
      }
      paramTypes = null; // Need to recompute this once we've set the boundJavaTypes
      resolvedParamJavaTypes = null;
   }

   public Object getTypeDeclaration() {
      return getTypeDeclaration(null, true);
   }

   public void rebindArguments(List<Expression> arguments) {
      if (arguments != null) {
         paramTypes = null;
         resolvedParamJavaTypes = null;
         boundTypes = null;
         boundJavaTypes = ModelUtil.parametersToJavaTypeArray(system, this, arguments, this);
      }
   }

   private Object resolveParameterizedType(Object parameterizedType, boolean resolve) {
      if (ModelUtil.isParameterizedType(parameterizedType)) {
         // Parameterized type here is a ParamTypeDeclaration with types that are unbound type parameters.
         // Need to look those up in methodTypeParams.  If we find one, need to clone the ParamTypeDeclaration - binding
         // the parameter type value with it's type.
         if (parameterizedType instanceof ParamTypeDeclaration) {
            ParamTypeDeclaration paramType = (ParamTypeDeclaration) parameterizedType;
            ParamTypeDeclaration newType = null;
            int i = 0;
            boolean hasParamTypeParam = false;
            for (Object type:paramType.types) {
               if (ModelUtil.hasTypeVariables(type)) {
                  Object newVal = resolveTypeParameter(type, resolve);
                  if (newVal instanceof ExtendsType.LowerBoundsTypeDeclaration && ((ExtendsType.LowerBoundsTypeDeclaration) newVal).baseType == Object.class) {
                     newVal = type;
                  }
                  if (newVal != type && newVal != null && newVal != Object.class) {
                     if (newType == null) {
                        newType = paramType.cloneForNewTypes();
                     }
                     newType.setTypeParamIndex(i, ModelUtil.wrapPrimitiveType(newVal));
                  }
               }
               else if (ModelUtil.isParameterizedType(type)) {

                  Object newParamTypeValue = resolveParameterizedType(type, resolve);
                  if (newParamTypeValue != type && newParamTypeValue != null) {
                     if (newType == null) {
                        newType = paramType.cloneForNewTypes();
                     }
                     newType.setTypeParamIndex(i, ModelUtil.wrapPrimitiveType(newParamTypeValue));
                  }
               }
               i++;
            }
            if (newType != null) {
               if (unboundInferredType)
                  newType.unboundInferredType = true;
               return newType;
            }
            else if (unboundInferredType)
               paramType.unboundInferredType = true;
         }
         else if (parameterizedType instanceof ParameterizedType) {
            int numParams = ModelUtil.getNumTypeParameters(parameterizedType);
            ParamTypeDeclaration newType = null;
            for (int i = 0; i < numParams; i++) {
               Object typeParam = ModelUtil.getTypeParameter(parameterizedType, i);
               if (ModelUtil.hasTypeVariables(typeParam)) {
                  Object newVal = resolveTypeParameter(typeParam, resolve);
                  if (newVal != typeParam && newVal != null && newVal != Object.class) {
                     if (newType == null) {
                        newType = createParamTypeFromNative(parameterizedType);
                     }
                     // No need to replace the old type with a wildcard since it does not add info
                     if (newType != null && !(newVal instanceof ExtendsType.WildcardTypeDeclaration))
                        newType.setTypeParamIndex(i, ModelUtil.wrapPrimitiveType(newVal));
                  }
               }
               else if (ModelUtil.isParameterizedType(typeParam)) {
                  Object newParamTypeValue = resolveParameterizedType(typeParam, resolve);
                  if (newParamTypeValue != typeParam && newParamTypeValue != null) {
                     if (newType == null) {
                        newType = createParamTypeFromNative(parameterizedType);
                     }
                     newType.setTypeParamIndex(i, ModelUtil.wrapPrimitiveType(newParamTypeValue));
                  }
               }
            }
            if (newType != null) {
               if (unboundInferredType)
                  newType.unboundInferredType = true;
               return newType;
            }
         }
      }
      return parameterizedType;
   }

   private ParamTypeDeclaration createParamTypeFromNative(Object parameterizedType) {
      List<?> typeParams = ModelUtil.getTypeParameters(ModelUtil.getTypeDeclFromType(this, parameterizedType, true, getLayeredSystem(), true, definedInType, null, -1));
      int numParams = ModelUtil.getNumTypeParameters(parameterizedType);
      Object baseType = ModelUtil.getParamTypeBaseType(parameterizedType);
      ArrayList<Object> typeDefs = new ArrayList<Object>(numParams);
      ParamTypeDeclaration newType;
      for (int j = 0; j < numParams; j++)
         typeDefs.add(typeParams.get(j));
      newType = new ParamTypeDeclaration(system, definedInType, typeParams, typeDefs, baseType);
      newType.writable = true;
      return newType;
   }

   public Object getTypeDeclaration(List<? extends ITypedObject> args, boolean resolve) {
      Object parameterizedType = ModelUtil.getParameterizedReturnType(method, args, false);
      if (parameterizedType == null)
         return null; // Unresolved type reference
      if (ModelUtil.hasTypeVariables(parameterizedType)) {
         Object res = resolveTypeParameter(parameterizedType, resolve);
         if (res == null) {
            //System.err.println("*** Returning null for type of method"); - happens when we have unresolved stuff
            res = resolveTypeParameter(parameterizedType, resolve);
         }
         return res;
      }
      parameterizedType = resolveParameterizedType(parameterizedType, resolve);
      if (parameterizedType == null)
         System.out.println("*** Returning null for param typed method");
      return parameterizedType;
   }

   private Object resolveTypeParameter(Object typeVariable, boolean resolve) {
      if (ModelUtil.isTypeVariable(typeVariable)) {
         String typeVarName = ModelUtil.getTypeParameterName(typeVariable);
         return getTypeDeclarationForParam(typeVarName, typeVariable, resolve);
      }
      else if (typeVariable instanceof ExtendsType.LowerBoundsTypeDeclaration) {
         return ((ExtendsType.LowerBoundsTypeDeclaration) typeVariable).resolveTypeVariables(this, resolve);
      }
      else if (ModelUtil.isGenericArray(typeVariable)) {
         Object origCompType = ModelUtil.getGenericComponentType(typeVariable);
         Object compType = resolveTypeParameter(origCompType, resolve);
         if (origCompType == compType || compType == null)
            return typeVariable;
         return ArrayTypeDeclaration.create(system, compType, 1, definedInType);
      }
      else if (typeVariable instanceof WildcardType) {
         Object wildcardBounds = ModelUtil.getWildcardBounds(typeVariable);
         if (ModelUtil.isTypeVariable(wildcardBounds)) {
            Object newBase = getTypeDeclarationForParam(ModelUtil.getTypeParameterName(wildcardBounds), wildcardBounds, resolve);
            if (!ModelUtil.isWildcardType(newBase) && !ModelUtil.isUnboundedWildcardType(newBase)) {
               if (ModelUtil.isSuperWildcard(typeVariable))
                  return new ExtendsType.LowerBoundsTypeDeclaration(system, newBase);
               else
                  return newBase; // Is this right?
            }
         }
         return typeVariable;
      }
      else
         throw new UnsupportedOperationException();
   }

   private Object resolveMethodTypeParameter(String typeVarName, Object typeVar) {
      if (methodTypeArgs != null) {
         if (methodTypeArgs.size() == methodTypeParams.length) {
            for (int i = 0; i < methodTypeArgs.size(); i++) {
               if (ModelUtil.getTypeParameterName(methodTypeParams[i]).equals(typeVarName)) {
                  if (!ModelUtil.sameTypeParameters(typeVar, methodTypeParams[i]))
                     System.out.println("*** Error - invalid method type parameter case");
                  return methodTypeArgs.get(i);
               }
            }
         }
      }
      // For method type parameters, do we need to derive the type of each parameter from types we were able to resolve
      // from the parameters?
      // TODO: We may be in the midst of creating the parameters and so can't use these values yet - since they are not defined
      // TODO: should we use getResolvedParameterJavaTypes here?
      JavaType[] paramJavaTypes = getParameterJavaTypes(true);

      Object paramRes = null;

      int i = 0;
      if (paramJavaTypes != null) {
         boolean isVarArgs = ModelUtil.isVarArgs(method);
         for (JavaType paramJavaType : paramJavaTypes) {
            if (paramJavaType != null) {
               Object type = paramTypeDecl == null ? null : paramJavaType.definesTypeParameter(typeVar, paramTypeDecl);
               if (type != null) {
                  // There are times when we need to bind the type parameter to the type of the argument passed to the method.
                  if (bindParamTypes && boundJavaTypes != null && i < boundJavaTypes.length) {
                     JavaType boundJavaType = boundJavaTypes[i];
                     if (boundJavaType != null) {
                        Object res = boundJavaType.getTypeDeclaration(); // pass in type context here?
                        // An array type in the last slot of a method defined like Arrays.toArray(T... value) really means
                        // that T is assigned to the component type, not the array type.
                        if (i == boundJavaTypes.length - 1 && ModelUtil.isVarArgs(method) && ModelUtil.isArray(res))
                           paramRes = ModelUtil.getArrayComponentType(res);
                        else
                           paramRes = res;
                        break;
                     }
                  }
                  Object newParamRes;
                  if (type instanceof JavaType)
                     newParamRes = ((JavaType) type).getTypeDeclaration();
                  else
                     newParamRes = type;
                  if (paramRes != null)
                     paramRes = ModelUtil.coerceTypes(getLayeredSystem(), paramRes, newParamRes);
                  else
                     paramRes = newParamRes;
               }
               if (bindParamTypes && boundJavaTypes != null && i < boundJavaTypes.length) {
                  // First get the type variable from the bound argument in the slot corresponding to this parameter
                  type = extractTypeVarFromParam(typeVar, paramJavaType, i, isVarArgs);
                  if (type != null) {
                     if (paramRes != null)
                        paramRes = ModelUtil.coerceTypes(getLayeredSystem(), paramRes, type);
                     else
                        paramRes = type;
                  }

                  // Process any varArgs matches here, but only before we've computed the boundTypes.  Once we have
                  // the boundTypes, we'll have processed the method parameter's type already.
                  if (i == paramJavaTypes.length - 1 && isVarArgs && boundTypes == null) {
                     for (int j = i+1; j < boundJavaTypes.length; j++) {
                        type = extractTypeVarFromParam(typeVar, paramJavaType, j, true);
                        if (type != null) {
                           if (paramRes != null)
                              paramRes = ModelUtil.coerceTypes(getLayeredSystem(), paramRes, type);
                           else
                              paramRes = type;
                        }
                     }
                  }
               }
            }
            i++;
         }
      }

      if (inferredType != null && inferredType != Object.class && !ModelUtil.isTypeVariable(inferredType)) {
         Object methReturnJavaType = ModelUtil.getReturnJavaType(method);
         // TODO: maybe false here for resolve?
         Object methReturnType = ModelUtil.getReturnType(method, true);
         if (methReturnJavaType != null) {
            Object extRes = extractTypeParameter(methReturnJavaType, methReturnType, inferredType, typeVar, false, false);
            if (extRes != null) {
               if (paramRes != null)
                  return ModelUtil.blendTypes(paramRes, extRes);
               return extRes;
            }
         }
         //Object res = inferredParamType.getTypeForVariable(typeVar, false);
         //if (res != null && !ModelUtil.isTypeVariable(res))
         //   return res;
      }
      return paramRes;
   }

   private Object extractTypeVarFromParam(Object typeVar, JavaType paramJavaType, int paramIndex, boolean isVarArgs) {
      JavaType boundJavaType = boundJavaTypes[paramIndex];
      Object paramRes = null;
      if (boundJavaType != null) {
         Object boundParamType;
         // Once we have computed the bound types - use these type decls.  We modify these types
         // when inferring type parameters
         if (boundTypes != null && paramIndex < boundTypes.length) {
            boundParamType = boundTypes[paramIndex];
         }
         else
            boundParamType = boundJavaType.getTypeDeclaration();
         Object paramType = paramJavaType.getTypeDeclaration();
         // Is this an array specified as the varArgs parameter
         boolean varArgsArrayParam = paramIndex == boundJavaTypes.length - 1 && isVarArgs && ModelUtil.isArray(boundParamType);
         // Is this a scalar parameter passed to a varArgs method
         boolean varArgsParam = paramIndex >= paramJavaTypes.length - 1 && isVarArgs && (ModelUtil.isArray(boundParamType) || ModelUtil.isArray(paramType));
         if (varArgsArrayParam && !ModelUtil.isArray(paramType)) {
            boundParamType = ModelUtil.getArrayComponentType(boundParamType);
         }

         // Do we have an actual bound value for this type parameter - if so, return it without checking the inferredType.
         // If this is any type of unbound type parameter expression - e.g. ? super T we need to check for T in the inferredtype
         // and so should not return.
         Object type = extractTypeParameter(paramJavaType, paramType, boundParamType, typeVar, varArgsParam, varArgsArrayParam);
         if (type != null && !ModelUtil.isTypeVariable(type)) {
            paramRes = type;
         }
      }
      return paramRes;
   }

   /** This method is called when we are trying to resolve a specific method type paraemter - typeVar.  We are given
    * the paramJavaType - the declaration of the parameter.  The paramType - that type's current type, and the bound
    * type for that parameter.   We walk the type hierarchy of paramJavaType to see if we can find "typeVar" and if
    * that type is assigned a value.
    */
   public Object extractTypeParameter(Object paramJavaType, Object paramType, Object boundType, Object typeVar, boolean varArgParam, boolean varArgArrayParam) {
      if (ModelUtil.isTypeVariable(paramType)) {
         if (paramJavaType instanceof ClassType) {
            ClassType ct = (ClassType) paramJavaType;
            if (!ModelUtil.isTypeVariable(ct.type) || !ModelUtil.sameTypeParameters(ct.type, typeVar))
               return null;
         }
         else {
            // Is this an extends type of bounded type - if so and it matches this parameter we'll use the bound type
            if (paramJavaType instanceof JavaType) {
               if (paramJavaType instanceof ExtendsType) {
                  ExtendsType extType = (ExtendsType) paramJavaType;
                  // TODO: should we also test here that paramJavaType's extends argument is the same as paramType?
                  if (extType.operator != null && extType.operator.equals("extends") && extType.typeArgument != null) {
                     Object typeArgType = extType.typeArgument.getTypeDeclaration();
                     if (ModelUtil.isTypeVariable(typeArgType) && ModelUtil.sameTypeParameters(typeArgType, typeVar))
                        return boundType;
                  }
               }
               return null;
            }
            else if (!ModelUtil.sameTypeParameters(paramJavaType, typeVar))
               return null;
         }
         if (ModelUtil.isTypeVariable(boundType) || ModelUtil.isUnboundSuper(boundType))
            return null;
         if (ModelUtil.isArray(boundType) && varArgArrayParam)
            return ModelUtil.getArrayComponentType(boundType);
         return boundType;
      }
      else if (paramType instanceof ExtendsType.LowerBoundsTypeDeclaration) {
         if (ModelUtil.isWildcardType(paramJavaType) && ModelUtil.isSuperWildcard(paramJavaType)) {
            Object paramBounds = ModelUtil.getWildcardLowerBounds(paramJavaType);
            if (ModelUtil.isTypeVariable(paramBounds) && ModelUtil.sameTypeParameters(paramBounds, typeVar)) {
               // Don't return unbound things -
               if (ModelUtil.isTypeVariable(boundType) || ModelUtil.isUnboundSuper(boundType))
                  return null;
               if (typeVar != boundType)
                  return boundType;
            }
         }
         else
            return null;
      }
      else if (ModelUtil.isGenericArray(paramType)) {
         Object compType = ModelUtil.getArrayComponentType(paramType);
         if (ModelUtil.isTypeVariable(compType) && ModelUtil.sameTypeParameters(compType, typeVar)) {
            // We have for example Arrays.asList("a", "b") bound to T[] - T = the type of "a"
            if (varArgParam && !ModelUtil.isArray(boundType))
               return boundType;
            Object boundCompType = ModelUtil.getArrayComponentType(boundType);
            // Weird case - we have a T... params which comes in here as T[].  There are two ways we could handle it -
            // either bind T to the component type or T to the array itself.  For primitive arrays, it will not bind on the
            // component type so we return the array.
            if (varArgArrayParam && ModelUtil.isPrimitive(boundCompType))
               return boundType;
            return boundCompType;
         }
      }
      // TODO: is this wildcard test right?  If we don't do this here, we can get an exception in isAssignableFrom
      if (ModelUtil.isWildcardType(boundType))
         boundType = ModelUtil.getWildcardBounds(boundType);
      if (boundType instanceof ExtendsType.LowerBoundsTypeDeclaration)
         boundType = ((ExtendsType.LowerBoundsTypeDeclaration) boundType).baseType;
      if (ModelUtil.isTypeVariable(boundType))
         return null;

      boolean sameParamTypes = true;
      boolean boundTypeSubType = false;
      if (!ModelUtil.isAssignableFrom(paramType, boundType, false, null, true, getLayeredSystem())) {
         // If the parameter type is a sub-type of the bound type, we still need to see if we can map
         // the desired type parameter to a bound-type parameter in boundType.   Apparently the inferredType
         // can be a base-type of the type from which we get type variables
         if (!ModelUtil.isAssignableFrom(boundType, paramType)) {
            return null;
         }
         //else
         //    TODO: Do we need to map typeVar or paramType or maybe boundType so we take into account type parameters which have been renamed?
         boundTypeSubType = false;
         // TODO: for now we are treating these as the same
         sameParamTypes = ModelUtil.getNumTypeParameters(boundType) == ModelUtil.getNumTypeParameters(paramType);
         if (!sameParamTypes)
            return null;
      }
      else {
         boundTypeSubType = true;
         sameParamTypes = ModelUtil.sameTypes(boundType, paramType);
      }

      if (paramJavaType instanceof ExtendsType) {
         paramJavaType = ((ExtendsType) paramJavaType).typeArgument;
         if (paramJavaType == null)
            return null;
      }
      int numParams = ModelUtil.getNumTypeParameters(paramType);
      //int boundParams = sameParamTypes ? ModelUtil.getNumTypeParameters(boundType) : -1;
      for (int i = 0; i < numParams; i++) {
         Object typeParamArg = ModelUtil.getTypeArgument(paramJavaType, i);
         Object typeParam = ModelUtil.getTypeDeclFromType(null, typeParamArg, false, getLayeredSystem(), false, getDefinedInType(), paramType, i);
         Object typeParamType = ModelUtil.getTypeParameter(paramType, i);
         // Unresolved type
         if (typeParam == null)
            continue;
         if (sameParamTypes) {
            Object boundParam = ModelUtil.getTypeParameter(boundType, i);
            // Do not try to extract the ? as it does not add any information and makes this parameter seem bound when it's not
            if (boundParam instanceof ExtendsType.WildcardTypeDeclaration) {
               continue;
            }
            Object nestedRes = extractTypeParameter(typeParamArg, typeParam, boundParam, typeVar, false, false);
            if (nestedRes != null)
               return nestedRes;
         }
         else {
            if (boundTypeSubType) {
               List<Object> extTypes = ModelUtil.getExtendsJavaTypePath(paramType, boundType);
               int srcIx = i;
               // Starting at the base type
               Object nextType = null;
               for (int extIx = extTypes.size() - 1; extIx >= 0; extIx--) {
                  Object ext = extTypes.get(extIx);
                  List<?> typeArgs = ModelUtil.getTypeArguments(ext);
                  if (typeArgs == null)
                     return null;
                  Object typeArg = typeArgs.get(srcIx);
                  if (typeArg instanceof JavaType)
                     typeArg = ((JavaType) typeArg).getTypeDeclaration();
                  if (ModelUtil.isTypeVariable(typeArg)) {
                     String nextTypeParamName = ModelUtil.getTypeParameterName(typeArg);

                     if (extIx == 0)
                        nextType = boundType;
                     else {
                        nextType = extTypes.get(extIx - 1);
                        if (nextType instanceof JavaType)
                           nextType = ((JavaType) nextType).getTypeDeclaration();
                     }

                     srcIx = ModelUtil.getTypeParameterPosition(nextType, nextTypeParamName);
                  }
                  else {
                     // An explicit bound type - let's see if we can find the typeVar inside of this bound type
                     Object nestedRes = extractTypeParameter(typeParamArg, typeParam, typeArg, typeVar, false, false);
                     if (nestedRes != null)
                        return nestedRes;
                  }
               }
               if (nextType != null && srcIx != -1) {
                  Object boundParam = ModelUtil.getTypeParameter(nextType, srcIx);
                  // Do not try to extract the ? as it does not add any information and makes this parameter seem bound when it's not
                  if (boundParam instanceof ExtendsType.WildcardTypeDeclaration) {
                     continue;
                  }
                  Object nestedRes = extractTypeParameter(typeParamArg, typeParam, boundParam, typeVar, false, false);
                  if (nestedRes != null)
                     return nestedRes;
               }

               /*
               Object nestedRes = ModelUtil.resolveTypeParameter(paramType, boundType, typeParam);
               if (nestedRes != null && !ModelUtil.isTypeVariable(nestedRes))
                  return nestedRes;
               */
            }
            else {
               Object nestedRes = ModelUtil.resolveBaseTypeParameter(paramType, boundType, typeParam);
               if (nestedRes != null && !ModelUtil.isTypeVariable(nestedRes))
                  return nestedRes;
            }
         }
      }
      return null;
   }

   public String getGenericTypeName(Object resultType, boolean includeDims) {
      return ModelUtil.getGenericTypeName(resultType, method, includeDims);
   }

   public String getAbsoluteGenericTypeName(Object resultType, boolean includeDims) {
      return ModelUtil.getAbsoluteGenericTypeName(resultType, method, includeDims);
   }

   public String getMethodName() {
      return ModelUtil.getMethodName(method);
   }

   public Object getDeclaringType() {
      return ModelUtil.getMethodDeclaringClass(method);
   }

   public Object getReturnType(boolean boundParams) {
      return ModelUtil.getReturnType(method, boundParams);
   }

   public Object getReturnJavaType() {
      return ModelUtil.getReturnJavaType(method);
   }

   public Object[] getParameterTypes(boolean bound) {
      if (bound) {
         if (boundTypes != null)
            return boundTypes;
      }
      else {
         if (paramTypes != null)
            return paramTypes;
      }
      return resolveParameterTypes(bound, null, 0, false, true);
   }

   public Object[] resolveParameterTypes(boolean bound, Object[] oldRes, int startIx, boolean refreshParams, boolean bindUnbound) {
      // Refresh the types as well as the parameters
      if (refreshParams) {
         if (bound)
            resolvedParamJavaTypes = null;
         else
            paramJavaTypes = null;
      }

      JavaType[] javaTypes = bound ? getResolvedParameterJavaTypes() : getParameterJavaTypes(true);
      if (javaTypes == null)
         return null;
      int len = javaTypes.length;
      Object[] res = oldRes == null ? new Object[len] : oldRes;
      for (int i = startIx; i < len; i++) {
         JavaType paramJavaType = javaTypes[i];
         res[i] = paramJavaType.getTypeDeclaration(bound ? this : null, definedInType, false, refreshParams, bindUnbound, null, -1);
         if (res[i] instanceof ClassType) {
            Object newRes = javaTypes[i].getTypeDeclaration();
            if (newRes != null)
               res[i] = newRes;
         }
         // We need to make a copy here because we might change these parameters for this particular instantiation of this method:
         //   - i.e. we set an inferredType on one of our arguments - a LambdaExpression and it further refines the type represented by this parameter
         if (bound) {
            Object nextRes = res[i];
            // TODO: use an interface here?
            if (nextRes instanceof ParamTypeDeclaration) {
               res[i] = ((ParamTypeDeclaration) nextRes).cloneForNewTypes();
            }
            else if (nextRes instanceof ArrayTypeDeclaration) {
               res[i] = ((ArrayTypeDeclaration) nextRes).cloneForNewTypes();
            }
         }
         // Happens for unresolved type references
         //if (res[i] == null)
         //   System.out.println("*** Warning - null value for parameter type");
      }
      // Don't cache the result if this flag is temporarily turned off
      if (!bindParamTypes)
         return res;
      if (bound)
         boundTypes = res;
      else
         paramTypes = res;
      return res;

   }

   public JavaType[] getResolvedParameterJavaTypes() {
      if (resolvedParamJavaTypes != null && bindParamTypes)
         return resolvedParamJavaTypes;

      if (method instanceof IMethodDefinition) {
         JavaType[] paramTypes = getParameterJavaTypes(true);
         if (paramTypes == null)
            return null;
         int len = paramTypes.length;
         JavaType[] result = null;
         for (int i = 0; i < len; i++) {
            JavaType paramType = paramTypes[i];
            if (paramType.isParameterizedType()) {
               JavaType resolvedType = paramType.resolveTypeParameters(this, false);
               if (resolvedType != paramType) {
                  if (result == null) {
                     result = new JavaType[len];
                     System.arraycopy(paramTypes, 0, result, 0, len);
                  }
                  result[i] = resolvedType;
               }
            }
         }
         JavaType[] res = result != null ? result : paramTypes;
         if (!bindParamTypes)
            return res;
         resolvedParamJavaTypes = res;
         return resolvedParamJavaTypes;
      }
      else if (method instanceof Method) {
         java.lang.reflect.Type[] paramTypes = ((Method) method).getGenericParameterTypes();
         return getNativeResolvedParameterJavaTypes(paramTypes);
      }
      else if (method instanceof Constructor) {
         java.lang.reflect.Type[] paramTypes = ((Constructor) method).getGenericParameterTypes();
         return getNativeResolvedParameterJavaTypes(paramTypes);
      }
      throw new UnsupportedOperationException();
   }

   private JavaType[] getNativeResolvedParameterJavaTypes(java.lang.reflect.Type[] paramTypes) {
      int len = paramTypes.length;
      if (len == 0)
         return null;
      JavaType[] res = new JavaType[len];

      for (int i = 0; i < len; i++) {
         Object paramType = paramTypes[i];
         if (paramType instanceof ExtendsType.LowerBoundsTypeDeclaration)
            System.out.println("*** Unknown lower bounds type (6)");
         if (ModelUtil.hasTypeVariables(paramType)) {
            Object newParamType = resolveTypeParameter(paramType, false); // Needs to be false
            if (newParamType != null)
               paramType = newParamType;
         }
         JavaType javaType = JavaType.createFromParamType(system, paramType, this, null);
         if (javaType.isParameterizedType()) {
            JavaType newVal = javaType.resolveTypeParameters(this, false);
            if (newVal != null) {
               newVal.initType(getLayeredSystem(), getDefinedInType(), null, this, false, false, javaType.getTypeDeclaration());
               if (newVal instanceof ClassType && ((ClassType) newVal).type == ClassType.FAILED_TO_INIT_SENTINEL) {
                  ((ClassType) newVal).type = javaType.getTypeDeclaration();
               }
               javaType = newVal;
            }
         }
         res[i] = javaType;
      }
      if (!bindParamTypes)
         return res;
      resolvedParamJavaTypes = res;
      return resolvedParamJavaTypes;

   }


   public JavaType[] getParameterJavaTypes(boolean convertRepeating) {
      if (paramJavaTypes != null)
         return paramJavaTypes;
      if (!convertRepeating)
         System.out.println("*** Error deprecated mode!");

      if (method instanceof IMethodDefinition) {
         JavaType[] paramTypes = ((IMethodDefinition) method).getParameterJavaTypes(convertRepeating);
         if (paramTypes == null)
            return null;
         paramJavaTypes = paramTypes;
         return paramJavaTypes;
      }
      // TODO: In Java 1.8 they have an Executable interface shared between Method and Constructor
      else if (method instanceof Method) {
         java.lang.reflect.Type[] paramTypes = ((Method) method).getGenericParameterTypes();

         return getNativeParameterJavaTypes(paramTypes);
      }
      else if (method instanceof Constructor) {
         java.lang.reflect.Type[] paramTypes = ((Constructor) method).getGenericParameterTypes();
         return getNativeParameterJavaTypes(paramTypes);
      }
      else
         throw new UnsupportedOperationException();
   }

   private JavaType[] getNativeParameterJavaTypes(java.lang.reflect.Type[] paramTypes) {
      int len = paramTypes.length;
      if (len == 0)
         return null;

      JavaType[] res = paramJavaTypes = new JavaType[len];
      for (int i = 0; i < len; i++) {
         Object paramType = paramTypes[i];
         // Do not supply a TypeParamContext here as that will cause these to be resolved.  This should be the parameter types with type variables unresolved.
         JavaType javaType = JavaType.createFromParamType(system, paramType, null, null);
         res[i] = javaType;
      }
      return res;
   }

   public String getPropertyName() {
      return ModelUtil.getPropertyName(method);
   }

   public boolean hasField() {
      return ModelUtil.hasField(method);
   }

   public boolean hasGetMethod() {
      return ModelUtil.hasGetMethod(method);
   }

   public boolean hasSetMethod() {
      return ModelUtil.hasSetMethod(method);
   }

   public boolean hasSetIndexMethod() {
      return ModelUtil.hasSetIndexMethod(method);
   }

   public Object getGetMethodFromSet() {
      return ModelUtil.getGetMethodFromSet(method);
   }

   public Object getSetMethodFromGet() {
      return ModelUtil.getSetMethodFromGet(method);
   }

   @Override
   public Object getFieldFromGetSetMethod() {
      return ModelUtil.getFieldFromGetSetMethod(method);
   }

   public boolean isGetMethod() {
      return ModelUtil.isGetMethod(method);
   }

   public boolean isSetMethod() {
      return ModelUtil.isSetMethod(method);
   }

   public boolean isGetIndexMethod() {
      return ModelUtil.isGetIndexMethod(method);
   }

   public boolean isSetIndexMethod() {
      return ModelUtil.isSetIndexMethod(method);
   }

   public boolean isVarArgs() {
      return ModelUtil.isVarArgs(method);
   }

   @Override
   public boolean isConstructor() {
      return ModelUtil.isConstructor(method);
   }

   public String getTypeSignature() {
      // Assuming we do not have to do anything here since we want the type sig of the method in the class format.
      return ModelUtil.getTypeSignature(method);
   }

   public String getParameterString() {
      // Assuming we do not have to do anything here since we want the type sig of the method in the class format.
      return ModelUtil.getParameterString(method);
   }

   public Object[] getExceptionTypes() {
      return ModelUtil.getExceptionTypes(method);
   }

   public String getThrowsClause() {
      return ModelUtil.getThrowsClause(method);
   }

   public Object[] getMethodTypeParameters() {
      return methodTypeParams;
   }

   public int getNumParameters() {
      return ModelUtil.getNumParameters(method);
   }

   public Object getAnnotation(String annotName, boolean checkModified) {
      return ModelUtil.getAnnotation(method, annotName);
   }

   public List<Object> getRepeatingAnnotation(String annotName) {
      return ModelUtil.getRepeatingAnnotation(method, annotName);
   }

   public Map<String,Object> getAnnotations() {
      return ModelUtil.getAnnotations(method);
   }

   public boolean hasModifier(String modifierName) {
      return ModelUtil.hasModifier(method, modifierName);
   }

   public AccessLevel getAccessLevel(boolean explicitOnly) {
      return ModelUtil.getAccessLevel(method, explicitOnly);
   }

   public Object getEnclosingIType() {
      return ModelUtil.getEnclosingType(method);
   }

   public String modifiersToString(boolean includeAnnotations, boolean includeAccess, boolean includeFinal, boolean includeScope, boolean abs, JavaSemanticNode.MemberType filterType) {
      return ModelUtil.modifiersToString(method, includeAnnotations, includeAccess, includeFinal, includeScope, abs, filterType);
   }

   public Object getType(int position) {
      return paramTypeDecl.getType(position);
   }

   public Object getDefaultType(int position) {
      return paramTypeDecl.getDefaultType(position);
   }

   public Object getTypeForVariable(Object typeVar, boolean resolve) {
      // Always returning the type - not a type parameter
      return resolveTypeParameter(typeVar, resolve);
   }

   public Object getTypeDeclarationForParam(String typeVarName, Object typeVar, boolean resolve) {
      Object def = typeVar != null ? ModelUtil.getTypeParameterDeclaration(typeVar) : null;
      boolean isMethod = def != null && (ModelUtil.isMethod(def) || ModelUtil.isConstructor(def));
      Object paramRes = null;
      Object inferredRes = null;
      if (def == null || isMethod) {
         if (methodTypeParams != null) {
            for (Object typeParam : methodTypeParams) {
               if (ModelUtil.getTypeParameterName(typeParam).equals(typeVarName)) {
                  if (ModelUtil.sameMethods(def, method)) {
                     Object res = resolveMethodTypeParameter(typeVarName, typeParam);
                     Object newRes = null;
                     if (res instanceof JavaType) {
                        newRes = ((JavaType) res).getTypeDeclaration();
                     }
                     else
                        newRes = res;

                     if (newRes != null) {
                        if (typeParam instanceof TypeParameter) {
                           JavaType extType = ((TypeParameter) typeParam).extendsType;
                           Object extTypeDecl = null;
                           if (extType != null && (extTypeDecl = extType.getTypeDeclaration()) != null && !ModelUtil.isAssignableFrom(extTypeDecl, newRes)) {
                              invalidTypeParameter = true;
                           }
                        }
                        else if (typeParam instanceof TypeVariable) {
                           Type[] bounds = ((TypeVariable) typeParam).getBounds();
                           for (Type boundsType:bounds) {
                              if (boundsType != Object.class && !ModelUtil.isAssignableFrom(boundsType, newRes))
                                 invalidTypeParameter = true;
                           }
                        }
                        if (paramRes == null)
                           paramRes = newRes;
                        else
                           paramRes = ModelUtil.coerceTypes(getLayeredSystem(), paramRes, newRes);
                     }
                  }
               }
            }
         }
      }
      if (def == null || !isMethod) {
         Object enclType = ModelUtil.getEnclosingType(method);
         if (def == null || ModelUtil.isAssignableFrom(enclType, def)) {
            if (def == null)
               def = enclType;
            int srcIx = ModelUtil.getTypeParameterPosition(def, typeVarName);
            Object res;
            if (srcIx != -1 && paramTypeDecl != null && ModelUtil.isAssignableFrom(def, paramTypeDecl)) {
               res = ModelUtil.resolveTypeParameter(def, paramTypeDecl, typeVarName);
            }
            else {
               res = null;
            }
            if (inferredType != null && (res == null || ModelUtil.hasTypeVariables(res)) && ModelUtil.isAssignableFrom(inferredType, def) && inferredType != Object.class) {
               srcIx = ModelUtil.getTypeParameterPosition(inferredType, typeVarName);
               if (srcIx != -1) {
                  if (ModelUtil.sameTypes(inferredType, def)) {
                     Object newRes = ModelUtil.getTypeParameter(inferredType, srcIx);
                     if (newRes != null && newRes != Object.class && !ModelUtil.isTypeVariable(newRes) && !ModelUtil.isUnboundedWildcardType(newRes)) {
                         if (res == null || ModelUtil.isTypeVariable(res) || (ModelUtil.isAssignableFrom(res, newRes) && !ModelUtil.isAssignableFrom(newRes, res))) {
                            res = newRes;
                         }
                     }
                  }
                  else {
                     // Here we might have inferredType as a base-type to the definition of the type parameter.
                     // To get the type parameter's value, we need to call:
                  }
               }
            }
            if (resolve && res != null && ModelUtil.hasTypeVariables(res)) {
               inferredRes = ModelUtil.getTypeParameterDefault(res);
            }
            else
               inferredRes = res;
         }
      }
      if (inferredRes != null) {
         if (paramRes != null)
            return ModelUtil.blendTypes(inferredRes, paramRes);
         else
            return inferredRes;
      }
      return paramRes;
   }

   public LayeredSystem getLayeredSystem() {
      if (system != null)
         return system;
      if (definedInType instanceof ITypeDeclaration)
         return ((ITypeDeclaration) definedInType).getLayeredSystem();
      else if (paramTypeDecl != null)
         return paramTypeDecl.getLayeredSystem();
      return null;
   }

   public Layer getRefLayer() {
      return definedInType != null ? ModelUtil.getLayerForType(system, definedInType) : paramTypeDecl.getRefLayer();
   }

   public Object getDefinedInType() {
      if (definedInType == null && paramTypeDecl != null)
         return paramTypeDecl.getDefinedInType();
      return definedInType;
   }

   public void setInferredType(Object inferredType) {
      if (inferredType != this.inferredType) {
         this.inferredType = inferredType;

         resetParameterTypes();
      }
   }

   public void resetParameterTypes() {
      // Invalidate these since they might be refined once the inferred type is set
      paramTypes = null;
      boundTypes = null;
      resolvedParamJavaTypes = null;
   }

   public String toString() {
      if (method instanceof AbstractMethodDefinition) {
         return ((AbstractMethodDefinition) method).toString();
      }
      else if (method instanceof ParamTypedMethod) {
         return method.toString();
      }
      else if (method instanceof Method || method instanceof CFMethod || method instanceof Constructor) {
         Object[] args = ModelUtil.getParameterJavaTypes(method, true);
         String argsStr = args == null ? "" : StringUtil.arrayToString(args);
         return ModelUtil.getMethodName(method) + "(" + argsStr + ")";
      }
      return "<uninitialized-param-method>";
   }

   // We may retrieve a ParamTypedMethod from a base type and need to merge the ParamTypeDeclaration of the
   // context from which that method is invoked into the one we retrieved from the base type.
   public void updateParamTypes(ParamTypeDeclaration ptd) {
      if (paramTypeDecl == null)
         paramTypeDecl = ptd;
      else if (paramTypeDecl instanceof ParamTypeDeclaration) {
         ParamTypeDeclaration oldType = (ParamTypeDeclaration) paramTypeDecl;
         if (oldType.getFullTypeName().equals(ptd.getFullTypeName())) {
            paramTypeDecl = ptd;
         }
         else {
            ParamTypeDeclaration mergedPT = oldType.cloneForNewTypes();

            int i = 0;
            // For each type parameter in the method's enclosing type - see if we can find a type param in the new type context to supply the right value
            for (Object typeParam:mergedPT.typeParams) {
               Object typeParamType = ModelUtil.getTypeParameterDeclaration(typeParam);
               // Is this parameter defined by a type parameter in the type context in which the method is invoked?
               // If so, find the position of that parameter and update the type context for this method with the
               // type of that parameter.
               Object mappedResParam = ModelUtil.resolveTypeParameter(mergedPT.baseType, ptd.baseType, typeParam);
               if (mappedResParam != null && ModelUtil.isTypeVariable(mappedResParam)) {
                  int j = 0;
                  for (Object tparam:ptd.typeParams) {
                     if (ModelUtil.sameTypeParameters(mappedResParam, tparam)) {
                        mergedPT.setTypeParamIndex(i, ptd.types.get(j));
                        // In case our parameter types change now based on the new type parameters
                        resetParameterTypes();
                     }
                     j++;
                  }
               }
               i++;
            }
            paramTypeDecl = mergedPT;
         }
      }
   }

}

