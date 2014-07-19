/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.List;

public class BoundType extends JavaType {
   public JavaType baseType;
   public List<JavaType> boundTypes;

   public Class getRuntimeClass() {
      return getFirstType().getRuntimeClass();
   }

   public Object getRuntimeType() {
      return getFirstType().getRuntimeType();
   }

   public String getFullTypeName() {
      return getFirstType().getFullTypeName();
   }

   public String getAbsoluteTypeName() {
      return getFirstType().getAbsoluteTypeName();
   }

   public Class getRuntimeBaseClass() {
      return getFirstType().getRuntimeBaseClass();
   }

   public String getFullBaseTypeName() {
      return getFirstType().getFullBaseTypeName();
   }

   public boolean isVoid() {
      return false;
   }

   @Override
   String toCompiledString(Object refType) {
      StringBuilder sb = new StringBuilder();
      sb.append(baseType.toCompiledString(refType));
      for (int i = 0; i < boundTypes.size(); i++) {
         JavaType t = boundTypes.get(i);
         sb.append(" & ");
         sb.append(t.toCompiledString(refType));
      }
      return sb.toString();
   }

   // TODO: this is not very clean... The SignatureLanguage puts the list of types into boundTypes but JavaLanguage
   // puts the first (required) value into the baseType.
   public JavaType getFirstType() {
      if (baseType == null) {
         return boundTypes.get(0);
      }
      return baseType;
   }

   public Object getTypeDeclaration() {
      return getFirstType().getTypeDeclaration();
   }

   public void initType(ITypeDeclaration itd, JavaSemanticNode node, boolean displayError, boolean isLayer) {
      getFirstType().initType(itd, node, displayError, isLayer);
   }

   public String getBaseSignature() {
      StringBuilder sb = new StringBuilder();
      sb.append(baseType.getBaseSignature());
      for (int i = 0; i < boundTypes.size(); i++) {
         sb.append(":");
         sb.append(boundTypes.get(i).getSignature());
      }
      return sb.toString();
   }

   public void refreshBoundType() {
      if (baseType != null)
         baseType.refreshBoundType();
      if (boundTypes != null) {
         for (JavaType bt:boundTypes)
            bt.refreshBoundType();
      }
   }

   public String toGenerateString() {
      StringBuilder sb = new StringBuilder();
      sb.append(baseType.toGenerateString());
      for (int i = 0; i < boundTypes.size(); i++) {
         JavaType t = boundTypes.get(i);
         sb.append(" & ");
         sb.append(t.toGenerateString());
      }
      return sb.toString();
   }
}
