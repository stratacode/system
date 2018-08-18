/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.List;

public interface IMethodDefinition extends IMember {
   String getMethodName();

   Object getDeclaringType();

   Object getReturnType(boolean boundTypeParams);

   Object getReturnJavaType();

   Object[] getParameterTypes(boolean bindTypeVars);

   JavaType[] getParameterJavaTypes(boolean convertRepeating);

   Object getTypeDeclaration(List<? extends ITypedObject> args, boolean resolve);

   String getPropertyName();

   boolean hasGetMethod();

   boolean hasSetMethod();

   Object getGetMethodFromSet();

   Object getSetMethodFromGet();

   Object getFieldFromGetSetMethod();

   boolean isGetMethod();

   boolean isSetMethod();

   boolean isGetIndexMethod();

   boolean isSetIndexMethod();

   boolean isVarArgs();

   boolean isConstructor();

   String getTypeSignature();

   /** Descriptive view of the parameters with parens - i.e. () for no params or (int foo) */
   String getParameterString();

   Object[] getExceptionTypes();

   String getThrowsClause();

   Object[] getMethodTypeParameters();

}
