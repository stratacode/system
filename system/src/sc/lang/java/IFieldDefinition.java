/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

public interface IFieldDefinition extends IMember, ITypedObject {
   public String getFieldName();

   public Object getFieldType();
}
