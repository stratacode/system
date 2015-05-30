/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.util.StringUtil;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class ParamTypedMethod implements ITypedObject, IMethodDefinition, ITypeParamContext {
   public Object method;
   ITypeParamContext paramTypeDecl;
   Object[] methodTypeParams;

   // Stores the target type of the method expression.  Since Java8 this is an alternate source
   // for type parameter values used in resolving parameter types of the method invocation.
   Object inferredType;

   // Cached the first time used
   JavaType[] paramJavaTypes;
   JavaType[] resolvedParamJavaTypes;
   JavaType[] boundJavaTypes;
   Object[] paramTypes;
   Object[] boundTypes;

   // Set to true when this type is derived from parameter types which are not defined until the inferredType is set
   // It allows us to match against this parameter even if a type variable is not assigned.
   boolean unboundInferredType = false;

   // This is the type declaration which defines the use of this param typed method
   ITypeDeclaration definedInType;

   public ParamTypedMethod(Object meth, ITypeParamContext paramTypeDeclaration, ITypeDeclaration definedInType, List<? extends Object> parametersOrExpressions) {
      if (meth == null)
         System.out.println("*** Warning null method for method typed parameter");

      method = meth;
      paramTypeDecl = paramTypeDeclaration;
      methodTypeParams = ModelUtil.getMethodTypeParameters(method);
      this.definedInType = definedInType;
      if (parametersOrExpressions != null)
         boundJavaTypes = ModelUtil.parametersToJavaTypeArray(this, parametersOrExpressions, this);
      paramTypes = null; // Need to recompute this once we've set the boundJavaTypes
      resolvedParamJavaTypes = null;
   }

   public Object getTypeDeclaration() {
      return getTypeDeclaration(null, true);
   }

   public Object getTypeDeclaration(List<? extends ITypedObject> args, boolean resolve) {
      Object parameterizedType = ModelUtil.getParameterizedReturnType(method, args, resolve);
      if (ModelUtil.hasTypeVariables(parameterizedType)) {
         return resolveTypeParameter(parameterizedType, resolve);
      }
      if (ModelUtil.isParameterizedType(parameterizedType)) {
         // Parameterized type here is a ParamTypeDeclaration with types that are unbound type parameters.
         // Need to look those up in methodTypeParams.  If we find one, need to clone the ParamTypeDeclaration - binding
         // the parameter type value with it's type.
         if (parameterizedType instanceof ParamTypeDeclaration) {
            ParamTypeDeclaration paramType = (ParamTypeDeclaration) parameterizedType;
            ParamTypeDeclaration newType = null;
            int i = 0;
            for (Object type:paramType.types) {
               if (ModelUtil.hasTypeVariables(type)) {
                  Object newVal = resolveTypeParameter(type, resolve);
                  if (newVal != type) {
                     if (newType == null) {
                        newType = paramType.cloneForNewTypes();
                     }
                     newType.types.set(i, ModelUtil.wrapPrimitiveType(newVal));
                  }
               }
               else if (ModelUtil.isParameterizedType(type)) {
                  //System.err.println("*** Not resolved nested type parameters");
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
      }
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
      else
         throw new UnsupportedOperationException();
   }

   private Object resolveMethodTypeParameter(String typeVarName, Object typeVar) {
      // For method type parameters, do we need to derive the type of each parameter from types we were able to resolve
      // from the parameters?
      // TODO: We may be in the midst of creating the parameters and so can't use these values yet - since they are not defined
      // TODO: should we use getResolvedParameterJavaTypes here?
      JavaType[] paramJavaTypes = getParameterJavaTypes();
      int i = 0;
      if (paramJavaTypes != null) {
         for (JavaType paramJavaType : paramJavaTypes) {
            if (paramJavaType != null) {
               Object type = paramJavaType.definesTypeParameter(typeVar, paramTypeDecl);
               if (type != null) {
                  // There are times when we need to bind the type parameter to the type of the argument passed to the method.
                  if (boundJavaTypes != null && i < boundJavaTypes.length) {
                     JavaType boundJavaType = boundJavaTypes[i];
                     if (boundJavaType != null) {
                        Object res = boundJavaType.getTypeDeclaration(); // pass in type context here?
                        // An array type in the last slot of a method defined like Arrays.toArray(T... value) really means
                        // that T is assigned to the component type, not the array type.
                        if (i == boundJavaTypes.length - 1 && ModelUtil.isVarArgs(method) && ModelUtil.isArray(res))
                           return ModelUtil.getArrayComponentType(res);
                        return res;
                     }
                  }
                  return type;
               }
               if (boundJavaTypes != null && i < boundJavaTypes.length) {
                  JavaType boundJavaType = boundJavaTypes[i];
                  if (boundJavaType != null) {
                     // Do we have an actual bound value for this type parameter - if so, return it without checking the inferredType.
                     // If this is any type of unbound type parameter expression - e.g. ? super T we need to check for T in the inferredtype
                     // and so should not return.
                     type = extractTypeParameter(paramJavaType, paramJavaType.getTypeDeclaration(), boundJavaType.getTypeDeclaration(), typeVar);
                     if (type != null)
                        return type;
                  }
               }
            }
            i++;
         }
      }
      if (inferredType instanceof ParamTypeDeclaration) {
         ParamTypeDeclaration inferredParamType = (ParamTypeDeclaration) inferredType;
         Object methReturnJavaType = ModelUtil.getReturnJavaType(method);
         Object methReturnType = ModelUtil.getReturnType(method);
         if (methReturnJavaType != null) {
            Object extRes = extractTypeParameter(methReturnJavaType, methReturnType, inferredParamType, typeVar);
            if (extRes != null)
               return extRes;
         }
         //Object res = inferredParamType.getTypeForVariable(typeVar, false);
         //if (res != null && !ModelUtil.isTypeVariable(res))
         //   return res;
      }
      return null;
   }

   /** This method is called when we are trying to resolve a specific method type paraemter - typeVar.  We are given
    * the paramJavaType - the declaration of the parameter.  The paramType - that type's current type, and the bound
    * type for that parameter.   We walk the type hierarchy of paramJavaType to see if we can find "typeVar" and if
    * that type is assigned a value.
    */
   public Object extractTypeParameter(Object paramJavaType, Object paramType, Object boundType, Object typeVar) {
      if (ModelUtil.isTypeVariable(paramType)) {
         if (paramJavaType instanceof ClassType) {
            ClassType ct = (ClassType) paramJavaType;
            if (!ModelUtil.isTypeVariable(ct.type) || !ModelUtil.sameTypeParameters(ct.type, typeVar))
               return null;
         }
         else {
            // Is this an extends type of bounded type - if so and it matches this parameter we'll use the bound type
            if (paramJavaType instanceof JavaType) {
               return null;
            }
            else if (!ModelUtil.sameTypeParameters(paramJavaType, typeVar))
               return null;
         }
         if (ModelUtil.isTypeVariable(boundType) || ModelUtil.isUnboundSuper(boundType))
            return null;
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
      if (ModelUtil.isTypeVariable(boundType))
         return null;
      if (!ModelUtil.isAssignableFrom(paramType, boundType, false, null, true))
         return null;

      int numParams = ModelUtil.getNumTypeParameters(paramType);
      int boundParams = ModelUtil.getNumTypeParameters(boundType);
      if (numParams == boundParams) {
         for (int i = 0; i < numParams; i++) {
            Object typeParamArg = ModelUtil.getTypeArgument(paramJavaType, i);
            Object typeParam = ModelUtil.getTypeDeclFromType(null, typeParamArg, false, getLayeredSystem(), false, getDefinedInType());
            Object boundParam = ModelUtil.getTypeParameter(boundType, i);
            Object nestedRes = extractTypeParameter(typeParamArg, typeParam, boundParam, typeVar);
            if (nestedRes != null)
               return nestedRes;
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

   public Object getReturnType() {
      return ModelUtil.getReturnType(method);
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
      JavaType[] javaTypes = bound ? getResolvedParameterJavaTypes() : getParameterJavaTypes();
      if (javaTypes == null)
         return null;
      int len = javaTypes.length;
      Object[] res = new Object[len];
      for (int i =  0; i < len; i++) {
         JavaType paramJavaType = javaTypes[i];
         res[i] = paramJavaType.getTypeDeclaration(bound ? this : null, false);
         if (res[i] instanceof ClassType) {
            res[i] = javaTypes[i].getTypeDeclaration();
         }
         if (res[i] == null)
            System.out.println("*** Warning - null value for parameter type");
      }
      if (bound)
         boundTypes = res;
      else
         paramTypes = res;
      return res;
   }

   public JavaType[] getResolvedParameterJavaTypes() {
      if (resolvedParamJavaTypes != null)
         return resolvedParamJavaTypes;
      if (method instanceof IMethodDefinition) {
         JavaType[] paramTypes = getParameterJavaTypes();
         if (paramTypes == null)
            return null;
         int len = paramTypes.length;
         JavaType[] result = null;
         for (int i = 0; i < len; i++) {
            JavaType paramType = paramTypes[i];
            if (paramType.isParameterizedType()) {
               JavaType resolvedType = paramType.resolveTypeParameters(this);
               if (resolvedType != paramType) {
                  if (result == null) {
                     result = new JavaType[len];
                     System.arraycopy(paramTypes, 0, result, 0, len);
                  }
                  result[i] = resolvedType;
               }
            }
         }
         resolvedParamJavaTypes = result != null ? result : paramTypes;
         return resolvedParamJavaTypes;
      }
      else if (method instanceof Method) {
         java.lang.reflect.Type[] paramTypes = ((Method) method).getGenericParameterTypes();
         int len = paramTypes.length;
         if (len == 0)
            return null;
         JavaType[] res = new JavaType[len];
         for (int i = 0; i < len; i++) {
            Object paramType = paramTypes[i];
            if (paramType instanceof ExtendsType.LowerBoundsTypeDeclaration)
               System.out.println("*** Unknown lower bounds type (6)");
            if (ModelUtil.hasTypeVariables(paramType)) {
               paramType = resolveTypeParameter(paramType, false);
            }
            JavaType javaType = JavaType.createFromParamType(paramType, this, null);
            if (javaType.isParameterizedType()) {
               JavaType newVal = javaType.resolveTypeParameters(this);
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
         resolvedParamJavaTypes = res;
         return resolvedParamJavaTypes;
      }
      throw new UnsupportedOperationException();
   }

   public JavaType[] getParameterJavaTypes() {
      if (paramJavaTypes != null)
         return paramJavaTypes;
      if (method instanceof IMethodDefinition) {
         JavaType[] paramTypes = ((IMethodDefinition) method).getParameterJavaTypes();
         if (paramTypes == null)
            return null;
         JavaType[] result = null;
         paramJavaTypes = result != null ? result : paramTypes;
         return paramJavaTypes;
      }
      else if (method instanceof Method) {
         java.lang.reflect.Type[] paramTypes = ((Method) method).getGenericParameterTypes();
         int len = paramTypes.length;
         if (len == 0)
            return null;
         JavaType[] res = paramJavaTypes = new JavaType[len];
         for (int i = 0; i < len; i++) {
            Object paramType = paramTypes[i];
            JavaType javaType = JavaType.createFromParamType(paramType, this, null);
            res[i] = javaType;
         }
         return res;
      }
      else
         throw new UnsupportedOperationException();
   }

   public String getPropertyName() {
      return ModelUtil.getPropertyName(method);
   }

   public boolean hasGetMethod() {
      return ModelUtil.hasGetMethod(method);
   }

   public boolean hasSetMethod() {
      return ModelUtil.hasSetMethod(method);
   }

   public Object getGetMethodFromSet() {
      return ModelUtil.getGetMethodFromSet(method);
   }

   public Object getSetMethodFromGet() {
      return ModelUtil.getSetMethodFromGet(method);
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

   public String getTypeSignature() {
      // Assuming we do not have to do anything here since we want the type sig of the method in the class format.
      return ModelUtil.getTypeSignature(method);
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

   public Object getAnnotation(String annotName) {
      return ModelUtil.getAnnotation(method, annotName);
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
      boolean isMethod = def != null && ModelUtil.isMethod(def);
      if (def == null || isMethod) {
         if (methodTypeParams != null) {
            for (Object typeParam : methodTypeParams) {
               if (ModelUtil.getTypeParameterName(typeParam).equals(typeVarName)) {
                  Object res = resolveMethodTypeParameter(typeVarName, typeParam);
                  if (res == null) {
                     if (typeParam instanceof JavaType)
                        return ((JavaType) typeParam).getTypeDeclaration(paramTypeDecl, false); // Is it right to pass paramTypeDecl here?
                     return typeParam; // An unresolved type parameter - return the type param since it at least matched.
                  }
                  if (res instanceof JavaType) {
                     return ((JavaType) res).getTypeDeclaration();
                  }
                  return res;
               }
            }
         }
      }
      if (def == null || !isMethod) {
         Object enclType = ModelUtil.getEnclosingType(method);
         if (def == null || ModelUtil.isAssignableFrom(enclType, def)) {
            int srcIx = ModelUtil.getTypeParameterPosition(enclType, typeVarName);
            Object res;
            if (srcIx != -1 && paramTypeDecl != null) {
               res = ModelUtil.resolveTypeParameter(enclType, paramTypeDecl, typeVarName);
            }
            else {
               res = null;
            }
            if (resolve && res != null && ModelUtil.hasTypeVariables(res)) {
               return ModelUtil.getTypeParameterDefault(res);
            }
            return res;
         }
      }
      return null;
   }

   public LayeredSystem getLayeredSystem() {
      if (definedInType != null)
         return definedInType.getLayeredSystem();
      else if (paramTypeDecl != null)
         return paramTypeDecl.getLayeredSystem();
      return null;
   }

   public Layer getRefLayer() {
      return definedInType != null ? definedInType.getLayer() : paramTypeDecl.getRefLayer();
   }

   public ITypeDeclaration getDefinedInType() {
      if (definedInType == null && paramTypeDecl != null)
         return paramTypeDecl.getDefinedInType();
      return definedInType;
   }

   public void setInferredType(Object inferredType) {
      this.inferredType = inferredType;
      paramTypes = null;
   }

   public String toString() {
      if (method instanceof AbstractMethodDefinition) {
         return ((AbstractMethodDefinition) method).toString();
      }
      else if (method instanceof ParamTypedMethod) {
         return method.toString();
      }
      else if (method instanceof Method) {
         Object[] args = ModelUtil.getParameterJavaTypes(method);
         String argsStr = args == null ? "" : StringUtil.arrayToString(args);
         return ModelUtil.getMethodName(method) + "(" + argsStr + ")";
      }
      throw new UnsupportedOperationException();
   }
}
