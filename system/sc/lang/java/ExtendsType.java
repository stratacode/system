/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.layer.LayeredSystem;

import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;

public class ExtendsType extends JavaType {
   public boolean questionMark;
   public String operator;
   public JavaType typeArgument;

   public void setSignatureCode(String code) {
      questionMark = true;
      if (code.equals("+")) {
         operator = "extends";
      }
      else if (code.equals("-")) {
         operator = "super";
      }
      // else "*" means <?>
   }

   public String getSignatureCode() {
      if (operator == null)
         return "*";
      else if (operator.equals("extends"))
         return "+";
      else
         return "-";
   }

   public Class getRuntimeClass() {
      throw new UnsupportedOperationException("Extends type not allowed in runtime expressions");
   }

   public Object getRuntimeType() {
      throw new UnsupportedOperationException("Extends type not allowed in runtime expressions");
   }

   public String getFullTypeName() {
      throw new UnsupportedOperationException("Extends type not allowed in runtime expressions");
   }

   public String getAbsoluteTypeName() {
      throw new UnsupportedOperationException("Extends type not allowed in runtime expressions");
   }

   public Class getRuntimeBaseClass() {
      throw new UnsupportedOperationException("Extends type not allowed in runtime expressions");
   }

   public String getFullBaseTypeName() {
      throw new UnsupportedOperationException("Extends type not allowed in runtime expressions");
   }

   public boolean isVoid() {
      return false;
   }

   @Override
   String toCompiledString(Object refType) {
      StringBuilder sb = new StringBuilder();
      sb.append("?");
      if (operator != null && typeArgument != null) {
         sb.append(" ");
         sb.append(operator);
         sb.append(" ");
         if (typeArgument != null)
            sb.append(typeArgument.toCompiledString(refType));
      }
      return sb.toString();
   }

   public Object getTypeDeclaration(ITypeParamContext ctx) {
      if (typeArgument == null)
         return Object.class;
      return typeArgument.getTypeDeclaration(ctx);
   }

   public void initType(LayeredSystem sys, ITypeDeclaration itd, JavaSemanticNode node, ITypeParamContext ctx, boolean displayError, boolean isLayer) {
      typeArgument.initType(sys, itd, node, ctx, displayError, isLayer);
   }

   public String getBaseSignature() {
      return getSignatureCode() + (typeArgument == null ? "" : typeArgument.getSignature());
   }

   public void refreshBoundType() {
      if (typeArgument != null)
         typeArgument.refreshBoundType();
   }

   public String toGenerateString() {
      StringBuilder sb = new StringBuilder();
      sb.append("?");
      if (operator != null && typeArgument != null) {
         sb.append(" ");
         sb.append(operator);
         sb.append(" ");
         if (typeArgument != null)
            sb.append(typeArgument.toGenerateString());
      }
      return sb.toString();
   }

   public String toString() {
      return toGenerateString();
   }

   @Override
   public JavaType resolveTypeParameters(ITypeParamContext t) {
      JavaType newTypeArg = typeArgument.resolveTypeParameters(t);
      if (newTypeArg != typeArgument) {
         ExtendsType extType = new ExtendsType();
         extType.parentNode = parentNode;
         extType.operator = operator;
         extType.questionMark = questionMark;
         extType.setProperty("typeArgument", newTypeArg);
         return extType;
      }
      return this;
   }

   @Override
   void startWithType(Object type) {
   }

   public boolean isParameterizedType() {
      return true;
   }

   public static ExtendsType create(WildcardType type) {
      ExtendsType res = new ExtendsType();
      String typeName = type.toString(); // In Java8 We can use getTypeName() which does the same thing
      int opIx = typeName.indexOf("extends");
      if (opIx != -1) {
         res.operator = "extends";
      }
      else {
         opIx = typeName.indexOf("super");
         if (opIx != -1)
            res.operator = "super";
      }
      res.questionMark = typeName.contains("?");
      if (opIx != -1) {
         String extName = typeName.substring(opIx + 1 + res.operator.length());
         res.setProperty("typeArgument", ClassType.createJavaTypeFromName(extName));
      }
      return res;
   }

   public boolean isBound() {
      return typeArgument == null || typeArgument.isBound();
   }

   public Object definesTypeParameter(String typeParam, ITypeParamContext ctx) {
      if (typeArgument == null)
         return null;
      // TODO: anything else to do here?
      return typeArgument.definesTypeParameter(typeParam, ctx);
   }
}
