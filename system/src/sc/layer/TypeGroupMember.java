/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.lang.java.Expression;
import sc.lang.java.ModelUtil;
import sc.lang.java.TypeDeclaration;

public class TypeGroupMember {
   public String typeName;
   public String groupName;
   public String templatePathName;

   transient LayeredSystem system;
   transient Object type;
   transient boolean changed = false;

   public TypeGroupMember(String typeName, String groupName, String templatePathName) {
      this.typeName = typeName;
      this.groupName = groupName;
      this.templatePathName = templatePathName;
   }

   Object getType() {
      if (type == null) {
         // Using the runtime type here so that once we're compiled we don't load the src for things like the URLPaths in Element to be evaluated.
         type = system.getRuntimeTypeDeclaration(typeName);
         // Maybe null if this type has not been started yet
         if (type instanceof TypeDeclaration)
            ModelUtil.ensureStarted(type, true);
      }
      return type;
   }

   public Object getAnnotationValue(String annotationName, String annotationAttribute) {
      Object useType = getType();
      if (useType == null)
         return null;
      Object annotation = ModelUtil.getAnnotation(useType, annotationName);
      if (annotation == null)
         return null;
      return ModelUtil.getAnnotationValue(annotation, annotationAttribute);
   }

   public Object getAnnotationValue(String annotationName) {
      Object useType = getType();
      if (useType == null)
         return null;
      Object annotation = ModelUtil.getAnnotation(useType, annotationName);
      if (annotation == null)
         return null;
      Object v = ModelUtil.getAnnotationSingleValue(annotation);
      // TODO: clean this up.  
      if (v instanceof Expression)
         return ((Expression) v).eval(null, null);
      else
         return v;
   }

   public boolean hasAnnotation(String annotationName) {
      Object useType = getType();
      if (useType == null)
         return false;
      return ModelUtil.getAnnotation(useType, annotationName) != null;
   }

   public boolean isAbstract() {
      return ModelUtil.hasModifier(getType(), "abstract");
   }

   public boolean isObjectType() {
      return ModelUtil.isObjectType(getType());
   }

   public boolean isDynamicType() {
      return ModelUtil.isDynamicType(getType());
   }

   public String getProcessedFileName(String buildSrcDir) {
      Object useType = getType();
      if (useType instanceof TypeDeclaration) {
         TypeDeclaration td = (TypeDeclaration) useType;
         return td.getJavaModel().getProcessedFileName(buildSrcDir);
      }
      return null;
   }

   public String getClassFileName(String buildDir) {
      Object useType = getType();
      if (useType instanceof TypeDeclaration) {
         TypeDeclaration td = (TypeDeclaration) useType;
         return td.getJavaModel().getClassFileName(buildDir);
      }
      return null;
   }

   public boolean equals(Object other) {
      if (!(other instanceof TypeGroupMember))
         return false;

      TypeGroupMember otherMember = (TypeGroupMember) other;
      return otherMember.typeName.equals(typeName) &&
             otherMember.groupName.equals(groupName);
   }

   public int hashCode() {
      return typeName.hashCode() + groupName.hashCode();
   }

   public String toString() {
      return typeName;
   }
}
