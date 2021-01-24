/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

public interface IFieldDefinition extends IMember, ITypedObject {
   public String getFieldName();

   public Object getFieldType();

   public JavaType getJavaType();
}
