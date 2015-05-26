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
         Object thisType = ctx.getTypeForVariable(this, true);
         if (thisType == this) {
            thisType = ModelUtil.getTypeParameterDefault(this);
         }
         if (thisType != null)
            return ModelUtil.isAssignableFrom(thisType, otherType, false, ctx);
      }
      // Type parameters can match anything right?  For primitive types, they are automatically boxed to the object type.  Should we just return true here?
      return ModelUtil.isAssignableFrom(Object.class, otherType) || ModelUtil.isPrimitive(otherType);
   }

   static int count = 0;

   public boolean isAssignableTo(Object otherType, ITypeParamContext ctx) {
      if (ctx != null) {
         Object thisType = ctx.getTypeForVariable(this, true);

         // No binding for this type parameter... see if the
         if (thisType == this)
            return ModelUtil.isAssignableFrom(otherType, Object.class);
         if (thisType != null) {
            if (ModelUtil.isTypeVariable(thisType)) {
               Object thisDefault = ctx.getTypeDeclarationForParam(ModelUtil.getTypeParameterName(thisType), thisType, true);
               if (thisDefault == null || ModelUtil.isTypeVariable(thisDefault))
                  return true;
            }
            if (count > 10)
               System.out.println("***");
            try {
               count++;
               return ModelUtil.isAssignableFrom(otherType, thisType, false, ctx);
            }
            finally {
               count--;
            }
         }
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

   public Object getGenericDeclaration() {
      Object def = getEnclosingMethod();
      if (def != null)
         return def;
      return getEnclosingType();
   }

   public String toGenerateString() {
      StringBuilder sb = new StringBuilder();
      if (name != null)
         sb.append(name);
      else
         sb.append("<null-type-parameter>");
      if (extendsType != null) {
         sb.append("? extends ");
         sb.append(extendsType.toGenerateString());
      }
      return sb.toString();
   }

   public String toString() {
      return toGenerateString();
   }
}
