/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.layer.LayeredSystem;

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

   public Object getTypeDeclaration(ITypeParamContext ctx, ITypeDeclaration definedInType, boolean resolve, boolean refreshParams, boolean bindUnbound) {
      if (typeArgument == null)
         return resolve ? Object.class : new WildcardTypeDeclaration();
      if (operator != null && operator.equals("super")) {
         Object typeDecl = typeArgument.getTypeDeclaration(ctx, definedInType, resolve, refreshParams, bindUnbound);
         if (typeDecl == null)
            return bindUnbound ? Object.class : null;
         if (typeDecl instanceof LowerBoundsTypeDeclaration)
            return typeDecl;
         return new LowerBoundsTypeDeclaration(typeDecl);
      }
      return typeArgument.getTypeDeclaration(ctx, definedInType, resolve, refreshParams, bindUnbound);
   }

   /** Represents a ? super X type */
   public static class LowerBoundsTypeDeclaration extends WrappedTypeDeclaration {
      LowerBoundsTypeDeclaration(Object lbType) {
         super(lbType);
         if (lbType instanceof LowerBoundsTypeDeclaration)
            System.err.println("*** Error - nested lower bounds type");
      }

      @Override
      public boolean isAssignableFrom(ITypeDeclaration other, boolean assignmentSemantics) {
         // ? super X = other
         if (baseType == null)
            return ModelUtil.isAssignableFrom(Object.class, other, assignmentSemantics, null, false, null);
         if (other instanceof LowerBoundsTypeDeclaration) {
            Object otherObj = ((LowerBoundsTypeDeclaration) other).baseType;
            if (otherObj == null)
               return true;
            return ModelUtil.isAssignableFrom(this, otherObj, assignmentSemantics, null, false, null);
         }
         // If we have ? super T  changing that to ? super Object does not work which is what happens in the reverse
         // assignment.  This should always match, or at least always match all objects.
         if (ModelUtil.isTypeVariable(baseType)) {
            if (!ModelUtil.isAssignableFrom(Object.class, other))
               System.out.println("*** Warning - unresolved code path for ? super T");
            return true;
         }
         // This switches the directions intentionally because the super construct matches the same type or base-types of that type
         return ModelUtil.isAssignableFrom(other, baseType, assignmentSemantics, null, false, null);
      }

      @Override
      public boolean isAssignableTo(ITypeDeclaration other) {
         if (baseType == null)
            return ModelUtil.isAssignableFrom(other, Object.class);
         if (other instanceof LowerBoundsTypeDeclaration) {
            Object otherObj = ((LowerBoundsTypeDeclaration) other).baseType;
            if (otherObj == null)
               return true;
            return ModelUtil.isAssignableFrom(otherObj, this, false, null, false, null);
         }
         return ModelUtil.isAssignableFrom(baseType, other);
      }

      @Override
      public boolean isAssignableFromClass(Class other) {
         if (baseType == null)
            return ModelUtil.isAssignableFrom(Object.class, other);
         return ModelUtil.isAssignableFrom(other, baseType);
      }

      @Override
      public Object getRuntimeType() {
         return this;
      }

      public Object resolveTypeVariables(ITypeParamContext ctx, boolean resolve) {
         if (baseType != null && ModelUtil.isTypeVariable(baseType)) {
            Object newBase = ctx.getTypeDeclarationForParam(ModelUtil.getTypeParameterName(baseType), baseType, resolve);
            if (!(newBase instanceof WildcardTypeDeclaration))
               return new LowerBoundsTypeDeclaration(newBase);
         }
         return this;
      }

      // Given an ExtendsType or WildcardType - create the LowerBoundsTypeDeclaration that does the appropriate type matching for a super
      public static Object createFromType(Object type) {
         return new LowerBoundsTypeDeclaration(ModelUtil.getWildcardLowerBounds(type));
      }

      public String toString() {
         if (baseType == null)
            return "?";
         return "? super " + String.valueOf(baseType);
      }
   }

   public static class WildcardTypeDeclaration extends LowerBoundsTypeDeclaration {
      WildcardTypeDeclaration() {
         super(Object.class);
      }
      public String getFullTypeName(boolean includeDims, boolean includeTypeParams) {
         return "?";
      }

      public boolean isAssignableFrom(ITypeDeclaration other, boolean assignmentSemantics) {
         return true;
      }

      @Override
      public boolean isAssignableTo(ITypeDeclaration other) {
         return true;
      }

      @Override
      public boolean isAssignableFromClass(Class other) {
         return true;
      }

      public boolean equals(Object obj) {
         return obj instanceof WildcardTypeDeclaration;
      }

      public String toString() {
         return "?";
      }
   }

   public void initType(LayeredSystem sys, ITypeDeclaration itd, JavaSemanticNode node, ITypeParamContext ctx, boolean displayError, boolean isLayer, Object typeParam) {
      if (typeArgument != null)
         typeArgument.initType(sys, itd, node, ctx, displayError, isLayer, typeParam);
   }

   public void convertToSrcReference() {
      if (typeArgument != null)
         typeArgument.convertToSrcReference();
   }

   public String getBaseSignature() {
      return getSignatureCode() + (typeArgument == null ? "" : typeArgument.getSignature(false));
   }

   public void refreshBoundType(int flags) {
      if (typeArgument != null)
         typeArgument.refreshBoundType(flags);
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
   public JavaType resolveTypeParameters(ITypeParamContext t, boolean resolveUnbound) {
      if (typeArgument == null)
         return this;
      JavaType newTypeArg = typeArgument.resolveTypeParameters(t, resolveUnbound);
      // This LowerBounds marker will already create the extends type so just return that
      if (typeArgument instanceof ClassType && ((ClassType) typeArgument).type instanceof LowerBoundsTypeDeclaration)
         return newTypeArg;
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

   public static ExtendsType createFromType(Object type, ITypeParamContext ctx, ITypeDeclaration definedInType) {
      if (type instanceof WildcardType)
         return create((WildcardType) type);
      else if (type instanceof LowerBoundsTypeDeclaration) {
         return createSuper((LowerBoundsTypeDeclaration) type, ctx, definedInType);
      }
      else
         throw new UnsupportedOperationException();
   }

   public static ExtendsType create(WildcardType type) {
      ExtendsType res = new ExtendsType();
      String typeName = type.toString(); // In Java8 We can use getTypeName() which does the same thing
      int opIx = typeName.indexOf("extends");
      boolean isSuper = false;
      if (opIx != -1) {
         res.operator = "extends";
      }
      else {
         opIx = typeName.indexOf("super");
         if (opIx != -1) {
            isSuper = true;
            res.operator = "super";
         }
      }
      res.questionMark = typeName.contains("?");
      if (opIx != -1) {
         String extName = typeName.substring(opIx + 1 + res.operator.length());
         ClassType argType = (ClassType) ClassType.createJavaTypeFromName(extName);
         // Need to bind this here to it's original value so we can track what type this paramter
         // is defined with.
         argType.type = isSuper ? type.getLowerBounds()[0] : type.getUpperBounds()[0];
         res.setProperty("typeArgument", argType);
      }
      return res;
   }

   public static ExtendsType createSuper(LowerBoundsTypeDeclaration argType, ITypeParamContext ctx, ITypeDeclaration definedInType) {
      ExtendsType res = new ExtendsType();
      res.operator = "super";
      res.questionMark = true;

      if (argType.baseType != null) {
         Object baseType = JavaType.createFromParamType(argType.baseType, ctx, definedInType);
         res.setProperty("typeArgument", baseType);
      }
      return res;
   }

   public boolean isBound() {
      return typeArgument == null || typeArgument.isBound();
   }

   public Object definesTypeParameter(Object typeParam, ITypeParamContext ctx) {
      if (typeArgument == null)
         return null;
      // TODO: anything else to do here?
      return typeArgument.definesTypeParameter(typeParam, ctx);
   }
}
