/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ISemanticNode;
import sc.lang.SemanticNodeList;
import sc.layer.LayeredSystem;

import java.lang.reflect.WildcardType;
import java.util.ArrayList;
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

   public Object getTypeDeclaration(ITypeParamContext ctx) {
      return getFirstType().getTypeDeclaration(ctx);
   }

   public void initType(LayeredSystem sys, ITypeDeclaration itd, JavaSemanticNode node, ITypeParamContext ctx, boolean displayError, boolean isLayer) {
      getFirstType().initType(sys, itd, node, ctx, displayError, isLayer);
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

   @Override
   public JavaType resolveTypeParameters(ITypeParamContext t) {
      if (!isParameterizedType())
         return this;

      JavaType newBaseType = baseType.resolveTypeParameters(t);
      BoundType newRes = null;
      if (newBaseType != baseType) {
         newRes = new BoundType();
         newRes.setProperty("baseType", newBaseType);
         newRes.parentNode = parentNode;
      }

      SemanticNodeList<JavaType> newBoundTypes = null;
      for (int i = 0; i < boundTypes.size(); i++) {
         JavaType bt = boundTypes.get(i);
         JavaType newBt = bt.resolveTypeParameters(t);
         if (newBt != bt) {
            if (newRes == null) {
               newRes = new BoundType();
               newRes.setProperty("baseType", newBaseType.deepCopy(ISemanticNode.CopyNormal, null));
               newRes.parentNode = parentNode;
            }
            if (newBoundTypes == null) {
               newBoundTypes = (SemanticNodeList<JavaType>) ((ArrayList) boundTypes).clone();
               newRes.setProperty("boundTypes", newBoundTypes);
            }
            newBoundTypes.set(i, newBt);
         }
      }
      return newRes != null ? newRes : this;
   }

   @Override
   public boolean isBound() {
      if (baseType != null && !baseType.isBound())
         return false;
      if (boundTypes != null) {
         for (JavaType bt:boundTypes)
            if (!bt.isBound())
               return false;
      }
      return true;
   }

   @Override
   void startWithType(Object type) {
   }

   public boolean isParameterizedType() {
      return true;
   }

   public Object definesTypeParameter(String typeParam, ITypeParamContext ctx) {
      if (baseType == null)
         return null;
      // TODO: anything else to do here?
      return baseType.definesTypeParameter(typeParam, ctx);
   }

}
