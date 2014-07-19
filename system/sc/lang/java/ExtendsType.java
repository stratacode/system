/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

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

   public Object getTypeDeclaration() {
      if (typeArgument == null)
         return Object.class;
      return typeArgument.getTypeDeclaration();
   }

   public void initType(ITypeDeclaration itd, JavaSemanticNode node, boolean displayError, boolean isLayer) {
      typeArgument.initType(itd, node, displayError, isLayer);
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
}
