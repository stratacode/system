/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.type.DynType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public interface ITypeDeclaration {
   boolean isAssignableFrom(ITypeDeclaration other, boolean assignmentSemantics);
   boolean isAssignableTo(ITypeDeclaration other);

   boolean isAssignableFromClass(Class other);

   String getTypeName();

   String getFullTypeName(boolean includeDims, boolean includeTypeParams);

   String getFullTypeName();

   String getFullBaseTypeName();

   String getInnerTypeName();

   Class getCompiledClass();

   String getCompiledClassName();

   String getCompiledTypeName();

   Object getRuntimeType();

   boolean isDynamicType();

   boolean isDynamicStub(boolean includeExtends);

   Object definesMethod(String name, List<?> parametersOrExpressions, ITypeParamContext ctx, Object refType, boolean isTransformed, boolean staticOnly);

   Object declaresConstructor(List<?> parametersOrExpressions, ITypeParamContext ctx);

   Object definesConstructor(List<?> parametersOrExpressions, ITypeParamContext ctx, boolean isTransformed);

   Object definesMember(String name, EnumSet<JavaSemanticNode.MemberType> type, Object refType, TypeContext ctx);

   Object definesMember(String name, EnumSet<JavaSemanticNode.MemberType> type, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed);

   Object getInnerType(String name, TypeContext ctx);

   boolean implementsType(String otherTypeName, boolean assignment, boolean allowUnbound);

   /** Returns the first occurrence of the specified annotation on the types in the type hierarchy */
   Object getInheritedAnnotation(String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve);

   /** Returns all occurrences of the specified annotation on the types in the type hierarchy ordered so the first type encountered during the traversal is first. */
   ArrayList<Object> getAllInheritedAnnotations(String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve);

   Object getDerivedTypeDeclaration();

   Object getExtendsTypeDeclaration();

   Object getExtendsType();

   List<?> getImplementsTypes();

   List<Object> getAllMethods(String modifier, boolean hasModifier, boolean isDyn, boolean overridesComp);

   List<Object> getMethods(String methodName, String modifier, boolean includeExtends);

   Object getConstructorFromSignature(String sig);

   Object getMethodFromSignature(String methodName, String signature, boolean resolveLayer);

   List<Object> getAllProperties(String modifier, boolean includeAssigns);

   /** Returns all of the fields based on the flags.  If modifier is not null then we only return those with have or don't have the modifier based on hasModifier.
    * If dynamicOnly is true, we only return dynamic fields.  If includeObjs is true, we return inner objects which have instance fields in this type.  If includeAssigns
    * is true, we return any property assignments we find along the way.  If includeModified is true, when we resolve objects we'll return the most specific type for
    * each inner type even if this is called on a type which is modified.
    */
   List<Object> getAllFields(String modifier, boolean hasModifier, boolean dynamicOnly, boolean includeObjs, boolean includeAssigns, boolean includeModified);

   List<Object> getAllInnerTypes(String modifier, boolean thisClassOnly);

   DeclarationType getDeclarationType();

   Object getClass(String className, boolean useImports);

   Object findTypeDeclaration(String typeName, boolean addExternalReference);

   JavaModel getJavaModel();

   boolean isLayerType();

   Layer getLayer();

   LayeredSystem getLayeredSystem();

   /**
    * This returns the normal typeParameters for the type.  It used to be called getTypeParameters but that conflicts with the semantic 'typeParameters' property on TypeDeclaration
    * This one needs to be distinct for ModifyTypes which can either set or inherit their type parameters.
    */
   List<?> getClassTypeParameters();

   Object[] getConstructors(Object refType);

   boolean isComponentType();

   DynType getPropertyCache();

   boolean isEnumeratedType();

   Object getEnumConstant(String nextName);

   boolean isCompiledProperty(String name, boolean fieldMode, boolean interfaceMode);

   /** Maps the supplied type arguments to those in the actual compiled class.  Used to propagate type params across in the dynamic/compiled stub */
   List<JavaType> getCompiledTypeArgs(List<JavaType> typeArgs);

   /** Returns false for object types which  */
   boolean needsOwnClass(boolean checkComponents);

   boolean isDynamicNew();

   void initDynStatements(Object inst, ExecutionContext ctx, TypeDeclaration.InitStatementMode mode);

   void clearDynFields(Object inst, ExecutionContext ctx);

   Object[] getImplementsTypeDeclarations();

   Object[] getAllImplementsTypeDeclarations();

   /** Return false for any types, such as lambda expressions, or intermediate template fragments which are not included in the type hierarchy (name and enclosing type) */
   boolean isRealType();

   void staticInit();

   boolean isTransformedType();

   /** If this type extends for example ArrayList return the value of the first type parameter so we get the accurate component type. */
   Object getArrayComponentType();
}
