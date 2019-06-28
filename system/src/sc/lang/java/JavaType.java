/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

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

   abstract String toCompiledString(Object refType, boolean retNullForDynObj);

   // Returns the TypeDeclaration or Class for this type
   public Object getTypeDeclaration() {
      return getTypeDeclaration(null, null, false, false, true, null, -1);
   }

   public abstract Object getTypeDeclaration(ITypeParamContext ctx, Object definedInType, boolean resolve, boolean refreshParams, boolean bindUnbound, Object baseType, int paramIx);

   public int getNdims() {
      return arrayDimensions == null || arrayDimensions.length() == 0 ? -1 : arrayDimensions.length() >> 1;
   }

   public String getAbsoluteBaseTypeName() {
      Object type = getTypeDeclaration(null, null, true, false, true, null, -1);
      if (type != null)
         return ModelUtil.getTypeName(type, false);
      else
         return getFullBaseTypeName();
   }

   public String getJavaFullTypeName() {
      Object type = getTypeDeclaration(null, null, true, false, true, null, -1);
      if (type != null)
         return ModelUtil.getJavaFullTypeName(type);
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

   public abstract boolean convertToSrcReference();

   abstract public void initType(LayeredSystem sys, Object definedInType, JavaSemanticNode node, ITypeParamContext ctx, boolean displayError, boolean isLayer, Object typeParam);

   public static JavaType createJavaType(LayeredSystem sys, Object typeDeclaration) {
      return createJavaType(sys, typeDeclaration, null, null);
   }

   public static JavaType createJavaType(LayeredSystem sys, Object typeDeclaration, ITypeParamContext ctx, Object definedInType) {
      if (typeDeclaration instanceof WildcardType) {
         ExtendsType extType = ExtendsType.create(sys, (WildcardType) typeDeclaration, ctx, definedInType);
         return extType;
      }
      if (typeDeclaration instanceof ExtendsType.LowerBoundsTypeDeclaration) {
         return ExtendsType.createSuper(sys, (ExtendsType.LowerBoundsTypeDeclaration) typeDeclaration, ctx, definedInType);
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
      return createFromTypeParams(ModelUtil.getTypeName(type), typeParams, type);
   }

   public static JavaType createFromTypeParams(String typeName, JavaType[] typeParams, Object typeDecl) {
      JavaType t = createJavaTypeFromName(typeName);
      if (t instanceof ClassType) {
         ClassType ct = (ClassType) t;
         
         if (typeParams != null)
            ct.setResolvedTypeArguments(SemanticNodeList.create((Object[]) typeParams));
         // This breaks the test/lambda with the -cc option for some reason - probably we need to use the parameterized type here if there are type params?
         //ct.type = typeDecl;
      }
      return t;
   }

   static int typeParamRecurseCheck = 0;

   public static JavaType createFromParamType(LayeredSystem sys, Object type, ITypeParamContext ctx, Object definedInType) {
      JavaType[] typeParamsArr = null;
      JavaType newType;
      Object typeCtx = ctx instanceof ITypeDeclaration ? (ITypeDeclaration) ctx : ctx != null ? ctx.getDefinedInType() : definedInType;

      if (ModelUtil.hasTypeParameters(type)) {
         SemanticNodeList<JavaType> typeParams = new SemanticNodeList<JavaType>();
         int numParams = ModelUtil.getNumTypeParameters(type);
         for (int i = 0; i < numParams; i++) {
            Object typeParam = ModelUtil.getTypeParameter(type, i);
            if (typeParam instanceof WildcardType) {
               newType = ExtendsType.create(sys, (WildcardType) typeParam, ctx, definedInType);
               if (!newType.isBound() && sys != null)
                  newType.initType(sys, typeCtx, null, ctx, true, false, typeParam);
               typeParam = newType;
            }
            else if (typeParam instanceof ExtendsType.WildcardTypeDeclaration) {
               newType = ExtendsType.createWildcard();
               typeParam = newType;
            }
            else if (typeParam instanceof ExtendsType.LowerBoundsTypeDeclaration) {
               ExtendsType.LowerBoundsTypeDeclaration td = (ExtendsType.LowerBoundsTypeDeclaration) typeParam;
               Object newTypeParam = null;
               Object baseType = td.getBaseType();
               if (ModelUtil.isTypeVariable(baseType)) {
                  newTypeParam = ctx != null ? ctx.getTypeForVariable(baseType, false) : null;
                  if (newTypeParam != null && !(newTypeParam instanceof ExtendsType.LowerBoundsTypeDeclaration))
                     typeParam = new ExtendsType.LowerBoundsTypeDeclaration(sys, newTypeParam);
               }
               else {
                  newType = ExtendsType.createSuper(sys, td, ctx, definedInType);
                  if (sys != null)
                     newType.initType(sys, typeCtx, null, ctx, true, false, typeParam);
                  typeParam = newType;
               }
            }
            else if (typeParam instanceof GenericArrayType) {
               GenericArrayType gat = (GenericArrayType) typeParam;
               java.lang.reflect.Type componentType = gat.getGenericComponentType();
               newType = createFromParamType(sys, componentType, ctx, definedInType);
               newType.setProperty("arrayDimensions", "[]");
               newType.initType(sys, typeCtx, null, ctx, true, false, newType.getTypeDeclaration());
               typeParam = newType;
            }
            else if (ModelUtil.isTypeVariable(typeParam)) {
               Object newTypeParam = ctx != null ? ctx.getTypeForVariable(typeParam, false) : null;
               if (newTypeParam != null && newTypeParam != type)
                  typeParam = newTypeParam;
            }
            if (typeParam instanceof JavaType) {
               JavaType javaType = (JavaType) typeParam;
               if (ctx != null)
                  javaType = javaType.resolveTypeParameters(ctx, false);
               typeParams.add(javaType);
            }
            else if (ModelUtil.hasTypeParameters(typeParam)) {
               if (typeParam == type)
                  System.out.println("*** Error!");
               else {
                  if (typeParamRecurseCheck == 100) {
                     System.err.println("*** Loop in type-parameters!");
                     return null;
                  }
                  else {
                     if (typeParamRecurseCheck == 97) {
                        System.out.println("*** Error infinite loop in type-parameter resolution!");
                     }
                     typeParamRecurseCheck++;
                     JavaType typeParamJavaType = createFromParamType(sys, typeParam, ctx, definedInType);
                     typeParamRecurseCheck--;
                     typeParams.add(typeParamJavaType);
                  }
               }
            }
            else if (typeParam instanceof WildcardType) {
               newType = ExtendsType.create(sys, (WildcardType) typeParam, ctx, definedInType);
               if (!newType.isBound())
                  newType.initType(ctx.getLayeredSystem(), typeCtx, null, ctx, true, false, typeParam);
               typeParams.add(newType);
            }
            else {
               String typeName = ModelUtil.getTypeName(typeParam);
               newType = ClassType.create(typeName);
               if (sys != null)
                  newType.initType(sys, typeCtx, null, ctx, true, false, typeParam);
               else
                  newType.initType(null, null, null, null, true, false, typeParam);
               typeParams.add(newType);
            }
         }
         typeParamsArr = typeParams.toArray(new JavaType[typeParams.size()]);
      }
      else if (type instanceof WildcardType) {
         newType = ExtendsType.create(sys, (WildcardType) type, ctx, definedInType);
         if (!newType.isBound() && sys != null)
            newType.initType(sys, typeCtx, null, ctx, true, false, null);
         return newType;
      }
      else if (type instanceof GenericArrayType || type instanceof ArrayTypeDeclaration) {
         StringBuilder arrDims = new StringBuilder();
         Object compType;
         do {
            compType = ModelUtil.getGenericComponentType(type);
            arrDims.append(ModelUtil.getArrayDimsStr(type));
            type = compType;
         } while (ModelUtil.isArray(compType));

         if (ModelUtil.isWildcardType(compType)) {
            newType = ExtendsType.createFromType(sys, compType, ctx, definedInType);
         }
         else {
            newType = ClassType.create(ModelUtil.getTypeName(compType));
         }
         newType.arrayDimensions = arrDims.toString();
         newType.initType(sys, typeCtx, null, ctx, true, false, compType);
         return newType;
      }
      else if (type instanceof ExtendsType.LowerBoundsTypeDeclaration) {
         newType = ExtendsType.createSuper(sys, (ExtendsType.LowerBoundsTypeDeclaration) type, ctx, definedInType);
         if (sys != null)
            newType.initType(sys, typeCtx, null, ctx, true, false, type);
         else
            newType.initType(null, null, null, ctx, true, false, type);
         return newType;
      }
      newType = createTypeFromTypeParams(type, typeParamsArr);
      if (sys != null) {
         newType.initType(sys, typeCtx, null, ctx, true, false, type);
      }
      else {
         newType.initType(null, null, null, null, true, false, type);
      }
      return newType;
   }

   /** Wraps primitive types in an object type wrapper for use in data binding where we only pass around objects (for now at least) */
   public static Object createObjectType(Object dstPropType) {
      if (dstPropType instanceof Class) {
         Class cl = (Class) dstPropType;
         if (cl.isPrimitive()) {
            return ClassType.createPrimitiveWrapper(cl.getName().replace('$', '.'));
         }
         return ClassType.create(StringUtil.split(cl.getName().replace('$', '.'), '.'));
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
         Object type = getTypeDeclaration(null, null, true, false, true, null, -1);
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

   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      Object typeDecl = getTypeDeclaration();
      if (typeDecl != null)
         addDependentType(types, typeDecl, mode);

      List<JavaType> typeArgs = getResolvedTypeArguments();
      if (typeArgs != null) {
         for (JavaType type:typeArgs)
            type.addDependentTypes(types, mode);
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

   public abstract boolean needsInit();

   abstract void startWithType(Object type);

   abstract Object definesTypeParameter(Object typeVar, ITypeParamContext ctx);

   public JavaType convertToArray(Object definedInType) {
      JavaType newType = (JavaType) this.deepCopy(SemanticNode.CopyNormal, null);
      newType.parentNode = parentNode;
      if (newType.arrayDimensions == null)
         newType.arrayDimensions = "[]";
      else
         newType.arrayDimensions += "[]";
      return newType;
   }

}
