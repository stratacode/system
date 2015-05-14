/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.layer.Layer;
import sc.layer.LayeredSystem;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class ParamTypedMethod implements ITypedObject, IMethodDefinition, ITypeParamContext {
   public Object method;
   ITypeParamContext paramTypeDecl;
   Object[] methodTypeParams;

   // Cached the first time used
   JavaType[] paramJavaTypes;
   Object[] paramTypes;

   // This is the type declaration which defines the use of this param typed method
   ITypeDeclaration definedInType;

   public ParamTypedMethod(Object meth, ITypeParamContext paramTypeDeclaration, ITypeDeclaration definedInType) {
      if (meth == null)
         System.out.println("*** Warning null method for method typed parameter");
      method = meth;
      paramTypeDecl = paramTypeDeclaration;
      methodTypeParams = ModelUtil.getMethodTypeParameters(method);
      this.definedInType = definedInType;
   }

   public Object getTypeDeclaration() {
      return getTypeDeclaration(null, true);
   }

   public Object getTypeDeclaration(List<? extends ITypedObject> args, boolean resolve) {
      Object parameterizedType = ModelUtil.getParameterizedReturnType(method, args, resolve);
      if (ModelUtil.isTypeVariable(parameterizedType)) {
         return resolveTypeParameter(parameterizedType, resolve);
      }
      return parameterizedType;
   }

   private Object resolveTypeParameter(Object typeVariable, boolean resolve) {
      String typeVarName = ModelUtil.getTypeParameterName(typeVariable);
      return getTypeDeclarationForParam(typeVarName, typeVariable, resolve);
   }

   private Object resolveMethodTypeParameter(String typeVarName) {
      JavaType[] paramJavaTypes = getParameterJavaTypes();
      if (paramJavaTypes == null)
         return null;
      // For method type parameters, we need to derive the type of each parameter from types we were able to resolve
      // from the parameters.
      for (JavaType paramJavaType:paramJavaTypes) {
         // TODO: We may be in the midst of creating the parameters and so can't use these values yet - since they are not defined. Remove this?
         if (paramJavaType != null) {
            Object type = paramJavaType.definesTypeParameter(typeVarName, paramTypeDecl);
            if (type != null)
               return type;
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

   public Object[] getParameterTypes() {
      if (paramTypes != null)
         return paramTypes;
      JavaType[] javaTypes = getParameterJavaTypes();
      if (javaTypes == null)
         return null;
      int len = javaTypes.length;
      Object[] res = new Object[len];
      for (int i =  0; i < len; i++) {
         res[i] = javaTypes[i].getTypeDeclaration();
         if (res[i] instanceof ClassType) {
            res[i] = javaTypes[i].getTypeDeclaration();
         }
         if (res[i] == null)
            System.out.println("*** Warning - null value for parameter type");
      }
      paramTypes = res;
      return res;
   }

   public JavaType[] getParameterJavaTypes() {
      if (paramJavaTypes != null)
         return paramJavaTypes;
      if (method instanceof IMethodDefinition) {
         JavaType[] paramTypes = ((IMethodDefinition) method).getParameterJavaTypes();
         if (paramTypes == null)
            return null;
         JavaType[] result = null;
         int len = paramTypes.length;
         for (int i = 0; i < len; i++) {
            JavaType paramType = paramTypes[i];
            if (paramType.isParameterizedType()) {
               JavaType resolvedType = paramType.resolveTypeParameters(this);
               if (resolvedType != paramType) {
                  if (result == null) {
                     paramJavaTypes = result = new JavaType[len];
                     System.arraycopy(paramTypes, 0, result, 0, len);
                  }
                  result[i] = resolvedType;
               }
            }
         }
         return result != null ? result : paramTypes;
      }
      else if (method instanceof Method) {
         java.lang.reflect.Type[] paramTypes = ((Method) method).getGenericParameterTypes();
         int len = paramTypes.length;
         if (len == 0)
            return null;
         JavaType[] res = paramJavaTypes = new JavaType[len];
         for (int i = 0; i < len; i++) {
            Object paramType = paramTypes[i];
            if (ModelUtil.isTypeVariable(paramType)) {
               paramType = resolveTypeParameter(paramType, false);
            }
            JavaType javaType = JavaType.createFromParamType(paramType, this);
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

   public Object getTypeForVariable(Object typeVar) {
      // Always returning the type - not a type parameter
      return resolveTypeParameter(typeVar, true);
   }

   public Object getTypeDeclarationForParam(String typeVarName, Object typeVar, boolean resolve) {
      Object def = typeVar != null ? ModelUtil.getTypeParameterDeclaration(typeVar) : null;
      boolean isMethod = def != null && ModelUtil.isMethod(def);
      if (def == null || isMethod) {
         if (methodTypeParams != null) {
            for (Object typeParam : methodTypeParams) {
               if (ModelUtil.getTypeParameterName(typeParam).equals(typeVarName)) {
                  return resolveMethodTypeParameter(typeVarName);
               }
            }
         }
      }
      if (def == null || !isMethod) {
         Object enclType = ModelUtil.getEnclosingType(method);
         if (def == null || ModelUtil.isAssignableFrom(enclType, def)) {
            Object res = ModelUtil.resolveTypeParameter(enclType, paramTypeDecl, typeVarName);
            if (resolve && ModelUtil.isTypeVariable(res)) {
               return ModelUtil.getTypeParameterDefault(res);
            }
            return res;
         }
      }
      return null;
   }

   public LayeredSystem getLayeredSystem() {
      return paramTypeDecl.getLayeredSystem();
   }

   public Layer getRefLayer() {
      return definedInType != null ? definedInType.getLayer() : paramTypeDecl.getRefLayer();
   }

   public ITypeDeclaration getDefinedInType() {
      return definedInType;
   }
}
