/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.List;

public class ParamTypedMethod implements ITypedObject, IMethodDefinition {
   public Object method;
   ITypeParamContext paramTypeDecl;

   public ParamTypedMethod(Object meth, ITypeParamContext paramTypeDeclaration) {
      method = meth;
      paramTypeDecl = paramTypeDeclaration;
   }

   public Object getTypeDeclaration() {
      Object parameterizedType = ModelUtil.getParameterizedReturnType(method);
      if (ModelUtil.isTypeVariable(parameterizedType)) {
         int pos = ModelUtil.getTypeParameterPosition(parameterizedType);
         return paramTypeDecl.getType(pos);
      }
      return parameterizedType;
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
      return ModelUtil.getParameterTypes(method);
   }

   public JavaType[] getParameterJavaTypes() {
      if (method instanceof IMethodDefinition)
         return ((IMethodDefinition) method).getParameterJavaTypes();
      else // TODO: Not sure what to do here but suspect we don't hit this code path
         throw new UnsupportedOperationException();
   }

   public Object getTypeDeclaration(List<? extends ITypedObject> args) {
      return getTypeDeclaration();
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
}
