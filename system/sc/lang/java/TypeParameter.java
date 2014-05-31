/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.List;
import java.util.Set;

public class TypeParameter extends JavaSemanticNode implements ITypedObject {
   public String name;
   public JavaType extendsType;

   public static TypeParameter create(String name) {
      TypeParameter tp = new TypeParameter();
      tp.name = name;
      return tp;
   }

   public Object getTypeDeclaration() {
      if (extendsType == null)
         return Object.class;
      return extendsType.getTypeDeclaration();
   }

   public String getGenericTypeName(Object resultType, boolean includeDims) {
      if (includeDims)
         return ModelUtil.getTypeName(getTypeDeclaration());
      else
         return ModelUtil.getFullBaseTypeName(getTypeDeclaration());
   }

   public String getAbsoluteGenericTypeName(Object resultType, boolean includeDims) {
      if (includeDims)
         return ModelUtil.getTypeName(getTypeDeclaration());
      else
         return ModelUtil.getFullBaseTypeName(getTypeDeclaration());
   }

   public int getPosition() {
      return ((List)parentNode).indexOf(this);
   }

   public boolean isAssignableFrom(Object otherType, ITypeParamContext ctx) {
      Object extType;
      if (otherType == this)
         return true;
      if (extendsType != null && (extType = extendsType.getTypeDeclaration()) != null &&
          !ModelUtil.isAssignableFrom(extType, otherType, false, ctx))
         return false;
      if (ctx != null) {
         Object thisType = ctx.getDefaultType(this.getPosition());
         if (thisType == this) {
            System.out.println("*** Error! recursive reference in isAssignableFrom.");
            return false;
         }
         if (thisType != null)
            return ModelUtil.isAssignableFrom(thisType, otherType, false, ctx);
      }
      return ModelUtil.isAssignableFrom(Object.class, otherType);
   }

   public boolean isAssignableTo(Object otherType, ITypeParamContext ctx) {
      if (ctx != null) {
         Object thisType = ctx.getType(this.getPosition());

         // No binding for this type parameter... see if the
         if (thisType == this)
            return ModelUtil.isAssignableFrom(otherType, Object.class);
         if (thisType != null)
            return ModelUtil.isAssignableFrom(otherType, thisType, false, ctx);
      }
      return otherType == this || ModelUtil.isAssignableFrom(Object.class, otherType);
   }

   public void refreshBoundType() {
      if (extendsType != null)
         extendsType.refreshBoundType();
   }

   public void addDependentTypes(Set<Object> types) {
      if (extendsType != null)
         extendsType.addDependentTypes(types);
   }
}
