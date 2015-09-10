/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.layer.Layer;
import sc.type.*;
import sc.layer.LayeredSystem;

import java.util.*;

public class ParamTypeDeclaration implements ITypeDeclaration, ITypeParamContext, IDefinition {
   Object baseType;
   List<?> typeParams;
   // NOTE: if there's a null value here, it means a wildcard type parameter - one that matches anything.  e.g. Collections.emptyList().
   List<Object> types;
   LayeredSystem system;
   ITypeDeclaration definedInType;
   ArrayList<TypeParamMap> typeParamMapping;

   // Set to true when this type is derived from parameter types which are not defined until the inferredType is set
   // It allows us to match against this parameter even if a type variable is not assigned.
   boolean unboundInferredType = false;

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
      // When dealing with type parameters Type<int> always becomes Type<Integer>
      if (types != null) {
         int i = 0;
         for (Object type:types) {
            if (type == null)
               System.out.println("*** Warning - null type in parameterized type");
            Object newType = ModelUtil.wrapPrimitiveType(type);
            if (newType != type)
               types.set(i, newType);
            i++;
         }
      }
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
      if (other instanceof ParamTypeDeclaration) {
         ParamTypeDeclaration otherParamType = ((ParamTypeDeclaration) other);
         Object otherBaseType = otherParamType.baseType;
         if (!ModelUtil.isAssignableFrom(baseType, otherBaseType, assignmentSemantics, null))
            return false;

         // TODO: Need to skip to find the most specific version of the other base type which matches to ensure the type parameters match.
         // This may need to be generalized to handle compiled base types but fixes a problem with the js TreeMap when entrySet is assigned a reference to the inner class whose type params get re-mapped during the extends.
         // I feel like we need to remap the type parameters we are given in some cases as they may move around and probably can't always get around skipping them until we find the most specific extends type as done here.
         do {
            Object otherBaseTypeExtends = ModelUtil.getExtendsClass(otherBaseType);
            if (otherBaseTypeExtends != null && ModelUtil.isAssignableFrom(baseType, otherBaseTypeExtends, assignmentSemantics, null) && otherBaseTypeExtends instanceof ParamTypeDeclaration) {
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
               if (type != null && otherType != null && !ModelUtil.isAssignableFrom(type, otherType, false, null, true))
                  return false;
            }
         }
         return true;
      }
      else {
         return ModelUtil.isAssignableFrom(baseType, other, assignmentSemantics, null);
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

   public Object definesMethod(String name, List<? extends Object> parametersOrExpressions, ITypeParamContext ctx, Object refType, boolean isTransformed, boolean staticOnly) {
      // assert ctx == null; ??? this fails unfortunately...
      Object method = ModelUtil.definesMethod(baseType, name, parametersOrExpressions, this, refType, isTransformed, staticOnly);
      if (ctx == null)
         ctx = this;
      // If we already got back some parameter types for this method, we need to merge the definitions of this type into the one we retrieved.
      // If it's a base type, it means mapping the parameter type names to the base type.
      if (method instanceof ParamTypedMethod) {
         ParamTypedMethod methPT = (ParamTypedMethod) method;
         methPT.updateParamTypes(this);
      }
      else if (method != null && ModelUtil.isParameterizedMethod(method))
         return new ParamTypedMethod(method, this, definedInType, parametersOrExpressions);
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
      if (position >= types.size()) {
         System.out.println("*** Invalid type parameter position");
         return null;
      }
      Object res = types.get(position);
      /*
      if (res instanceof JavaType) {
         res = ((JavaType) res).getTypeDeclaration();
         if (res == null)
            return Object.class;
      }
      */
      //if (ModelUtil.isTypeVariable(res)) {
         // Not Doing this here because we need this parameter at a higher level in the code - to resolve it elsewhere from getParameterizedReturnType
         //return ModelUtil.getTypeParameterDefault(res);
      //}
      return res;
   }

   public Object getDefaultType(int position) {
      if (position == -1)
         return null;
      Object type = types.get(position);
      if (ModelUtil.hasTypeVariables(type))
         return ModelUtil.getTypeParameterDefault(type);
      return type;
   }

   public Object getTypeForVariable(Object typeVar, boolean resolve) {
      // Is this type parameter bound to the type for this type context?  It might be a method type parameter or for a different type
      Object srcType = ModelUtil.getTypeParameterDeclaration(typeVar);
      if (ModelUtil.isMethod(srcType)) {
         // DEBUG-start
         Object meth = srcType;
         srcType = ModelUtil.getReturnType(meth);
         //if (ModelUtil.isAssignableFrom(srcType, baseType)) {
         //   Object oldRes = ModelUtil.resolveTypeParameter(srcType, this, ModelUtil.getTypeParameterName(typeVar));
            //return oldRes; // TODO: remove me.
         //}
         // DEBUG-end

         return typeVar;
      }
      if (ModelUtil.isAssignableFrom(srcType, baseType)) {
         return ModelUtil.resolveTypeParameter(srcType, this, ModelUtil.getTypeParameterName(typeVar));
      }
      else {
         return typeVar;
      }
   }

   public Object getTypeDeclarationForParam(String name, Object tvar, boolean resolve) {
      if (tvar != null) {
         Object decl = ModelUtil.getTypeParameterDeclaration(tvar);
         // Need to rule out the method at least - also checking isAssignable just because it should be assignable
         if (ModelUtil.isMethod(decl) || !ModelUtil.isAssignableFrom(decl, baseType))
            return null;
      }
      for (int i = 0; i < typeParams.size(); i++) {
         if (ModelUtil.getTypeParameterName(typeParams.get(i)).equals(name)) {
            Object res = types.get(i);
            if (resolve && ModelUtil.hasTypeVariables(res)) {
               res = ModelUtil.getTypeParameterDefault(res);
            }
            return res;
         }
      }
      return null;
   }

   public boolean implementsType(String otherTypeName, boolean assignment, boolean allowUnbound) {
      // TODO: should we verify that our parameters match if the other type has assigned params too?
      return ModelUtil.implementsType(baseType, otherTypeName, assignment, allowUnbound);
   }

   public Object getInheritedAnnotation(String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve) {
      return ModelUtil.getInheritedAnnotation(system, baseType, annotationName, skipCompiled, refLayer, layerResolve);
   }

   public ArrayList<Object> getAllInheritedAnnotations(String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve) {
      return ModelUtil.getAllInheritedAnnotations(system, baseType, annotationName, skipCompiled, refLayer, layerResolve);
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

   @Override
   public Object getConstructorFromSignature(String sig) {
      return ModelUtil.getConstructorFromSignature(baseType, sig);
   }

   @Override
   public Object getMethodFromSignature(String methodName, String signature, boolean resolveLayer) {
      return ModelUtil.getMethodFromSignature(baseType, methodName, signature, resolveLayer);
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
         return system.getClassWithPathName(className, null, false, false, false);
   }

   public Object findTypeDeclaration(String typeName, boolean addExternalReference) {
      Object res = null;
      if (definedInType != null)
         res = definedInType.findTypeDeclaration(typeName, addExternalReference);
      else
         res = system.getTypeDeclaration(typeName);
      if (res != null)
         return res;
      if (typeParams != null) {
         for (Object typeParam:typeParams)
            if (ModelUtil.getTypeParameterName(typeParam).equals(typeName))
               return typeParam;
      }
      return null;
   }

   public JavaModel getJavaModel() {
      return definedInType.getJavaModel();
   }

   @Override
   public boolean isLayerType() {
      return false;
   }

   public Layer getLayer() {
     return definedInType != null ? definedInType.getLayer() : null;
   }

   public LayeredSystem getLayeredSystem() {
      return system;
   }

   public Layer getRefLayer() {
      return definedInType != null ? definedInType.getLayer() : null;
   }

   private List<Object> parameterizeMethodList(Object[] baseMethods) {
      if (baseMethods == null || baseMethods.length == 0)
         return null;

      List<Object> result = new ArrayList<Object>(baseMethods.length);
      for (int i = 0; i < baseMethods.length; i++) {
         Object meth = baseMethods[i];
         if (ModelUtil.isParameterizedMethod(meth))
            result.add(new ParamTypedMethod(meth, this, definedInType, null));
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

   public Object getArrayComponentType() {
      if (baseType != null)
         return ModelUtil.getArrayComponentType(baseType);
      return null;
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
      if (definedInType != null)
         return new ParamTypeDeclaration(definedInType, new ArrayList<Object>(typeParams), new ArrayList<Object>(types), baseType);
      else
         return new ParamTypeDeclaration(system, new ArrayList<Object>(typeParams), new ArrayList<Object>(types), baseType);
   }

   public void addMappedTypeParameters(Map<TypeParamKey,Object> paramMap) {
      if (typeParamMapping != null) {
         for (TypeParamMap typeParamMap:typeParamMapping) {
            Object mappedType = typeParamMap.toVar;
            Object value = paramMap.get(new TypeParamKey(mappedType));
            if (value != null) {
               if (!ModelUtil.isTypeVariable(value)) {
                  TypeParamKey fromVarKey = new TypeParamKey(typeParamMap.fromVar);
                  Object oldValue = paramMap.get(fromVarKey);
                  if (oldValue == null)
                     paramMap.put(fromVarKey, value);
                  else {
                     if (!ModelUtil.sameTypes(oldValue, value)) {
                        // TODO: we already have a definition for this parameter - how do we reconcile?
                     }
                  }
               }
               else if (value instanceof ExtendsType.LowerBoundsTypeDeclaration) {
                  System.out.println("*** Unknown lower bounds type (5)");
               }
               else if (ModelUtil.isWildcardType(value))
                  System.out.println("*** Unknown wildcard type");
            }
         }
      }
   }

   static class TypeParamMap {
      Object fromVar;
      Object toVar;
      public TypeParamMap(Object from, Object to) {
         fromVar = from;
         toVar = to;
      }
   }

   public void setTypeParameter(String varName, Object type) {
      if (typeParams != null) {
         int i = 0;
         for (Object typeParam : typeParams) {
            if (ModelUtil.isTypeVariable(typeParam) && ModelUtil.getTypeParameterName(typeParam).equals(varName)) {
               Object oldType = types.get(i);
               if (oldType != typeParam && ModelUtil.isTypeVariable(oldType) && !ModelUtil.sameTypeParameters(oldType, typeParam)) {
                  if (typeParamMapping == null)
                     typeParamMapping = new ArrayList<TypeParamMap>();
                  typeParamMapping.add(new TypeParamMap(oldType, typeParam));
               }
               types.set(i, ModelUtil.wrapPrimitiveType(type));
               return;
            }
            i++;
         }
      }
      System.err.println("*** Failed to augment parameterized type with computed type parameter: " + varName);
   }

   public boolean hasUnboundParameters() {
      if (types == null)
         return false;
      for (Object type:types) {
         if (type == null)
            return true;
         if (type instanceof JavaType && ((JavaType) type).getTypeDeclaration() == null)
            return true;
         if (ModelUtil.hasTypeVariables(type))
            return true;
      }
      return false;
   }

   public ITypeDeclaration getDefinedInType() {
      return definedInType;
   }

   public ParamTypeDeclaration cloneForNewTypes() {
      return new ParamTypeDeclaration(system, typeParams, new ArrayList<Object>(types), baseType);
   }

}
