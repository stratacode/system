/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

public class AnnotationTypeDeclaration extends TypeDeclaration
{
   public DeclarationType getDeclarationType() {
      return DeclarationType.ANNOTATION;
   }

   public void unregister() {
   }
}

