/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ISemanticNode;
import sc.lang.SemanticNodeList;
import sc.layer.LayeredSystem;

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
   String toCompiledString(Object refType, boolean retNullForDynObj) {
      StringBuilder sb = new StringBuilder();

      String baseStr = baseType.toCompiledString(refType, retNullForDynObj);
      if (baseStr == null)
         return null;
      sb.append(baseStr);
      for (int i = 0; i < boundTypes.size(); i++) {
         JavaType t = boundTypes.get(i);
         sb.append(" & ");
         sb.append(t.toCompiledString(refType, false));
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

   public Object getTypeDeclaration(ITypeParamContext ctx, Object itd, boolean resolve, boolean refreshParams, boolean bindUnbound, Object baseType, int paramIx) {
      return getFirstType().getTypeDeclaration(ctx, itd, resolve, refreshParams, bindUnbound, baseType, paramIx);
   }

   public void initType(LayeredSystem sys, Object itd, JavaSemanticNode node, ITypeParamContext ctx, boolean displayError, boolean isLayer, Object typeParam) {
      getFirstType().initType(sys, itd, node, ctx, displayError, isLayer, typeParam);
   }

   public boolean convertToSrcReference() {
      return getFirstType().convertToSrcReference();
   }

   public String getBaseSignature() {
      StringBuilder sb = new StringBuilder();
      sb.append(baseType.getBaseSignature());
      for (int i = 0; i < boundTypes.size(); i++) {
         sb.append(":");
         sb.append(boundTypes.get(i).getSignature(false));
      }
      return sb.toString();
   }

   public boolean refreshBoundType(int flags) {
      boolean res = false;
      if (baseType != null)
         res = baseType.refreshBoundType(flags);
      if (boundTypes != null) {
         for (JavaType bt:boundTypes)
            if (bt.refreshBoundType(flags))
               res = true;
      }
      return res;
   }

   public String toGenerateString() {
      StringBuilder sb = new StringBuilder();
      if (baseType != null)
         sb.append(baseType.toGenerateString());
      if (boundTypes != null) {
         for (int i = 0; i < boundTypes.size(); i++) {
            JavaType t = boundTypes.get(i);
            if (i != 0)
               sb.append(" & ");
            else
               sb.append(" ");
            sb.append(t.toGenerateString());
         }
      }
      return sb.toString();
   }

   @Override
   public JavaType resolveTypeParameters(ITypeParamContext t, boolean bound) {
      if (!isParameterizedType())
         return this;

      JavaType newBaseType = baseType.resolveTypeParameters(t, bound);
      BoundType newRes = null;
      if (newBaseType != baseType) {
         newRes = new BoundType();
         newRes.setProperty("baseType", newBaseType);
         newRes.parentNode = parentNode;
      }

      SemanticNodeList<JavaType> newBoundTypes = null;
      for (int i = 0; i < boundTypes.size(); i++) {
         JavaType bt = boundTypes.get(i);
         JavaType newBt = bt.resolveTypeParameters(t, bound);
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

   public boolean needsInit() {
      if (baseType != null && baseType.needsInit())
         return true;

      if (boundTypes != null) {
         for (JavaType bt:boundTypes)
            if (bt.needsInit())
               return true;
      }
      return false;
   }

   @Override
   void startWithType(Object type) {
   }

   public boolean isParameterizedType() {
      return true;
   }

   public void setAccessTime(long time) {
      if (baseType != null)
         baseType.setAccessTime(time);
      if (boundTypes != null) {
         for (JavaType bt:boundTypes)
            bt.setAccessTime(time);
      }
   }

   public Object definesTypeParameter(Object typeParam, ITypeParamContext ctx) {
      if (baseType == null)
         return null;
      // TODO: anything else to do here?
      return baseType.definesTypeParameter(typeParam, ctx);
   }

   public boolean equalTypes(JavaType other) {
      if (!(other instanceof BoundType))
         return false;
      BoundType otherB = (BoundType) other;
      if (otherB.baseType == baseType)
         return true;
      if (baseType == null || otherB.baseType == null)
         return false;
      return baseType.equalTypes(otherB.baseType);
   }

}
