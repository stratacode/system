/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

public class AnnotationTypeDeclaration extends TypeDeclaration {
   public DeclarationType getDeclarationType() {
      return DeclarationType.ANNOTATION;
   }

   public void unregister() {
   }

   public AccessLevel getDefaultAccessLevel() {
      return AccessLevel.Public;
   }

   public boolean implementsType(String fullTypeName, boolean assignment, boolean allowUnbound) {
      if (super.implementsType(fullTypeName, assignment, allowUnbound))
         return true;
      // This is the interface implicity implemented by all annotation types.  It is necessary that we implement this interface for the type
      // system to resolve for example:   field.getAnnotation(AnnotationClass.class).annotationMethod()
      if (fullTypeName.equals("java.lang.annotation.Annotation"))
         return true;
      return false;
   }

   /** All annotation classes implicitly inherit from java.lang.annotation.Annotation the way Object's by default extend Object.class */
   public Object getDerivedTypeDeclaration() {
      return java.lang.annotation.Annotation.class;
   }
}

