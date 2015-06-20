/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.classfile.CFClass;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.type.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

public class ArrayTypeDeclaration implements ITypeDeclaration, IArrayTypeDeclaration {
   public Object componentType;
   public String arrayDimensions;

   public static final Class OBJECT_ARRAY_CLASS = new Object[0].getClass();

   /** For some reason reflection does not show the clone method which returns an array type so we define a dummy one here to return */
   public static class DummyArrayClass {
      public Object[] clone() throws CloneNotSupportedException {
         return (Object[]) super.clone();
      }
   }

   // This is final because the array length is constant
   public static final int length = 0;
   public static final Field LENGTH_FIELD = RTypeUtil.getField(ArrayTypeDeclaration.class, "length");

   private ITypeDeclaration definedInType;

   // All array classes just have this one property so they share the same cache
   private final static DynType arrayPropertyCache = TypeUtil.getPropertyCache(OBJECT_ARRAY_CLASS);

   public ArrayTypeDeclaration(ITypeDeclaration dit, Object comp, String arrayDims) {
      if (comp == null)
         System.out.println("*** Error null array component type");
      componentType = comp;
      arrayDimensions = arrayDims;
      definedInType = dit;
      if (dit == null)
         System.out.println("*** No defined in type for ArrayTypeDeclaration");
   }

   /** Handles nested array inside of array */
   public static ArrayTypeDeclaration create(Object compType, String arrayDims, ITypeDeclaration dit) {
      if (compType instanceof ArrayTypeDeclaration) {
         ArrayTypeDeclaration prevArr = (ArrayTypeDeclaration) compType;
         arrayDims = prevArr.arrayDimensions + arrayDims;
         compType = prevArr.getComponentType();
      }
      return new ArrayTypeDeclaration(dit, compType, arrayDims);
   }

   public static ArrayTypeDeclaration create(Object compType, int ndim, ITypeDeclaration dit) {
      return new ArrayTypeDeclaration(dit, compType, JavaType.getDimsStr(ndim));
   }

   public boolean isAssignableFrom(ITypeDeclaration other, boolean assignmentSemantics) {
      //if (other instanceof TypeDeclaration || other instanceof CFClass || other instanceof ParamTypeDeclaration || other instanceof EnumConstant)
      //   return false;

      if (!(other instanceof ArrayTypeDeclaration))
         return false;

      ArrayTypeDeclaration otherTD = (ArrayTypeDeclaration) other;
      
      return ModelUtil.isAssignableFrom(componentType, otherTD.componentType, assignmentSemantics, null) && otherTD.arrayDimensions.equals(arrayDimensions);
   }

   public boolean isAssignableTo(ITypeDeclaration other) {
      if (other instanceof TypeDeclaration || other instanceof CFClass)
         return false;

      ArrayTypeDeclaration otherTD = (ArrayTypeDeclaration) other;

      return ModelUtil.isAssignableFrom(otherTD.componentType, componentType) && otherTD.arrayDimensions.equals(arrayDimensions);
   }

   public String getTypeName() {
      return CTypeUtil.getClassName(ModelUtil.getTypeName(componentType)) + arrayDimensions;
   }

   public String getFullTypeName(boolean includeDims, boolean includeTypeParams) {
      if (!includeDims)
         return ModelUtil.getTypeName(componentType);
      else
         return getFullTypeName();
   }

   public String getFullTypeName() {
      return ModelUtil.getTypeName(componentType) + arrayDimensions;
   }

   public String getFullBaseTypeName() {
      return ModelUtil.getTypeName(componentType);
   }

   public String getInnerTypeName() {
      return ModelUtil.getInnerTypeName(componentType);
   }

   public Class getCompiledClass() {
      Object componentClass = ModelUtil.getCompiledClass(componentType);
      if (componentClass instanceof Class) {
         Class cclass = (Class) componentClass;
         if (cclass.isPrimitive())
            return Type.get(cclass).getPrimitiveArrayClass(getNdim());
         else
            return Type.get(cclass).getArrayClass(cclass, getNdim());
      }
      return null;
   }

   public String getCompiledClassName() {
      return ModelUtil.getCompiledClassName(componentType);
   }

   public String getCompiledTypeName() {
      return ModelUtil.getCompiledClassName(componentType) + arrayDimensions;
   }

   public Object getRuntimeType() {
      return getCompiledClass();
   }

