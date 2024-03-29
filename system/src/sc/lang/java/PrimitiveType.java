/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.layer.LayeredSystem;
import sc.type.Type;
import sc.util.StringUtil;

public class PrimitiveType extends JavaType {
   public String typeName;

   public String toString() {
      return typeName + (arrayDimensions == null ? "" : arrayDimensions);
   }

   public static PrimitiveType create(String typeName) {
      PrimitiveType res = new PrimitiveType();
      res.typeName = typeName;
      return res;
   }

   public static PrimitiveType createArray(String typeName, int ndim) {
      PrimitiveType res = new PrimitiveType();
      res.typeName = typeName;
      res.arrayDimensions = getDimsStr(ndim);
      return res;
   }

   public Class getRuntimeBaseClass() {
      if (typeName == null) {
         System.out.println("*** Error - invalid primitive type");
         return null;
      }
      char c = typeName.charAt(0);

      Class base;

      switch (c) {
         case 'b':
            if (typeName.equals("byte"))
               base = Byte.TYPE;
            else
               base = Boolean.TYPE;
            break;
         case 's':
            base = Short.TYPE;
            break;
         case 'c':
            base = Character.TYPE;
            break;
         case 'i':
            base = Integer.TYPE;
            break;
         case 'f':
            base = Float.TYPE;
            break;
         case 'l':
            base = Long.TYPE;
            break;
         case 'd':
            base = Double.TYPE;
            break;
         case 'v':
            base = Void.TYPE;
            break;
         default:
            throw new UnsupportedOperationException();
      }

      return base;
   }

   public Class getRuntimeClass() {
      Class base = getRuntimeBaseClass();

      if (arrayDimensions == null)
         return base;

      return Type.get(base).getPrimitiveArrayClass(arrayDimensions.length() >> 1);
   }

   public Object getRuntimeType() {
      return getRuntimeClass();
   }

   public String getFullTypeName() {
      return getRuntimeClass().getName();
   }

   public String getAbsoluteTypeName() {
      return getFullTypeName();
   }

   public String getFullBaseTypeName() {
      return getRuntimeBaseClass().getName();
   }

   public boolean isVoid() {
      return typeName.charAt(0) == 'v';
   }

   @Override
   String toCompiledString(Object refType, boolean retNullForDynObj) {
      return typeName;
   }

   public Object getTypeDeclaration(ITypeParamContext ctx, Object dit, boolean resolve, boolean refreshParams, boolean bindUnbound, Object baseType, int paramIx) {
      return getRuntimeClass();
   }

   public void initType(LayeredSystem sys, Object itd, JavaSemanticNode node, ITypeParamContext ctx, boolean displayError, boolean isLayer, Object typeParam) {}

   public boolean convertToSrcReference() { return false; }

   public String getBaseSignature() {
      return getSignatureCode();
   }

   public void setSignatureCode(String code) {
      if (code.equals("V"))
         typeName = "void";
      else
         typeName = Type.getArrayType(code).primitiveClass.getName();
   }

   public String getSignatureCode() {
      if (isVoid())
         return "V";
      if (typeName != null)
         return String.valueOf(Type.getPrimitiveType(typeName).arrayTypeCode);
      return null;
   }

   public boolean refreshBoundType(int flags) {
      return false;
   }

   public void setAccessTime(long time) {}

   public void transformToJS() {
      parentNode.replaceChild(this, ClassType.createStarted(Object.class, "var"));
   }

   @Override
   public String toGenerateString() {
      return typeName + (arrayDimensions == null ? "" : arrayDimensions);
   }

   @Override
   public JavaType resolveTypeParameters(ITypeParamContext t, boolean resolveUnbound) {
      return this;
   }

   @Override
   void startWithType(Object type) {
   }

   public String getAbsoluteBaseTypeName() {
      return getFullBaseTypeName();
   }

   public boolean isParameterizedType() {
      return false;
   }

   public boolean isBound() {
      return true;
   }

   public boolean needsInit() {
      return false;
   }

   public Object definesTypeParameter(Object typeParamName, ITypeParamContext ctx) {
      return null;
   }

   public boolean equalTypes(JavaType other) {
      return (other instanceof PrimitiveType) && StringUtil.equalStrings(((PrimitiveType) other).typeName, typeName);
   }
}
