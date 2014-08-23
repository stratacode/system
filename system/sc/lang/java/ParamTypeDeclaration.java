/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.type.CTypeUtil;
import sc.type.DynType;
import sc.layer.LayeredSystem;

import java.util.*;

public class ParamTypeDeclaration implements ITypeDeclaration, ITypeParamContext, IDefinition {
   Object baseType;
   List<?> typeParams;
   // NOTE: if there's a null value here, it means a wildcard type parameter - one that matches anything.  e.g. Collections.emptyList().
   List<Object> types;
   LayeredSystem system;
   ITypeDeclaration definedInType;

   public ParamTypeDeclaration(ITypeDeclaration it, List<?> typeParameters, List<Object> typeDefs, Object baseTypeDecl) {
      this(it.getLayeredSystem(), typeParameters, typeDefs, baseTypeDecl);
      definedInType = it;
   }

   public ParamTypeDeclaration(LayeredSystem sys, List<?> typeParameters, List<Object> typeDefs, Object baseTypeDecl) {
      system = sys;
      typeParams = typeParameters;
      baseType = baseTypeDecl;
      int ix = 0;
      types = typeDefs;
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

   public boolean isAssignableFrom(ITypeDeclaration other) {
      if (other instanceof ParamTypeDeclaration) {
         ParamTypeDeclaration otherParamType = ((ParamTypeDeclaration) other);
         Object otherBaseType = otherParamType.baseType;
         if (!ModelUtil.isAssignableFrom(baseType, otherBaseType))
            return false;

         // TODO: Need to skip to find the most specific version of the other base type which matches to ensure the type parameters match.
         // This may need to be generalized to handle compiled base types but fixes a problem with the js TreeMap when entrySet is assigned a reference to the inner class whose type params get re-mapped during the extends.
         // I feel like we need to remap the type parameters we are given in some cases as they may move around and probably can't always get around skipping them until we find the most specific extends type as done here.
         do {
            Object otherBaseTypeExtends = ModelUtil.getExtendsClass(otherBaseType);
            if (otherBaseTypeExtends != null && ModelUtil.isAssignableFrom(baseType, otherBaseTypeExtends) && otherBaseTypeExtends instanceof ParamTypeDeclaration) {
               otherParamType = (ParamTypeDeclaration) otherBaseTypeExtends;
               otherBaseType = otherParamType.baseType;
            }
            else
               break;
         } while (true);

         List<?> otherTypeParams = otherParamType.typeParams;
         if (typeParams.size() != otherTypeParams.size())
            return false;

         for (int i = 0; i < typeParams.size(); i++) {
            Object typeParam = typeParams.get(i);
            Object otherTypeParam = otherTypeParams.get(i);
            // For TypeVariables, do we need to bind them to their real types in any situations here?
            if (!ModelUtil.isTypeVariable(typeParam) && !ModelUtil.isAssignableFrom(typeParam, otherTypeParam))
               return false;
            if (types != null && otherParamType.types != null) {
               Object type = types.get(i);
               if (i >= otherParamType.types.size())
                  return false;
               Object otherType = otherParamType.types.get(i);
               // A null type is a signal for a wildcard like <T> List<T> emptyList();
               if (type != null && otherType != null && !ModelUtil.isAssignableFrom(type, otherType))
                  return false;
            }
         }
         return true;
      }
      else {
         return ModelUtil.isAssignableFrom(baseType, other);
      }
   }

   public boolean isAssignableTo(ITypeDeclaration other) {
      return ModelUtil.isAssignableFrom(other, baseType);
   }

   public String getTypeName() {
      return CTypeUtil.getClassName(getFullTypeName());
   }

   public String getFullTypeName() {
      return getFullTypeName(true, false);
   }

   public String getFullTypeName(boolean includeDims, boolean includeTypeParams) {
      String baseName = ModelUtil.getTypeName(baseType);
      if (includeTypeParams && types != null) {
         StringBuilder sb = new StringBuilder();
         sb.append(baseName);
         sb.append("<");
         boolean first = true;
         for (Object t: types) {
            if (!first)
               sb.append(",");
            sb.append(ModelUtil.getTypeName(t,includeDims,includeTypeParams));
            first = false;
         }
         sb.append(">");
         baseName = sb.toString();
      }
      return baseName;
   }

   public String getFullBaseTypeName() {
      return ModelUtil.getTypeName(baseType);
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
      if (types != null) {
         StringBuilder sb = new StringBuilder();
         sb.append(baseName);
         sb.append("<");
         boolean first = true;
         for (Object t: types) {
            if (!first)
               sb.append(",");
            sb.append(ModelUtil.getCompiledTypeName(t));
            first = false;
         }
         sb.append(">");
         baseName = sb.toString();
      }
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

   public Object definesMethod(String name, List<? extends Object> parametersOrExpressions, ITypeParamContext ctx, Object refType, boolean isTransformed) {
      assert ctx == null;
      Object method = ModelUtil.definesMethod(baseType, name, parametersOrExpressions, this, refType, isTransformed);
      if (method != null && ModelUtil.hasParameterizedReturnType(method))
         return new ParamTypedMethod(method, this);
      return method;
   }

   public Object declaresConstructor(List<?> parametersOrExpressions, ITypeParamContext ctx) {
      return ModelUtil.declaresConstructor(baseType, parametersOrExpressions, this);
   }

   public Object definesConstructor(List<?> parametersOrExpressions, ITypeParamContext ctx, boolean isTransformed) {
      return ModelUtil.definesConstructor(baseType, parametersOrExpressions, this, null, isTransformed);
   }

   public Object definesMember(String name, EnumSet<JavaSemanticNode.MemberType> types, Object refType, TypeContext ctx) {
      return definesMember(name, types, refType, ctx, false, false);
   }

   public Object definesMember(String name, EnumSet<JavaSemanticNode.MemberType> types, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      Object member = ModelUtil.definesMember(baseType, name, types, refType, ctx, skipIfaces, isTransformed);
      if (member != null) {
         JavaSemanticNode.MemberType type = JavaSemanticNode.MemberType.getMemberType(member, types);
         if (type != null && ModelUtil.hasParameterizedType(member, type))
            return new ParamTypedMember(member, this, type);
      }
      return member;
   }

   public Object getInnerType(String name, TypeContext ctx) {
      return ModelUtil.getInnerType(baseType, name, ctx);
   }

   public Object getType(int position) {
      if (position == -1)
         return null;
      return types.get(position);
   }

   public Object getDefaultType(int position) {
      if (position == -1)
         return null;
      Object type = types.get(position);
      if (ModelUtil.isTypeVariable(type))
         return ModelUtil.getTypeParameterDefault(type);
      return type;
   }

   public Object getTypeDeclarationForParam(String name) {
      for (int i = 0; i < typeParams.size(); i++)
         if (ModelUtil.getTypeParameterName(typeParams.get(i)).equals(name))
            return types.get(i);
      return null;
   }

   public boolean implementsType(String otherTypeName) {
      // TODO: should we verify that our parameters match if the other type has assigned params too?
      return ModelUtil.implementsType(baseType, otherTypeName);
   }

   public Object getInheritedAnnotation(String annotationName, boolean skipCompiled) {
      return ModelUtil.getInheritedAnnotation(system, baseType, annotationName, skipCompiled);
   }

   public boolean isAssignableFromClass(Class c) {
      return ModelUtil.isAssignableFrom(baseType, c);
   }

   public List<Object> getAllMethods(String modifier, boolean hasModifier, boolean isDyn, boolean overridesComp) {
      Object[] baseMethods = ModelUtil.getAllMethods(baseType, modifier, hasModifier, isDyn, overridesComp);
      return parameterizeMethodList(baseMethods);
   }

   public List<Object> getMethods(String methodName, String modifier, boolean includeExtends) {
      Object[] baseMethods = ModelUtil.getMethods(baseType, methodName, modifier, includeExtends);
      return parameterizeMethodList(baseMethods);
   }

   public List<Object> getAllProperties(String modifier, boolean includeAssigns) {
      Object[] baseProps = ModelUtil.getProperties(baseType, modifier, includeAssigns);
      return parameterizePropList(baseProps);
   }

   public List<Object> getAllFields(String modifier, boolean hasModifier, boolean dynamicOnly, boolean includeObjs, boolean includeAssigns, boolean includeModified) {
      Object[] baseFields = ModelUtil.getFields(baseType, modifier, hasModifier, dynamicOnly, includeObjs, includeAssigns, includeModified);
      return parameterizePropList(baseFields);
   }

   public List<Object> getAllInnerTypes(String modifier, boolean thisClassOnly) {
      Object[] res = ModelUtil.getAllInnerTypes(baseType, modifier, thisClassOnly);
      if (res == null)
         return Collections.emptyList();
      return Arrays.asList(res);
   }

   public DeclarationType getDeclarationType() {
      return ModelUtil.getDeclarationType(baseType);
   }

   public Object getClass(String className, boolean useImports) {
      if (definedInType != null)
         return definedInType.getClass(className, useImports);
      else
         return system.getClassWithPathName(className, false, false);
   }

   public Object findTypeDeclaration(String typeName, boolean addExternalReference) {
      if (definedInType != null)
         return definedInType.findTypeDeclaration(typeName, addExternalReference);
      else
         return system.getTypeDeclaration(typeName);
   }

   public JavaModel getJavaModel() {
      return definedInType.getJavaModel();
   }

   public LayeredSystem getLayeredSystem() {
      return system;
   }

   private List<Object> parameterizeMethodList(Object[] baseMethods) {
      if (baseMethods == null || baseMethods.length == 0)
         return null;

      List<Object> result = new ArrayList<Object>(baseMethods.length);
      for (int i = 0; i < baseMethods.length; i++) {
         Object meth = baseMethods[i];
         if (ModelUtil.hasParameterizedReturnType(meth))
            result.add(new ParamTypedMethod(meth, this));
         else
            result.add(meth);
      }
      return result;
   }

   private List<Object> parameterizePropList(Object[] baseProps) {
      if (baseProps == null || baseProps.length == 0)
         return null;

      List<Object> result = new ArrayList<Object>(baseProps.length);
      for (int i = 0; i < baseProps.length; i++) {
         Object prop = baseProps[i];
         if (prop == null)
            continue;
         JavaSemanticNode.MemberType mtype = JavaSemanticNode.MemberType.getMemberType(prop, JavaSemanticNode.MemberType.AllSet);
         if (ModelUtil.hasParameterizedType(prop, mtype))
            result.add(new ParamTypedMember(prop, this, mtype));
         else
            result.add(prop);
      }
      return result;
   }

   public List<?> getClassTypeParameters() {
      return typeParams;
   }

   public Object[] getConstructors(Object refType) {
      return ModelUtil.getConstructors(baseType, refType);
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

   public void initDynStatements(Object inst, ExecutionContext ctx, TypeDeclaration.InitStatementMode mode) {
      if (!(baseType instanceof ITypeDeclaration))
         return;
      ((ITypeDeclaration) baseType).initDynStatements(inst, ctx, mode);
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

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(ModelUtil.getTypeName(baseType));
      sb.append("<");
      int i = 0;
      for (Object type:types) {
         if (i != 0)
            sb.append(", ");
         if (type != null)
            sb.append(ModelUtil.getTypeName(type));
         i++;
      }
      sb.append(">");
      return sb.toString();
   }

   public ParamTypeDeclaration copy() {
      return new ParamTypeDeclaration(system, new ArrayList<Object>(typeParams), new ArrayList<Object>(types), baseType);
   }
}
