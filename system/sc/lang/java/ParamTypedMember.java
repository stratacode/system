/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.dyn.DynUtil;

public class ParamTypedMember implements ITypedObject, IDefinition {
   Object member;
   ITypeParamContext paramTypeDecl;
   JavaSemanticNode.MemberType mtype;

   public ParamTypedMember(Object mem, ITypeParamContext paramTypeDeclaration, JavaSemanticNode.MemberType mt) {
      // No need to nest ParamTypedMembers (I don't think).  Several places in the code only unwrap them one level but that
      // would be easy to change.
      if (mem instanceof ParamTypedMember)
         mem = ((ParamTypedMember) mem).member;
      member = mem;
      paramTypeDecl = paramTypeDeclaration;
      mtype = mt;
   }

   public Object getTypeDeclaration() {
      Object parameterizedType = ModelUtil.getParameterizedType(member, mtype);
      if (ModelUtil.isTypeVariable(parameterizedType)) {
         return paramTypeDecl.getTypeForVariable(parameterizedType, true);
      }
      return parameterizedType;
   }

   public String getGenericTypeName(Object resultType, boolean includeDims) {
      return ModelUtil.getTypeName(member, includeDims);
   }

   public String getAbsoluteGenericTypeName(Object resultType, boolean includeDims) {
      return getGenericTypeName(resultType, includeDims);
   }

   public Object getMemberObject() {
      return member;
   }

   public Object getAnnotation(String annotName) {
      return ModelUtil.getAnnotation(member, annotName);
   }

   public boolean hasModifier(String modifierName) {
      return ModelUtil.hasModifier(member, modifierName);
   }

   public AccessLevel getAccessLevel(boolean explicitOnly) {
      return ModelUtil.getAccessLevel(member, explicitOnly);
   }

   public Object getEnclosingIType() {
      return ModelUtil.getEnclosingType(member);
   }

   public String modifiersToString(boolean includeAnnotations, boolean includeAccess, boolean includeFinal, boolean includeScope, boolean abs, JavaSemanticNode.MemberType filterType) {
      return ModelUtil.modifiersToString(member, includeAnnotations, includeAccess, includeFinal, includeScope, abs, filterType);
   }

   public String toString() {
      return member.toString();
   }

   public boolean equals(Object other) {
      if (!(other instanceof ParamTypedMember))
         return false;
      ParamTypedMember opm = (ParamTypedMember) other;
      return DynUtil.equalObjects(opm.member, member) && DynUtil.equalObjects(opm.paramTypeDecl, paramTypeDecl) && opm.mtype == mtype;
   }
}
