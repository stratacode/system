/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.db.BaseTypeDescriptor;
import sc.db.DBTypeDescriptor;
import sc.lang.sql.DBProvider;
import sc.layer.Layer;
import sc.type.CTypeUtil;
import sc.type.DynType;
import sc.layer.LayeredSystem;

import java.util.*;

/**
 * This class gets used for a weird case, when coercing one type into another to determine the resulting type of a
 * QuestionMarkOperator (i.e. where the resulting type is the overlapping type formed by each value),
 * there are times we have to create essentially a new type that collects just the overlapping interfaces between the two types we are
 * coercing into one.
 * */
public class WrappedTypeDeclaration implements ITypeDeclaration {
   Object baseType;
   LayeredSystem system;

   public WrappedTypeDeclaration(LayeredSystem sys, Object it) {
      this.system = sys;
      baseType = it;
   }

   public Object getDerivedTypeDeclaration() {
      return ModelUtil.getSuperclass(baseType);
   }

   public Object getExtendsTypeDeclaration() {
      return getDerivedTypeDeclaration();
   }

   public Object getExtendsType() {
      return ModelUtil.getExtendsJavaType(baseType);
   }

   public List<?> getImplementsTypes() {
      Object[] res = ModelUtil.getImplementsJavaTypes(baseType);
      return res == null ? null : Arrays.asList(res);
   }

   public boolean isAssignableFrom(ITypeDeclaration other, boolean assignmentSemantics) {
      if (ModelUtil.isAssignableFrom(baseType, other, assignmentSemantics, null))
           return true;
      return false;
   }

   public boolean isAssignableTo(ITypeDeclaration other) {
      if (other == this || other == baseType || ModelUtil.sameTypes(other, baseType) || ModelUtil.isAssignableFrom(other, baseType))
         return true;
      return false;
   }

   public String getTypeName() {
      return CTypeUtil.getClassName(getFullTypeName());
   }

   public String getFullTypeName() {
      return getFullTypeName(true, false);
   }

   @Override
   public String getJavaFullTypeName() {
      return ModelUtil.getJavaFullTypeName(baseType);
   }

   public String getFullTypeName(boolean includeDims, boolean includeTypeParams) {
      return ModelUtil.getTypeName(baseType);
   }

   public String getFullBaseTypeName() {
      return getFullTypeName(false, false);
   }

   public String getInnerTypeName() {
      return ModelUtil.getInnerTypeName(baseType);
   }

   public Class getCompiledClass() {
      return ModelUtil.typeToClass(baseType);
   }

   public String getCompiledClassName() {
      return ModelUtil.getCompiledClassName(baseType);
   }

   public String getCompiledTypeName() {
      String baseName = ModelUtil.getCompiledTypeName(baseType);
      return baseName;
   }

   public Object getRuntimeType() {
      if (ModelUtil.isDynamicType(baseType))
         return this;
      return getCompiledClass();
   }

   public boolean isDynamicType() {
      return ModelUtil.isDynamicType(baseType);
   }

   public boolean isDynamicStub(boolean includeExtends) {
      return ModelUtil.isDynamicStub(baseType, includeExtends);
   }

   public Object definesMethod(String name, List<? extends Object> parametersOrExpressions, ITypeParamContext ctx, Object refType, boolean isTransformed, boolean staticOnly, Object inferredType, List<JavaType> methodTypeArgs) {
      return ModelUtil.definesMethod(baseType, name, parametersOrExpressions, ctx, refType, isTransformed, staticOnly, inferredType, methodTypeArgs, getLayeredSystem());
   }

   public Object declaresConstructor(List<?> parametersOrExpressions, ITypeParamContext ctx) {
      return ModelUtil.declaresConstructor(system, baseType, parametersOrExpressions, ctx);
   }

   public Object definesConstructor(List<?> parametersOrExpressions, ITypeParamContext ctx, boolean isTransformed) {
      return ModelUtil.definesConstructor(system, baseType, parametersOrExpressions, ctx, null, isTransformed);
   }

   public Object definesMember(String name, EnumSet<JavaSemanticNode.MemberType> types, Object refType, TypeContext ctx) {
      return definesMember(name, types, refType, ctx, false, false);
   }

   public Object definesMember(String name, EnumSet<JavaSemanticNode.MemberType> types, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      return ModelUtil.definesMember(baseType, name, types, refType, ctx, skipIfaces, isTransformed, getLayeredSystem());
   }

