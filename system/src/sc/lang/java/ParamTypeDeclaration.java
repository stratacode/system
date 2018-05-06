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
   Object definedInType;
   ArrayList<TypeParamMap> typeParamMapping;

   // Set to true when this type is derived from parameter types which are not defined until the inferredType is set
   // It allows us to match against this parameter even if a type variable is not assigned.
   boolean unboundInferredType = false;

   boolean writable = false;

   public ParamTypeDeclaration(LayeredSystem sys, Object it, List<?> typeParameters, List<Object> typeDefs, Object baseTypeDecl) {
      this(sys, typeParameters, typeDefs, baseTypeDecl);
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
            if (type == null) {
               // System.out.println("*** Warning - null type in parameterized type");
               // If there are unresolved types in the model this might happen.
               continue;
            }
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
         if (!ModelUtil.isAssignableFrom(baseType, otherBaseType, assignmentSemantics, null, getLayeredSystem()))
            return false;

         // TODO: Need to skip to find the most specific version of the other base type which matches to ensure the type parameters match.
         // This may need to be generalized to handle compiled base types but fixes a problem with the js TreeMap when entrySet is assigned a reference to the inner class whose type params get re-mapped during the extends.
         // I feel like we need to remap the type parameters we are given in some cases as they may move around and probably can't always get around skipping them until we find the most specific extends type as done here.

         /* This code below does not work because we are not mapping the parameter values that might be supplied on 'other' as we walk through the type hierarchy.
            Below, we now call resolveTypeParameter which handles the mapping from 'baseType' to the base type of 'other' while mapping the parameters
         do {
            Object otherBaseTypeExtends = ModelUtil.getExtendsClass(otherBaseType);
            if (otherBaseTypeExtends != null && ModelUtil.isAssignableFrom(baseType, otherBaseTypeExtends, assignmentSemantics, null, getLayeredSystem()) && otherBaseTypeExtends instanceof ParamTypeDeclaration) {
               otherParamType = (ParamTypeDeclaration) otherBaseTypeExtends;
               otherBaseType = otherParamType.baseType;
            }
            else
               break;
         } while (true);
         */

         for (int i = 0; i < typeParams.size(); i++) {
            Object typeParam = typeParams.get(i);
            Object otherTypeParam = ModelUtil.resolveTypeParameter(baseType, otherParamType, typeParam);
            // May not be mapped to a type parameter in the base type
            if (otherTypeParam == null)
               continue;
            // For TypeVariables, do we need to bind them to their real types in any situations here?
            if (!ModelUtil.isTypeVariable(typeParam) && !ModelUtil.isAssignableFrom(typeParam, otherTypeParam))
               return false;
            // TODO: This is a complex case - do we need to check if this type parameter is defined in this param type declaration - e.g. HashMap.EntryIterator<K, V> extends Iterator<Map.Entry<K, V>>
            if (!ModelUtil.isTypeVariable(otherTypeParam))
               continue;
            if (types != null && otherParamType.types != null) {
               // the case where this happens is if you have a type declarated with the wildcard: <> and are in an isAssignableFrom clause.  Assume it's ok rather than rejecting
               if (i >= types.size())
                  return true;
               Object type = types.get(i);
               int otherPos = ModelUtil.getTypeParameterPosition(otherBaseType, ModelUtil.getTypeParameterName(otherTypeParam));
               if (otherPos >= otherParamType.types.size()) {
                  if (otherParamType.types.size() != 0)
                     System.out.println("*** Error - mismatched param type lists");
                  // else - wildcard type - let it match
               }
               else {
                  Object otherType = otherParamType.types.get(otherPos);
                  // A null type is a signal for a wildcard like <T> List<T> emptyList();
                  if (type != null && otherType != null && !ModelUtil.isAssignableFrom(type, otherType, false, null, true, getLayeredSystem()))
                     return false;
               }
            }
         }
         return true;
      }
      // For ? super X do make sure this type is a super type of the base type in the super
      else if (other instanceof ExtendsType.LowerBoundsTypeDeclaration) {
         return ModelUtil.isAssignableFrom(this, ((ExtendsType.LowerBoundsTypeDeclaration) other).baseType);
      }
      else {
         return ModelUtil.isAssignableFrom(baseType, other, assignmentSemantics, null, getLayeredSystem());
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

   @Override
   public String getJavaFullTypeName() {
      return ModelUtil.getJavaFullTypeName(baseType);
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

   public Object definesMethod(String name, List<? extends Object> parametersOrExpressions, ITypeParamContext ctx, Object refType, boolean isTransformed, boolean staticOnly, Object inferredType, List<JavaType> methodTypeArgs) {
      // assert ctx == null; ??? this fails unfortunately...
      Object method = ModelUtil.definesMethod(baseType, name, parametersOrExpressions, this, refType, isTransformed, staticOnly, inferredType, methodTypeArgs, system);
      if (ctx == null)
         ctx = this;
      // If we already got back some parameter types for this method, we need to merge the definitions of this type into the one we retrieved.
      // If it's a base type, it means mapping the parameter type names to the base type.
      if (method instanceof ParamTypedMethod) {
         ParamTypedMethod methPT = (ParamTypedMethod) method;
         methPT.updateParamTypes(this);
      }
      else if (method != null && ModelUtil.isParameterizedMethod(method))
         return new ParamTypedMethod(system, method, this, definedInType, parametersOrExpressions, inferredType, methodTypeArgs);
      return method;
   }

   public Object declaresConstructor(List<?> parametersOrExpressions, ITypeParamContext ctx) {
      return ModelUtil.declaresConstructor(system, baseType, parametersOrExpressions, this);
   }

   public Object definesConstructor(List<?> parametersOrExpressions, ITypeParamContext ctx, boolean isTransformed) {
      return ModelUtil.definesConstructor(system, baseType, parametersOrExpressions, this, null, isTransformed);
   }

   public Object definesMember(String name, EnumSet<JavaSemanticNode.MemberType> types, Object refType, TypeContext ctx) {
      return definesMember(name, types, refType, ctx, false, false);
   }

   public Object definesMember(String name, EnumSet<JavaSemanticNode.MemberType> types, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      Object member = ModelUtil.definesMember(baseType, name, types, refType, ctx, skipIfaces, isTransformed, system);
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
         srcType = ModelUtil.getReturnType(meth, true);
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
         Object typeParam = typeParams.get(i);
         if (ModelUtil.getTypeParameterName(typeParam).equals(name)) {
            // It's not enough to match the name here as the same name could be used differently in the type
            // hierarchy - e.g.  class MultiValuedMap<K, V> extends Map<K, List<V>>
            if (tvar != null && !ModelUtil.sameTypeParameters(typeParam, tvar)) {
               continue;
            }
            Object res = types.get(i);
            if (resolve && ModelUtil.hasTypeVariables(res)) {
               res = ModelUtil.getTypeParameterDefault(res);
            }
            return res;
         }
      }
      if (tvar != null) {
         Object tvarDecl = ModelUtil.getTypeParameterDeclaration(tvar);
         Object res = ModelUtil.resolveTypeParameter(tvarDecl, this, tvar);
         if (res != null && ModelUtil.isTypeVariable(res)) {
            if (ModelUtil.sameTypes(ModelUtil.getTypeParameterDeclaration(res), this))
               return getTypeDeclarationForParam(ModelUtil.getTypeParameterName(res), res, resolve);
         }
         return ModelUtil.getTypeDeclFromType(this, res, false, getLayeredSystem(), resolve, definedInType);
         // TODO: do we need to resolve any type parameters here before returning?
         //return res;
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
         return ModelUtil.getClassFromType(system, definedInType, className, useImports);
      else
         return system.getClassWithPathName(className, null, false, false, false);
   }

   public Object findTypeDeclaration(String typeName, boolean addExternalReference) {
      Object res = null;
      if (definedInType != null)
         res = ModelUtil.findTypeDeclaration(system, definedInType, typeName, null, addExternalReference);
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
      return definedInType instanceof ITypeDeclaration ? ((ITypeDeclaration) definedInType).getJavaModel() : null;
   }

   @Override
   public boolean isLayerType() {
      return false;
   }

   public Layer getLayer() {
     return definedInType != null ? ModelUtil.getLayerForType(system, definedInType) : null;
   }

   public LayeredSystem getLayeredSystem() {
      return system;
   }

   public Layer getRefLayer() {
      return getLayer();
   }

   public List<Object> parameterizeMethodList(Object[] baseMethods) {
      if (baseMethods == null || baseMethods.length == 0)
         return null;

      List<Object> result = new ArrayList<Object>(baseMethods.length);
      for (int i = 0; i < baseMethods.length; i++) {
         Object meth = baseMethods[i];
         if (meth instanceof ParamTypedMethod)
            meth = ((ParamTypedMethod) meth).method;
         if (ModelUtil.isParameterizedMethod(meth))
            result.add(new ParamTypedMethod(system, meth, this, definedInType, null, null, null));
         else
            result.add(meth);
      }
      return result;
   }

   public Object parameterizeMethod(Object meth) {
      if (meth instanceof ParamTypedMethod)
         meth = ((ParamTypedMethod) meth).method;
      if (ModelUtil.isParameterizedMethod(meth))
         return new ParamTypedMethod(system, meth, this, definedInType, null, null, null);
      return meth;
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

   public Map<String,Object> getAnnotations() {
      return ModelUtil.getAnnotations(baseType);
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

   public void setBaseType(Object nbt) {
      baseType = nbt;
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

   public ITypeDeclaration resolve(boolean modified) {
      if (baseType instanceof ITypeDeclaration) {
         Object newType = ((ITypeDeclaration) baseType).resolve(modified);
         if (newType != null)
            baseType = newType;
      }
      return this;
   }

   public boolean useDefaultModifier() {
      return baseType instanceof ITypeDeclaration && ((ITypeDeclaration) baseType).useDefaultModifier();
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      if (baseType != null)
         sb.append(ModelUtil.getTypeName(baseType));
      sb.append("<");
      int i = 0;
      if (types != null) {
         for (Object type:types) {
            if (i != 0)
               sb.append(", ");
            if (type != null) {
               sb.append(ModelUtil.paramTypeToString(type));
            }
            i++;
         }
      }
      sb.append(">");
      return sb.toString();
   }

   public ParamTypeDeclaration copy() {
      ParamTypeDeclaration res;
      if (definedInType != null)
          res = new ParamTypeDeclaration(system, definedInType, new ArrayList<Object>(typeParams), new ArrayList<Object>(types), baseType);
      else
          res = new ParamTypeDeclaration(system, new ArrayList<Object>(typeParams), new ArrayList<Object>(types), baseType);
      res.writable = true;
      return res;
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

   public Object mapTypeParameters(Map<TypeParamKey, Object> paramMap) {
      if (types == null) {
         return this;
      }
      ParamTypeDeclaration res = null;
      int ix = 0;
      for (Object paramType:types) {
         if (ModelUtil.isTypeVariable(paramType)) {
            Object defaultParamType = ModelUtil.getTypeParameterDefault(paramType);
            Object newParamType = paramMap.get(new TypeParamKey(paramType));
            if (newParamType == defaultParamType)
               continue;
            if (newParamType != null && newParamType != paramType) {
               if (res == null)
                  res = copy();
               res.types.set(ix, newParamType);
            }
         }
         ix++;
      }
      if (res != null)
         return res;
      return this;
   }

   public int getMappedParameterPosition(String typeParamName) {
      int i = 0;
      for (Object type:types) {
         if (ModelUtil.isTypeVariable(type) && ModelUtil.getTypeParameterName(type).equals(typeParamName))
            return i;
         i++;
      }
      return -1;
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
               Object origType = types.get(i);
               if (ModelUtil.isTypeVariable(origType)) {
                  if (!writable)
                     System.err.println("*** writable type violation");
                  types.set(i, ModelUtil.wrapPrimitiveType(type));
               }
               // Here the 'type' may be more specific for the core type but may not include type parameters which exist in the current type.
               else if (type != null) {
                  Object newType = ModelUtil.refineType(system, definedInType, origType, type);
                  if (newType != origType && !writable)
                     System.err.println("*** writable type violation");
                  types.set(i, newType);
               }
               return;
            }
            i++;
         }
      }
   }

   public void setTypeParamIndex(int ix, Object type) {
      if (!writable)
         System.out.println("*** writable type violation");
      types.set(ix, type);
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
         if (ModelUtil.hasUnboundTypeParameters(type))
            return true;
      }
      return false;
   }

   public Object getDefinedInType() {
      return definedInType;
   }

   public ParamTypeDeclaration cloneForNewTypes() {
      ParamTypeDeclaration res;
      if (definedInType == null)
         res = new ParamTypeDeclaration(system, typeParams, new ArrayList<Object>(types), baseType);
      else
         res = new ParamTypeDeclaration(system, definedInType, typeParams, new ArrayList<Object>(types), baseType);
      res.writable = true;
      return res;
   }

   // If we have something like class Foo<A,B> extends Bar<C,D> - need to perform the type mapping on a copy of the param type here
   public static Object convertBaseTypeContext(ITypeParamContext ctx, Object baseType) {
      if (ctx != null && baseType instanceof ParamTypeDeclaration) {
         ParamTypeDeclaration newType = null;
         ParamTypeDeclaration origType = (ParamTypeDeclaration) baseType;
         List<?> typeParams = origType.getClassTypeParameters();
         if (typeParams != null) {
            for (int ix = 0; ix < typeParams.size(); ix++) {
               Object typeParam = typeParams.get(ix);
               Object newVal = ctx.getTypeForVariable(typeParam, true);
               if (newVal != null && newVal != typeParam) {
                  if (newType == null)
                     newType = origType.cloneForNewTypes();
                  newType.setTypeParamIndex(ix, newVal);
               }
            }
         }
         if (newType != null)
            baseType = newType;
      }
      return baseType;
   }

   public static Object toBaseType(Object paramType) {
      if (paramType instanceof ParamTypeDeclaration)
         return ((ParamTypeDeclaration) paramType).baseType;
      return paramType;
   }
}