   public boolean isDynamicType() {
      return ModelUtil.isDynamicType(componentType);
   }

   public boolean isDynamicStub(boolean includeExtends) {
      return ModelUtil.isDynamicStub(componentType, includeExtends);
   }

   public int getNdim() {
      return arrayDimensions.length() >> 1;
   }

   public Object definesMethod(String name, List<? extends Object> parametersOrExpressions, ITypeParamContext ctx, Object refType, boolean isTransformed, boolean staticOnly) {
      Object res = ModelUtil.definesMethod(OBJECT_ARRAY_CLASS, name, parametersOrExpressions, ctx, refType, isTransformed, staticOnly);
      // The clone method in an array declaration seems to magically know the return value is an array even though reflection on the class does not detect a clone method.
      if (name.equals("clone")) {
         return new ArrayCloneMethod(this, res, definedInType.getJavaModel());
      }
      return res;
   }

   public Object declaresConstructor(List<?> parametersOrExpressions, ITypeParamContext ctx) {
      return null;
   }

   public Object definesConstructor(List<?> parametersOrExpressions, ITypeParamContext ctx, boolean isTransformed) {
      return null;
   }

   public Object definesMember(String name, EnumSet<JavaSemanticNode.MemberType> type, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      if (type.contains(JavaSemanticNode.MemberType.Field) && name.equals("length"))  // TODO: Java won't let us get at the real field.  This is to avoid compile errors but maybe we need to build a FieldDef
         return LENGTH_FIELD;
      return ModelUtil.definesMember(OBJECT_ARRAY_CLASS, name, type, refType, ctx, skipIfaces, isTransformed);
   }

   public Object definesMember(String name, EnumSet<JavaSemanticNode.MemberType> type, Object refType, TypeContext ctx) {
      return definesMember(name, type, refType, ctx, false, false);
   }

   public Object getInnerType(String name, TypeContext ctx) {
      return null;
   }

   public boolean implementsType(String otherTypeName, boolean assignment, boolean allowUnbound) {
      int ix = otherTypeName.indexOf("[");
      if (ix == -1)
         return false;

      // Primitive type [B, [X, etc.
      if (ix == 0) {
         int otherNdim = otherTypeName.lastIndexOf("[") + 1;
         int ourNdim = getNumDims();

         if (otherNdim < ourNdim)
            return false;

         // It has more dims than us.  Need to strip off
         if (otherNdim > ourNdim) {
            String otherTypeCompName = otherTypeName.substring(ourNdim);

            return ModelUtil.implementsType(componentType, otherTypeCompName, assignment, allowUnbound);
         }

         String arrayTypeName = otherTypeName.substring(otherNdim, otherNdim + 1);
         Type t = Type.getArrayType(arrayTypeName);

         Object otherComponentType;
         if (t.primitiveClass == null)
            otherComponentType = definedInType.findTypeDeclaration(otherTypeName.substring(otherNdim + 1, otherTypeName.length()-1), false);
         else
            otherComponentType = t.primitiveClass;

         return ModelUtil.isAssignableFrom(otherComponentType, componentType, assignment, null, allowUnbound);

      }
      else {
         return arrayDimensions.length() == otherTypeName.length()-ix &&
                ModelUtil.implementsType(componentType, otherTypeName, assignment, allowUnbound);
      }
   }

   public Object getInheritedAnnotation(String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve) {
      return ModelUtil.getInheritedAnnotation(definedInType.getLayeredSystem(), componentType, annotationName, skipCompiled, refLayer, layerResolve);
   }

   public ArrayList<Object> getAllInheritedAnnotations(String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve) {
      return ModelUtil.getAllInheritedAnnotations(definedInType.getLayeredSystem(), componentType, annotationName, skipCompiled, refLayer, layerResolve);
   }

   // Don't think this is used right now but basically keep the dimensions in tact and return the componenet's base type.
   public Object getDerivedTypeDeclaration() {
      Object superComponentType = ModelUtil.getSuperclass(componentType);
      if (superComponentType == null)
         return null;
      return new ArrayTypeDeclaration(definedInType, superComponentType, arrayDimensions);
   }

   public Object getExtendsTypeDeclaration() {
      return getDerivedTypeDeclaration();
   }

   public Object getExtendsType() {
      return ModelUtil.getExtendsJavaType(componentType);
   }

