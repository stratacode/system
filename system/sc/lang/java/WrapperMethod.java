/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.List;

public abstract class WrapperMethod implements IMethodDefinition {
   Object wrapped;
   JavaModel javaModel;

   public String getMethodName() {
      return ModelUtil.getMethodName(wrapped);
   }

   public Object getDeclaringType() {
      return ModelUtil.getEnclosingType(wrapped);
   }

   public Object getReturnType() {
      return ModelUtil.getReturnType(wrapped);
   }

   public Object getReturnJavaType() {
      return ModelUtil.getReturnJavaType(wrapped);
   }

   public Object[] getParameterTypes() {
      return ModelUtil.getParameterTypes(wrapped);
   }

   public JavaType[] getParameterJavaTypes() {
      return (JavaType[]) ModelUtil.getParameterJavaTypes(wrapped);
   }

   public Object getTypeDeclaration(List<? extends ITypedObject> args, boolean resolve) {
      return ModelUtil.getMethodTypeDeclaration(null, wrapped, (List<Expression>) args, javaModel, null);
   }

   public String getPropertyName() {
      return ModelUtil.getPropertyName(wrapped);
   }

   public boolean hasGetMethod() {
      return ModelUtil.hasGetMethod(wrapped);
   }

   public boolean hasSetMethod() {
      return ModelUtil.hasSetMethod(wrapped);
   }

   public boolean isGetMethod() {
      return ModelUtil.isGetMethod(wrapped);
   }

   public boolean isSetMethod() {
      return ModelUtil.isSetMethod(wrapped);
   }

   public boolean isGetIndexMethod() {
      return ModelUtil.isGetIndexMethod(wrapped);
   }

   public boolean isSetIndexMethod() {
      return ModelUtil.isSetIndexMethod(wrapped);
   }

   public boolean isVarArgs() {
      return ModelUtil.isVarArgs(wrapped);
   }

   public String getTypeSignature() {
      return ModelUtil.getTypeSignature(wrapped);
   }

   public Object[] getExceptionTypes() {
      return ModelUtil.getExceptionTypes(wrapped);
   }

   public String getThrowsClause() {
      return ModelUtil.getThrowsClause(wrapped);
   }

   public Object getAnnotation(String annotName) {
      return ModelUtil.getAnnotation(wrapped, annotName);
   }

   public boolean hasModifier(String modifierName) {
      return ModelUtil.hasModifier(wrapped, modifierName);
   }

   public AccessLevel getAccessLevel(boolean explicitOnly) {
      return ModelUtil.getAccessLevel(wrapped, explicitOnly);
   }

   public Object getEnclosingIType() {
      return ModelUtil.getEnclosingType(wrapped);
   }

   public String modifiersToString(boolean includeAnnotations, boolean includeAccess, boolean includeFinal, boolean includeScopes, boolean abs, JavaSemanticNode.MemberType filterType) {
      return ModelUtil.modifiersToString(wrapped, includeAnnotations, includeAccess, includeFinal, includeScopes, abs, filterType);
   }

   public Object getGetMethodFromSet() {
      return ModelUtil.getGetMethodFromSet(wrapped);
   }

   public Object getSetMethodFromGet() {
      return ModelUtil.getSetMethodFromGet(wrapped);
   }

   public Object[] getMethodTypeParameters() {
      return ModelUtil.getMethodTypeParameters(wrapped);
   }

}
