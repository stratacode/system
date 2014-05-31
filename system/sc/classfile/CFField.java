/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.classfile;

import sc.lang.java.IVariable;
import sc.lang.java.IFieldDefinition;
import sc.lang.java.JavaType;

public class CFField extends ClassFile.FieldMethodInfo implements IVariable, IFieldDefinition {
   public JavaType type;

   public void start() {
      String fieldDesc = getDescription();
      parentNode = ownerClass;
      type = SignatureLanguage.getSignatureLanguage().parseType(fieldDesc);
      type.parentNode = this;
      type.start();

      super.start();
   }

   public String getVariableName() {
      return name;
   }

   public Object getTypeDeclaration() {
      if (!started)
        start();
      return type.getTypeDeclaration();
   }

   public String getGenericTypeName(Object resultType, boolean includeDims) {
      return type.getGenericTypeName(resultType, includeDims);
   }

   public String getAbsoluteGenericTypeName(Object resultType, boolean includeDims) {
      return type.getAbsoluteGenericTypeName(resultType, includeDims);
   }

   public boolean isEnumConstant() {
      return ownerClass.isEnum();
   }

   public String toString() {
      return name;
   }

   public String getFieldName() {
      return name;
   }

   public Object getFieldType() {
      return getTypeDeclaration();
   }

   public String getOperator() {
      return "=";
   }
}
