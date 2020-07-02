/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.classfile.CFClass;
import sc.db.BaseTypeDescriptor;
import sc.db.DBTypeDescriptor;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.type.*;
import sc.util.StringUtil;

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

   LayeredSystem system;
   public Object definedInType;

   // All array classes just have this one property so they share the same cache
   private final static DynType arrayPropertyCache = TypeUtil.getPropertyCache(OBJECT_ARRAY_CLASS);

   public ArrayTypeDeclaration(LayeredSystem sys, Object dit, Object comp, String arrayDims) {
      system = sys;
      if (comp == null)
         System.out.println("*** Error null array component type");
      if (comp instanceof ArrayTypeDeclaration)
         System.out.println("*** Error - nesting array types");

      if (comp instanceof String && ((String) comp).equals("Invalid type sentinel"))
         System.out.println("*** Invalid array type!");
      componentType = comp;
      arrayDimensions = arrayDims;
      definedInType = dit;
      /*
      if (dit == null)
         System.out.println("*** No defined in type for ArrayTypeDeclaration");
      */
   }

   /** Handles nested array inside of array */
   public static ArrayTypeDeclaration create(LayeredSystem sys, Object compType, String arrayDims, Object dit) {
      int numInnerDims = 0;
      while (compType instanceof ArrayTypeDeclaration) {
         ArrayTypeDeclaration prevArr = (ArrayTypeDeclaration) compType;
         compType = prevArr.getComponentType();
         numInnerDims++;
      }
      arrayDims = arrayDims + StringUtil.repeat("[]", numInnerDims);
      return new ArrayTypeDeclaration(sys, dit, compType, arrayDims);
   }

   public static ArrayTypeDeclaration create(LayeredSystem sys, Object compType, int ndim, Object dit) {
      while (compType instanceof ArrayTypeDeclaration) {
         ArrayTypeDeclaration arrCompType = (ArrayTypeDeclaration) compType;
         ndim += arrCompType.getNumDims();
         compType = arrCompType.getComponentType();
      }
      return new ArrayTypeDeclaration(sys, dit, compType, JavaType.getDimsStr(ndim));
   }

   public boolean isAssignableFrom(ITypeDeclaration other, boolean assignmentSemantics) {
      //if (other instanceof TypeDeclaration || other instanceof CFClass || other instanceof ParamTypeDeclaration || other instanceof EnumConstant)
      //   return false;

      // We might have a ParamTypeDeclaration wrapping the array - if so, just unwrap
      if (other instanceof ParamTypeDeclaration) {
         Object otherObj = ((ParamTypeDeclaration) other).getBaseType();
         return ModelUtil.isAssignableFrom(this, otherObj, assignmentSemantics, null, getLayeredSystem());
      }

      if (!(other instanceof ArrayTypeDeclaration))
         return false;

      ArrayTypeDeclaration otherTD = (ArrayTypeDeclaration) other;

      if (otherTD.arrayDimensions.equals(arrayDimensions))
          return ModelUtil.isAssignableFrom(componentType, otherTD.componentType, assignmentSemantics, null, getLayeredSystem());
      else {
         int dimDiff = otherTD.arrayDimensions.length() - arrayDimensions.length();
         if (dimDiff < 0)
            return false;
         ArrayTypeDeclaration otherCompArr = new ArrayTypeDeclaration(otherTD.system, otherTD.definedInType, otherTD.componentType, otherTD.arrayDimensions.substring(dimDiff));
         return ModelUtil.isAssignableFrom(componentType, otherCompArr, assignmentSemantics, null, getLayeredSystem());
      }
   }

   public boolean isAssignableTo(ITypeDeclaration other) {
      if (other instanceof TypeDeclaration || other instanceof CFClass)
         return false;

      ArrayTypeDeclaration otherTD = (ArrayTypeDeclaration) other;

      return ModelUtil.isAssignableFrom(otherTD.componentType, componentType, getLayeredSystem()) && otherTD.arrayDimensions.equals(arrayDimensions);
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

   public String getJavaFullTypeName() {
      return ModelUtil.getJavaFullTypeName(componentType);
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
            return Type.get(cclass).getPrimitiveArrayClass(getNumDims());
         else
            return Type.get(cclass).getArrayClass(cclass, getNumDims());
      }
      return null;
   }

   public static Class getCompiledArrayClassForType(Object type, int numDims) {
      Object componentClass = ModelUtil.getCompiledClass(type);
      if (componentClass instanceof Class) {
         Class cclass = (Class) componentClass;
         if (cclass.isPrimitive())
            return Type.get(cclass).getPrimitiveArrayClass(numDims);
         else
            return Type.get(cclass).getArrayClass(cclass, numDims);
      }
      throw new IllegalArgumentException("No compiled class for to create array type");
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


   public Object definesMethod(String name, List<? extends Object> parametersOrExpressions, ITypeParamContext ctx, Object refType, boolean isTransformed, boolean staticOnly, Object inferredType, List<JavaType> methodTypeArgs) {
      Object res = ModelUtil.definesMethod(OBJECT_ARRAY_CLASS, name, parametersOrExpressions, ctx, refType, isTransformed, staticOnly, inferredType, methodTypeArgs, getLayeredSystem());
      // The clone method in an array declaration seems to magically know the return value is an array even though reflection on the class does not detect a clone method.
      if (name.equals("clone")) {
         return new ArrayCloneMethod(this, res, system, definedInType instanceof ITypeDeclaration ? ((ITypeDeclaration) definedInType).getJavaModel() : null);
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
      return ModelUtil.definesMember(OBJECT_ARRAY_CLASS, name, type, refType, ctx, skipIfaces, isTransformed, system);
   }

   public Object definesMember(String name, EnumSet<JavaSemanticNode.MemberType> type, Object refType, TypeContext ctx) {
      return definesMember(name, type, refType, ctx, false, false);
   }

   public Object getInnerType(String name, TypeContext ctx) {
      return null;
   }

   public boolean implementsType(String otherTypeName, boolean assignment, boolean allowUnbound) {
      int ix = otherTypeName.indexOf("[");
      if (ix == -1) {
         // All array types are assignable to Object
         return otherTypeName.equals("java.lang.Object");
      }

      // Primitive type [B, [X, etc.
      if (ix == 0) {
         int otherNdim = otherTypeName.lastIndexOf("[") + 1;
         int ourNdim = getNumDims();

         if (otherNdim < ourNdim) {
            int ourResDim = ourNdim - otherNdim;
            String otherBaseType = otherTypeName.substring(otherNdim + 1);
            int semiIx = otherBaseType.indexOf(';');
            if (semiIx != -1)
               otherBaseType = otherBaseType.substring(0, semiIx);
            return ModelUtil.implementsType(new ArrayTypeDeclaration(system, definedInType, componentType, StringUtil.repeat("[]", ourResDim)), otherBaseType, assignment, allowUnbound);
         }

         // It has more dims than us.  Need to strip off
         if (otherNdim > ourNdim) {
            String otherTypeCompName = otherTypeName.substring(ourNdim);

            return ModelUtil.implementsType(componentType, otherTypeCompName, assignment, allowUnbound);
         }

         String arrayTypeName = otherTypeName.substring(otherNdim, otherNdim + 1);
         Type t = Type.getArrayType(arrayTypeName);

         Object otherComponentType;
         if (t.primitiveClass == null) {
            if (definedInType instanceof ITypeDeclaration)
               otherComponentType = ((ITypeDeclaration) definedInType).findTypeDeclaration(otherTypeName.substring(otherNdim + 1, otherTypeName.length() - 1), false);
            else
               otherComponentType = ModelUtil.findTypeDeclaration(system, definedInType, otherTypeName.substring(otherNdim + 1, otherTypeName.length() - 1), null, false);
         }
         else
            otherComponentType = t.primitiveClass;

         return ModelUtil.isAssignableFrom(otherComponentType, componentType, assignment, null, allowUnbound, getLayeredSystem());

      }
      else {
         return arrayDimensions.length() == otherTypeName.length()-ix &&
                ModelUtil.implementsType(componentType, otherTypeName, assignment, allowUnbound);
      }
   }

   public Object getInheritedAnnotation(String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve) {
      return ModelUtil.getInheritedAnnotation(getLayeredSystem(), componentType, annotationName, skipCompiled, refLayer, layerResolve);
   }

   public ArrayList<Object> getAllInheritedAnnotations(String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve) {
      return ModelUtil.getAllInheritedAnnotations(getLayeredSystem(), componentType, annotationName, skipCompiled, refLayer, layerResolve);
   }

   // Don't think this is used right now but basically keep the dimensions in tact and return the componenet's base type.
   public Object getDerivedTypeDeclaration() {
      if (ModelUtil.isTypeVariable(componentType))
         return null;
      Object superComponentType = ModelUtil.getSuperclass(componentType);
      if (superComponentType == null)
         return null;
      return new ArrayTypeDeclaration(system, definedInType, superComponentType, arrayDimensions);
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
      return ModelUtil.isAssignableFrom(componentType, classComponentType, getLayeredSystem());
   }

   public List<Object> getAllMethods(String modifier, boolean hasModifier, boolean isDyn, boolean overridesComp) {
      return null;
   }

   public List<Object> getMethods(String methodName, String modifier, boolean includeExtends) {
      return null;
   }

   @Override
   public Object getConstructorFromSignature(String sig) {
      return null;
   }

   @Override
   public Object getMethodFromSignature(String methodName, String signature, boolean resolveLayer) {
      return null;
   }

   public List<Object> getAllProperties(String modifier, boolean includeAssigns) {
      return null;
   }

   public List<Object> getDeclaredProperties(String modifier, boolean includeAssigns, boolean includeModified, boolean editorProperties) {
      return null;
   }

   public List<Object> getAllFields(String modifier, boolean hasModifier, boolean dynamicOnly, boolean includeObjs, boolean includeAssigns, boolean includeModified) {
      return null;
   }

   public List<Object> getAllInnerTypes(String modifier, boolean thisClassOnly, boolean includeInherited) {
      return null;
   }

   public DeclarationType getDeclarationType() {
      return ModelUtil.getDeclarationType(componentType);
   }

   public Object getComponentType() {
      int numDims = getNumDims();
      if (numDims <= 1)
         return componentType;
      else
         return ArrayTypeDeclaration.create(system, componentType, numDims - 1, definedInType);
   }

   public int getNumDims() {
      return arrayDimensions.length() >> 1;
   }

   public Object getClass(String className, boolean useImports, boolean compiledOnly) {
      return ((ITypeDeclaration) definedInType).getClass(className, useImports, compiledOnly);
   }

   public Object findTypeDeclaration(String typeName, boolean addExternalReference) {
      return ModelUtil.findTypeDeclaration(system, definedInType, typeName, null, addExternalReference);
   }

   public JavaModel getJavaModel() {
      if (definedInType instanceof ITypeDeclaration)
         return ((ITypeDeclaration) definedInType).getJavaModel();
      return null;
   }

   public boolean isLayerType() {
      return false;
   }

   public boolean isLayerComponent() {
      return false;
   }

   public Layer getLayer() {
      return definedInType instanceof ITypeDeclaration ? ((ITypeDeclaration) definedInType).getLayer() : null;
   }

   public LayeredSystem getLayeredSystem() {
      return system;
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

   public String toString() {
      return componentType == null ? "<null>" : componentType.toString() + (arrayDimensions == null ? "<no dims>" : arrayDimensions);
   }

   public ArrayTypeDeclaration cloneForNewTypes() {
      if (componentType instanceof ParamTypeDeclaration) {
         Object newCompType = ((ParamTypeDeclaration) componentType).cloneForNewTypes();
         return new ArrayTypeDeclaration(system, definedInType, newCompType, arrayDimensions);
      }
      // No mutable state so no need to clone
      return this;
   }

   public ITypeDeclaration resolve(boolean modified) {
      if (componentType instanceof ITypeDeclaration) {
         Object newType = ((ITypeDeclaration) componentType).resolve(modified);
         if (newType != null)
            componentType = newType;
      }
      return this;
   }

   public boolean useDefaultModifier() {
      return componentType instanceof ITypeDeclaration && ((ITypeDeclaration) componentType).useDefaultModifier();
   }

   public void setAccessTimeForRefs(long time) {
      if (componentType instanceof ITypeDeclaration)
         ((ITypeDeclaration) componentType).setAccessTimeForRefs(time);
   }

   public void setAccessTime(long time) {
      if (componentType instanceof ITypeDeclaration)
         ((ITypeDeclaration) componentType).setAccessTime(time);
   }

   public BaseTypeDescriptor getDBTypeDescriptor() {
      return null;
   }
}