   public Object getInnerType(String name, TypeContext ctx) {
      return ModelUtil.getInnerType(baseType, name, ctx);
   }

   public boolean implementsType(String otherTypeName, boolean assignment, boolean allowUnbound) {
      boolean isTypeVar = ModelUtil.isTypeVariable(baseType);
      if (allowUnbound && isTypeVar)
         return true;
      Object typeToUse = baseType;
      while (isTypeVar) {
         typeToUse = ModelUtil.getTypeParameterDefault(typeToUse);
         isTypeVar = typeToUse != null && ModelUtil.isTypeVariable(typeToUse);
      }
      if (typeToUse == null)
         typeToUse = Object.class;
      // TODO: should we verify that our parameters match if the other type has assigned params too?
      return ModelUtil.implementsType(typeToUse, otherTypeName, assignment, allowUnbound);
   }

   public Object getInheritedAnnotation(String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve) {
      return ModelUtil.getInheritedAnnotation(null, baseType, annotationName, skipCompiled, refLayer, layerResolve);
   }

   public ArrayList<Object> getAllInheritedAnnotations(String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve) {
      return ModelUtil.getAllInheritedAnnotations(null, baseType, annotationName, skipCompiled, refLayer, layerResolve);
   }

   public boolean isAssignableFromClass(Class c) {
      return ModelUtil.isAssignableFrom(baseType, c);
   }

   public List<Object> getAllMethods(String modifier, boolean hasModifier, boolean isDyn, boolean overridesComp) {
      Object[] res = ModelUtil.getAllMethods(baseType, modifier, hasModifier, isDyn, overridesComp);
      if (res == null)
         return null;
      return Arrays.asList(res);
   }

   public List<Object> getMethods(String methodName, String modifier, boolean includeExtends) {
      if (baseType == null)
         return null;
      Object type = baseType;
      if (ModelUtil.isTypeVariable(type))
         type = ModelUtil.getTypeParameterDefault(type);
      Object[] baseMethods = ModelUtil.getMethods(type, methodName, modifier, includeExtends);
      if (baseMethods == null)
         return null;
      return Arrays.asList(baseMethods);
   }

   @Override
   public Object getConstructorFromSignature(String sig, boolean includeHidden) {
      return ModelUtil.getConstructorFromSignature(baseType, sig, includeHidden);
   }

   @Override
   public Object getMethodFromSignature(String methodName, String signature, boolean resolveLayer) {
      return ModelUtil.getMethodFromSignature(baseType, methodName, signature, resolveLayer);
   }

   public List<Object> getAllProperties(String modifier, boolean includeAssigns) {
      Object[] baseProps = ModelUtil.getProperties(baseType, modifier, includeAssigns);
      if (baseProps == null)
         return null;
      return Arrays.asList(baseProps);
   }

   public List<Object> getDeclaredProperties(String modifier, boolean includeAssigns, boolean includeModified, boolean editorProperties) {
      return getAllProperties(modifier, includeAssigns);
   }

   public List<Object> getAllFields(String modifier, boolean hasModifier, boolean dynamicOnly, boolean includeObjs, boolean includeAssigns, boolean includeModified) {
      Object[] baseFields = ModelUtil.getFields(baseType, modifier, hasModifier, dynamicOnly, includeObjs, includeAssigns, includeModified);
      if (baseFields == null)
         return null;
      return Arrays.asList(baseFields);
   }

   public List<Object> getAllInnerTypes(String modifier, boolean thisClassOnly, boolean includeInherited) {
      Object[] res = ModelUtil.getAllInnerTypes(baseType, modifier, thisClassOnly, includeInherited);
      if (res == null)
         return Collections.emptyList();
      return Arrays.asList(res);
   }

   public DeclarationType getDeclarationType() {
      return ModelUtil.getDeclarationType(baseType);
   }

   public Object getClass(String className, boolean useImports, boolean compiledOnly) {
      throw new UnsupportedOperationException();
   }

   public Object findTypeDeclaration(String typeName, boolean addExternalReference) {
      throw new UnsupportedOperationException();
   }

   public JavaModel getJavaModel() {
      throw new UnsupportedOperationException();
   }

   public Layer getLayer() {
      return ModelUtil.getLayerForType(null, baseType);
   }

   public LayeredSystem getLayeredSystem() {
      return system;
   }

   public List<?> getClassTypeParameters() {
      return null;
   }

   public Object[] getConstructors(Object refType, boolean includeHidden) {
      return ModelUtil.getConstructors(baseType, refType, includeHidden);
   }

