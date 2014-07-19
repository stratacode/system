/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.SemanticNodeList;
import sc.type.Type;
import sc.util.StringUtil;

import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Set;


public abstract class JavaType extends JavaSemanticNode implements ITypedObject {
   public String arrayDimensions;

   /** Returns the full type name of the base type including array dimensions if set */
   abstract public String getFullTypeName();

   /** Returns the absolute type name of the base type including array dimensions if set */
   abstract public String getAbsoluteTypeName();

   /** Returns the full type name of the base type without the array dimensions */
   abstract public String getFullBaseTypeName();

   abstract public Class getRuntimeClass();

   abstract public Object getRuntimeType();

   abstract public Class getRuntimeBaseClass();

   abstract boolean isVoid();

   abstract String toCompiledString(Object refType);

   // Returns the TypeDeclaration or Class for this type
   public abstract Object getTypeDeclaration();

   public int getNdims() {
      return arrayDimensions == null || arrayDimensions.length() == 0 ? -1 : arrayDimensions.length() >> 1;
   }

   public String getAbsoluteBaseTypeName() {
      Object type = getTypeDeclaration();
      if (type != null)
         return ModelUtil.getTypeName(type, false);
      else
         return getFullBaseTypeName();
   }

   /** Includes type arguments in the type name */
   public String getAbsoluteGenericTypeName(Object resultType, boolean includeDims) {
      if (includeDims)
         return getAbsoluteTypeName();
      else
         return getAbsoluteBaseTypeName();
   }

   /** Includes type arguments in the type name */
   public String getGenericTypeName(Object resultType, boolean includeDims) {
      if (includeDims)
         return getFullTypeName();
      else
         return getFullBaseTypeName();
   }

   /** Only for ClassType - returns the type arguments (if any) or null */
   public List<JavaType> getResolvedTypeArguments() {
      return null;
   }

   /** Returns the type declaration for a specific type argument */
   public Object getTypeArgumentDeclaration(int ix) {
      return null;
   }

   abstract public void initType(ITypeDeclaration definedInType, JavaSemanticNode node, boolean displayError, boolean isLayer);

   public static JavaType createJavaType(Object typeDeclaration) {
      String modelTypeName = ModelUtil.getTypeName(typeDeclaration);
      return createJavaTypeFromName(modelTypeName);
   }

   public static JavaType createJavaTypeFromName(String modelTypeName) {
      int ndims = 0;
      if (modelTypeName.endsWith("]")) {
         int ix;
         ndims = (modelTypeName.length() - (ix = modelTypeName.indexOf("["))) >> 1;
         modelTypeName = modelTypeName.substring(0, ix);
      }

      JavaType compType;
      if (Type.getPrimitiveType(modelTypeName) != null)
         compType = PrimitiveType.create(modelTypeName);
      else
         compType = ClassType.create(modelTypeName);
      if (ndims != 0)
         compType.arrayDimensions = getDimsStr(ndims);
      return compType;
   }

   public static JavaType createTypeFromTypeParams(Object type, JavaType[] typeParams) {
      return createFromTypeParams(ModelUtil.getTypeName(type), typeParams);
   }

   public static JavaType createFromTypeParams(String typeName, JavaType[] typeParams) {
      JavaType t = createJavaTypeFromName(typeName);
      if (t instanceof ClassType) {
         ClassType ct = (ClassType) t;
         
         if (typeParams != null)
            ct.setResolvedTypeArguments(SemanticNodeList.create((Object[]) typeParams));
      }
      return t;
   }


   /** Wraps primitive types in an object type wrapper for use in data binding where we only pass around objects (for now at least) */
   public static Object createObjectType(Object dstPropType) {
      if (dstPropType instanceof Class) {
         Class cl = (Class) dstPropType;
         if (cl.isPrimitive()) {
            return ClassType.createPrimitiveWrapper(cl.getName());
         }
         return ClassType.create(StringUtil.split(cl.getName(), '.'));
      }
      else if (dstPropType instanceof ITypeDeclaration) {
         return ClassType.create(StringUtil.split(((ITypeDeclaration) dstPropType).getFullTypeName(), '.'));
      }
      else if (dstPropType instanceof TypeParameter) {
         return createObjectType(((TypeParameter) dstPropType).getTypeDeclaration());
      }
      else if (dstPropType instanceof ParameterizedType) {
         return ClassType.create(StringUtil.split(ModelUtil.getTypeName(dstPropType), '.'));
      }
      else
         throw new UnsupportedOperationException();
   }

   public static String getDimsStr(int ndim) {
      switch (ndim) {
         case 1:
            return "[]";
         case 2:
            return "[][]";
         case 3:
            return "[][][]";
      }
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < ndim; i++) {
         sb.append("[]");
      }
      return sb.toString();
   }

   public void setSignatureDims(String dims) {
      if (dims != null)
         arrayDimensions = getDimsStr(dims.length());
      else
         arrayDimensions = null;
   }

   public String getSignatureDims() {
      if (arrayDimensions == null)
         return null;
      String s = "";
      for (int i = 0; i < arrayDimensions.length() >> 1; i++)
         s += "[";
      return s;
   }

   public abstract String getBaseSignature();

   public String getSignature() {
      String dimsStr = getSignatureDims();
      String baseSig = getBaseSignature();
      return dimsStr == null ? baseSig : dimsStr + baseSig;
   }

   public abstract void refreshBoundType();

   public void setTypeDeclaration(Object typeObj) {
      throw new IllegalArgumentException("Invalid set type");
   }

   public void addDependentTypes(Set<Object> types) {
      Object typeDecl = getTypeDeclaration();
      if (typeDecl != null)
         types.add(typeDecl);

      List<JavaType> typeArgs = getResolvedTypeArguments();
      if (typeArgs != null) {
         for (JavaType type:typeArgs)
            type.addDependentTypes(types);
      }
   }

   public boolean isTypeParameter() {
      return false;
   }

   public void transformToJS() {
   }

   public abstract String toGenerateString();

}
