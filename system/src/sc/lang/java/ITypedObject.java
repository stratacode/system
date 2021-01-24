/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

public interface ITypedObject {
   // Returns the type as either a Class or a TypeDeclaration, or an ArrayTypeDeclaration
   abstract Object getTypeDeclaration();

   abstract String getGenericTypeName(Object resultType, boolean includeDims);

   abstract String getAbsoluteGenericTypeName(Object resultType, boolean includeDims);
}
