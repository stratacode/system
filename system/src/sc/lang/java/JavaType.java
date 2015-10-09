/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ISemanticNode;
import sc.lang.SemanticNode;
import sc.lang.SemanticNodeList;
import sc.layer.LayeredSystem;
import sc.type.Type;
import sc.util.StringUtil;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.WildcardType;
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
   public Object getTypeDeclaration() {
      return getTypeDeclaration(null, false);
   }

   public abstract Object getTypeDeclaration(ITypeParamContext ctx, boolean resolve);

   public int getNdims() {
      return arrayDimensions == null || arrayDimensions.length() == 0 ? -1 : arrayDimensions.length() >> 1;
   }

   public String getAbsoluteBaseTypeName() {
      Object type = getTypeDeclaration(null, true);
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

   abstract public void initType(LayeredSystem sys, ITypeDeclaration definedInType, JavaSemanticNode node, ITypeParamContext ctx, boolean displayError, boolean isLayer, Object typeParam);

   public static JavaType createJavaType(Object typeDeclaration) {
      if (typeDeclaration instanceof WildcardType) {
         ExtendsType extType = ExtendsType.create((WildcardType) typeDeclaration);
         return extType;
      }
      String modelTypeName = ModelUtil.getTypeName(typeDeclaration);
      JavaType res = createJavaTypeFromName(modelTypeName);
      res.startWithType(typeDeclaration);
      return res;
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

   public static JavaType createFromParamType(Object type, ITypeParamContext ctx, ITypeDeclaration definedInType) {
      JavaType[] typeParamsArr = null;
      JavaType newType = null;
      ITypeDeclaration typeCtx = ctx instanceof ITypeDeclaration ? (ITypeDeclaration) ctx : ctx != null ? ctx.getDefinedInType() : definedInType;
      if (ModelUtil.hasTypeParameters(type)) {
         SemanticNodeList<JavaType> typeParams = new SemanticNodeList<JavaType>();
         int numParams = ModelUtil.getNumTypeParameters(type);
         for (int i = 0; i < numParams; i++) {
            Object typeParam = ModelUtil.getTypeParameter(type, i);
            if (typeParam instanceof WildcardType) {
               newType = ExtendsType.create((WildcardType) typeParam);
               if (!newType.isBound() && ctx != null)
                  newType.initType(ctx.getLayeredSystem(), typeCtx, null, ctx, true, false, typeParam);
               typeParam = newType;
            }
            else if (typeParam instanceof ExtendsType.LowerBoundsTypeDeclaration) {
               ExtendsType.LowerBoundsTypeDeclaration td = (ExtendsType.LowerBoundsTypeDeclaration) typeParam;
               Object newTypeParam = null;
               Object baseType = td.getBaseType();
               if (ModelUtil.isTypeVariable(baseType)) {
                  newTypeParam = ctx != null ? ctx.getTypeForVariable(baseType, false) : null;
                  if (newTypeParam != null && !(newTypeParam instanceof ExtendsType.LowerBoundsTypeDeclaration))
                     typeParam = new ExtendsType.LowerBoundsTypeDeclaration(newTypeParam);
               }
               else {
                  newType = ExtendsType.createSuper(td, ctx);
                  typeParam = newType;
               }
            }
            else if (typeParam instanceof GenericArrayType) {
               GenericArrayType gat = (GenericArrayType) typeParam;
               java.lang.reflect.Type componentType = gat.getGenericComponentType();
               newType = createFromParamType(componentType, ctx, definedInType);
               newType.setProperty("arrayDimensions", "[]");
               newType.initType(typeCtx.getLayeredSystem(), typeCtx, null, ctx, true, false, newType.getTypeDeclaration());
               typeParam = newType;
            }
            else if (ModelUtil.isTypeVariable(typeParam)) {
               Object newTypeParam = ctx != null ? ctx.getTypeForVariable(typeParam, false) : null;
               if (newTypeParam != null && newTypeParam != type)
                  typeParam = newTypeParam;
            }
            if (typeParam instanceof JavaType) {
               JavaType javaType = (JavaType) typeParam;
               javaType = javaType.resolveTypeParameters(ctx, false);
               typeParams.add(javaType);
            }
            else if (ModelUtil.hasTypeParameters(typeParam)) {
               if (typeParam == type)
                  System.out.println("*** Error!");
               else {
                  JavaType typeParamJavaType = createFromParamType(typeParam, ctx, definedInType);
                  typeParams.add(typeParamJavaType);
               }
            }
            else if (typeParam instanceof WildcardType) {
               newType = ExtendsType.create((WildcardType) typeParam);
               if (!newType.isBound())
                  newType.initType(ctx.getLayeredSystem(), typeCtx, null, ctx, true, false, typeParam);
               typeParams.add(newType);
            }
            else {
               String typeName = ModelUtil.getTypeName(typeParam);
               newType = ClassType.create(typeName);
               if (ctx != null)
                  newType.initType(ctx.getLayeredSystem(), typeCtx, null, ctx, true, false, typeParam);
               typeParams.add(newType);
            }
         }
         typeParamsArr = typeParams.toArray(new JavaType[typeParams.size()]);
      }
      else if (type instanceof WildcardType) {
         newType = ExtendsType.create((WildcardType) type);
         if (!newType.isBound() && ctx != null)
            newType.initType(ctx.getLayeredSystem(), typeCtx, null, ctx, true, false, null);
         return newType;
      }
      else if (type instanceof GenericArrayType || type instanceof ArrayTypeDeclaration) {
         StringBuilder arrDims = new StringBuilder();
         Object compType;
         do {
            compType = ModelUtil.getGenericComponentType(type);
            arrDims.append(ModelUtil.getArrayDimsStr(type));
         } while (compType instanceof GenericArrayType);

         if (ModelUtil.isWildcardType(compType)) {
            newType = ExtendsType.createFromType(compType, ctx);
         }
         else {
            newType = ClassType.create(ModelUtil.getTypeName(compType));
         }
         newType.arrayDimensions = arrDims.toString();
         if (ctx != null)
            newType.initType(ctx.getLayeredSystem(), typeCtx, null, ctx, true, false, compType);
         return newType;
      }
      else if (type instanceof ExtendsType.LowerBoundsTypeDeclaration) {
         newType = ExtendsType.createSuper((ExtendsType.LowerBoundsTypeDeclaration) type, ctx);
         if (ctx != null)
            newType.initType(ctx.getLayeredSystem(), typeCtx, null, ctx, true, false, type);
         return newType;
      }
      newType = createTypeFromTypeParams(type, typeParamsArr);
      if (ctx != null) {
         newType.initType(ctx.getLayeredSystem(), typeCtx, null, ctx, true, false, type);
      }
      return newType;
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

   public String getSignature(boolean expandTypeParams) {
      String dimsStr = getSignatureDims();
      String baseSig = getBaseSignature();
      if (baseSig.startsWith("T") && expandTypeParams) {
         Object type = getTypeDeclaration(null, true);
         if (ModelUtil.isArray(type))
            type = ModelUtil.getArrayComponentType(type);
         if (ModelUtil.isTypeVariable(type))
            type = ModelUtil.getTypeParameterDefault(type);
         baseSig = ModelUtil.getSignature(type);
      }
      return dimsStr == null ? baseSig : dimsStr + baseSig;
   }

   public abstract void refreshBoundType(int flags);

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

   // Returns true if this is a type parameter (e.g. T)
   public boolean isTypeParameter() {
      return false;
   }

   // Returns true if this is a type whose concrete type depends on the type context
   public abstract boolean isParameterizedType();

   public void transformToJS() {
   }

   public abstract String toGenerateString();

   public abstract JavaType resolveTypeParameters(ITypeParamContext t, boolean useDefaultsForUnbound);

   public abstract boolean isBound();

   abstract void startWithType(Object type);

   abstract Object definesTypeParameter(Object typeVar, ITypeParamContext ctx);

   public JavaType convertToArray(ITypeDeclaration definedInType) {
      JavaType newType = (JavaType) this.deepCopy(SemanticNode.CopyNormal, null);
      newType.parentNode = parentNode;
      if (newType.arrayDimensions == null)
         newType.arrayDimensions = "[]";
      else
         newType.arrayDimensions += "[]";
      return newType;
   }

}
