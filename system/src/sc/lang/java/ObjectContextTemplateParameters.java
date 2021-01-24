/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.type.CTypeUtil;
import sc.type.TypeUtil;

public class ObjectContextTemplateParameters {
   public String typeName;               // The full class name being instantiate with parameters
   public String typeBaseName;           // Excludes the package part of the typeName
   public String typeClassName;          // Excludes type parameters
   public String variableTypeName;       // Type of the variable (often typeName)
   public String lowerClassName;         // The lower class version of the identifier name
   public String upperClassName;         // The upper class version of the identifier name

   private TypeDeclaration objectType;

   public ObjectContextTemplateParameters(String objectClassName, TypeDeclaration objType) {
      typeName = objectClassName;
      typeBaseName = CTypeUtil.getClassName(objectClassName);
      typeClassName = TypeUtil.stripTypeParameters(objectClassName);
      String propName = objType.typeName; // Allow this to be either upper or lower, but we still genenerate
      objectType = objType;
      upperClassName = CTypeUtil.capitalizePropertyName(propName);
      lowerClassName = CTypeUtil.decapitalizePropertyName(propName);
   }

   public boolean isAssignableFrom(String className) {
      Object otherType = objectType.getJavaModel().findTypeDeclaration(className, false);
      if (otherType == null)
         throw new IllegalArgumentException("No type named: " + className + " for template isAssignableFrom");
      return ModelUtil.isAssignableFrom(objectType, otherType);
   }

   public boolean isAssignableTo(String className) {
      Object otherType = objectType.getJavaModel().findTypeDeclaration(className, false);
      if (otherType == null)
         throw new IllegalArgumentException("No type named: " + className + " for template isAssignableFrom");
      return ModelUtil.isAssignableFrom(otherType, objectType);
   }
}