   public boolean isComponentType() {
      return ModelUtil.isComponentType(baseType);
   }

   public DynType getPropertyCache() {
      return ModelUtil.getPropertyCache(baseType);
   }

   public boolean isEnumeratedType() {
      return false;
   }

   public Object getEnumConstant(String nextName) {
      return null;
   }

   public boolean isCompiledProperty(String name, boolean fieldMode, boolean interfaceMode) {
      return ModelUtil.isCompiledProperty(baseType, name, fieldMode, interfaceMode);
   }

   public Object getAnnotation(String annotName) {
      return ModelUtil.getAnnotation(baseType, annotName);
   }

   public boolean hasModifier(String modifierName) {
      return ModelUtil.hasModifier(baseType, modifierName);
   }

   public AccessLevel getAccessLevel(boolean explicitOnly) {
      return ModelUtil.getAccessLevel(baseType, explicitOnly);
   }

   public Object getEnclosingIType() {
      return ModelUtil.getEnclosingType(baseType);
   }

   public String modifiersToString(boolean includeAnnotations, boolean includeAccess, boolean includeFinal, boolean includeScopes, boolean abs, JavaSemanticNode.MemberType filterType) {
      return ModelUtil.modifiersToString(baseType, includeAnnotations, includeAccess, includeFinal, includeScopes, abs, filterType);
   }

   public boolean equals(Object obj) {
      return ModelUtil.getTypeName(baseType).equals(ModelUtil.getTypeName(obj));
   }

   public Object getBaseType() {
      return baseType;
   }


   public List<JavaType> getCompiledTypeArgs(List<JavaType> typeArgs) {
      if (baseType != null)
         return ModelUtil.getCompiledTypeArgs(baseType, typeArgs);
      return null;
   }

   public boolean needsOwnClass(boolean checkComponents) {
      if (baseType == null)
         return true;
      return ModelUtil.needsOwnClass(baseType, checkComponents);
   }

   public boolean isDynamicNew() {
      if (baseType == null)
         return false;
      return ModelUtil.isDynamicNew(baseType);
   }

   public void initDynStatements(Object inst, ExecutionContext ctx, TypeDeclaration.InitStatementMode mode, boolean initExt) {
      if (!(baseType instanceof ITypeDeclaration))
         return;
      ((ITypeDeclaration) baseType).initDynStatements(inst, ctx, mode, initExt);
   }

   public void clearDynFields(Object inst, ExecutionContext ctx) {
      if (!(baseType instanceof ITypeDeclaration))
         return;
      ((ITypeDeclaration) baseType).clearDynFields(inst, ctx);
   }

   public Object[] getImplementsTypeDeclarations() {
      return ModelUtil.getImplementsTypeDeclarations(baseType);
   }

   public Object[] getAllImplementsTypeDeclarations() {
      return ModelUtil.getAllImplementsTypeDeclarations(baseType);
   }

   public boolean isRealType() {
      return true;
   }

   public void staticInit() {
      if (baseType != null)
         ModelUtil.initType(baseType);
   }

   public boolean isTransformedType() {
      return ModelUtil.isTransformedType(baseType);
   }

   public Object getArrayComponentType() {
      if (baseType != null)
         return ModelUtil.getArrayComponentType(baseType);
      return null;
   }

   @Override
   public ITypeDeclaration resolve(boolean modified) {
      if (baseType instanceof ITypeDeclaration) {
         Object newType = ((ITypeDeclaration) baseType).resolve(modified);
         if (newType != null)
            baseType = newType;
      }
      return this;
   }

   @Override
   public boolean useDefaultModifier() {
      return baseType instanceof ITypeDeclaration ? ((ITypeDeclaration) baseType).useDefaultModifier() : false;
   }

   public void setAccessTimeForRefs(long time) {
      if (baseType instanceof ITypeDeclaration)
         ((ITypeDeclaration) baseType).setAccessTimeForRefs(time);
   }

   public void setAccessTime(long time) {
      if (baseType instanceof ITypeDeclaration)
         ((ITypeDeclaration) baseType).setAccessTime(time);
   }

   public BaseTypeDescriptor getDBTypeDescriptor() {
      return DBProvider.getDBTypeDescriptor(getLayeredSystem(), getLayer(), baseType, false);
   }

   public boolean isLayerType() {
      return false;
   }

   public boolean isLayerComponent() {
      return false;
   }
}