   public List<?> getImplementsTypes() {
      Object res = ModelUtil.getImplementsJavaTypes(componentType);
      return res == null ? null : Arrays.asList(res);
   }

   public boolean isTypeParameter() {
      return ModelUtil.isTypeVariable(componentType) || ModelUtil.isGenericArray(componentType);
   }

   public boolean isAssignableFromClass(Class classComponentType) {
      int ndim = arrayDimensions.length() >> 1;
      for (int i = 0; i < ndim; i++) {
         if (classComponentType == null || !classComponentType.isArray())
            return false;

         classComponentType = classComponentType.getComponentType();
         if (classComponentType == null)
            return false;
      }
      return ModelUtil.isAssignableFrom(componentType, classComponentType);
   }

   public List<Object> getAllMethods(String modifier, boolean hasModifier, boolean isDyn, boolean overridesComp) {
      return null;
   }

   public List<Object> getMethods(String methodName, String modifier, boolean includeExtends) {
      return null;
   }

   public List<Object> getAllProperties(String modifier, boolean includeAssigns) {
      return null;
   }

   public List<Object> getAllFields(String modifier, boolean hasModifier, boolean dynamicOnly, boolean includeObjs, boolean includeAssigns, boolean includeModified) {
      return null;
   }

   public List<Object> getAllInnerTypes(String modifier, boolean thisClassOnly) {
      return null;
   }

   public DeclarationType getDeclarationType() {
      return ModelUtil.getDeclarationType(componentType);
   }

   public Object getComponentType() {
      int numDims = getNumDims();
      if (numDims == 1)
         return componentType;
      else
         return ArrayTypeDeclaration.create(componentType, numDims - 1, definedInType);
   }

   public int getNumDims() {
      return arrayDimensions.length() >> 1;
   }

   public Object getClass(String className, boolean useImports) {
      return definedInType.getClass(className, useImports);
   }

   public Object findTypeDeclaration(String typeName, boolean addExternalReference) {
      return definedInType.findTypeDeclaration(typeName, addExternalReference);
   }

   public JavaModel getJavaModel() {
      return definedInType.getJavaModel();
   }

   public boolean isLayerType() {
      return false;
   }

   public Layer getLayer() {
      return definedInType != null ? definedInType.getLayer() : null;
   }

   public LayeredSystem getLayeredSystem() {
      return definedInType.getLayeredSystem();
   }

   public List<?> getClassTypeParameters() {
      return null;
   }

   public Object[] getConstructors(Object refType) {
      return null;
   }

   public boolean isComponentType() {
      return false;
   }

   public DynType getPropertyCache() {
      return arrayPropertyCache;
   }

   public boolean isEnumeratedType() {
      return false;
   }

   public Object getEnumConstant(String nextName) {
      return null;
   }

   public boolean isCompiledProperty(String name, boolean fieldMode, boolean interfaceMode) {
      // All of these are compiled properties
      return definesMember(name, JavaSemanticNode.MemberType.PropertyGetSetObj, null, null) != null;
   }

   public List<JavaType> getCompiledTypeArgs(List<JavaType> typeArgs) {
      return null;
   }

   public boolean needsOwnClass(boolean checkComponents) {
      return ModelUtil.needsOwnClass(componentType, checkComponents);
   }

   public boolean isDynamicNew() {
      return ModelUtil.isDynamicNew(componentType);
   }

   public void initDynStatements(Object inst, ExecutionContext ctx, TypeDeclaration.InitStatementMode mode) {
      if (componentType instanceof ITypeDeclaration)
         ((ITypeDeclaration) componentType).initDynStatements(inst, ctx, mode);
   }

   public void clearDynFields(Object inst, ExecutionContext ctx) {
      if (componentType instanceof ITypeDeclaration)
         ((ITypeDeclaration) componentType).clearDynFields(inst, ctx);
   }

   public Object[] getImplementsTypeDeclarations() {
      return null;
   }

   public Object[] getAllImplementsTypeDeclarations() {
      return null;
   }

   public boolean isRealType() {
      return true;
   }

   public void staticInit() {
      if (componentType != null)
         ModelUtil.initType(componentType);
   }

   public boolean isTransformedType() {
      if (componentType != null)
         return ModelUtil.isTransformedType(componentType);
      return false;
   }

   public Object getArrayComponentType() {
      return componentType;
   }
}
