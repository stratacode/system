/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.List;

public interface IMethodDefinition extends IMember {
   String getMethodName();

   Object getDeclaringType();

   Object getReturnType();

   Object getReturnJavaType();

   Object[] getParameterTypes();

   JavaType[] getParameterJavaTypes();

   Object getTypeDeclaration(List<? extends ITypedObject> args);

   String getPropertyName();

   boolean hasGetMethod();

   boolean hasSetMethod();

   Object getGetMethodFromSet();

   Object getSetMethodFromGet();

   boolean isGetMethod();

   boolean isSetMethod();

   boolean isGetIndexMethod();

   boolean isSetIndexMethod();

   boolean isVarArgs();

   String getTypeSignature();

   Object[] getExceptionTypes();

   String getThrowsClause();
}
