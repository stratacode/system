/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.layer.LayeredSystem;

import java.util.List;
import java.util.Map;

public abstract class WrapperMethod implements IMethodDefinition {
   Object wrapped;
   LayeredSystem system;
   JavaModel model;

   public String getMethodName() {
      return ModelUtil.getMethodName(wrapped);
   }

   public Object getDeclaringType() {
      return ModelUtil.getEnclosingType(wrapped);
   }

   public Object getReturnType(boolean resolve) {
      return ModelUtil.getReturnType(wrapped, resolve);
   }

   public Object getReturnJavaType() {
      return ModelUtil.getReturnJavaType(wrapped);
   }

   public Object[] getParameterTypes(boolean bound) {
      return ModelUtil.getParameterTypes(wrapped, bound);
   }

   public JavaType[] getParameterJavaTypes(boolean convertRepeating) {
      return (JavaType[]) ModelUtil.getParameterJavaTypes(wrapped, convertRepeating);
   }

   public Object getTypeDeclaration(List<? extends ITypedObject> args, boolean resolve) {
      return ModelUtil.getMethodTypeDeclaration(null, wrapped, (List<Expression>) args, system, model, null, null);
   }

   public String getPropertyName() {
      return ModelUtil.getPropertyName(wrapped);
   }

   public boolean hasField() {
      return ModelUtil.hasField(wrapped);
   }

   public boolean hasGetMethod() {
      return ModelUtil.hasGetMethod(wrapped);
   }

   public boolean hasSetMethod() {
      return ModelUtil.hasSetMethod(wrapped);
   }

   public boolean hasSetIndexMethod() {
      return ModelUtil.hasSetIndexMethod(wrapped);
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

   public String getParameterString() {
      return ModelUtil.getParameterString(wrapped);
   }

   public Object[] getExceptionTypes() {
      return ModelUtil.getExceptionTypes(wrapped);
   }

   public String getThrowsClause() {
      return ModelUtil.getThrowsClause(wrapped);
   }

   public Object getAnnotation(String annotName, boolean checkModified) {
      return ModelUtil.getAnnotation(wrapped, annotName);
   }

   public List<Object> getRepeatingAnnotation(String annotName) {
      return ModelUtil.getRepeatingAnnotation(wrapped, annotName);
   }

   public Map<String,Object> getAnnotations() {
      return ModelUtil.getAnnotations(wrapped);
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

   public Object getFieldFromGetSetMethod() {
      return ModelUtil.getFieldFromGetSetMethod(wrapped);
   }

   public Object[] getMethodTypeParameters() {
      return ModelUtil.getMethodTypeParameters(wrapped);
   }

   public boolean isConstructor() {
      return ModelUtil.isConstructor(wrapped);
   }

   public int getNumParameters() {
      return ModelUtil.getNumParameters(wrapped);
   }

}
