/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.classfile;

import sc.type.PropertyMethodType;
import sc.type.RTypeUtil;
import sc.lang.java.*;

import java.lang.reflect.Method;
import java.util.List;

/** A Method definition that is extracted directly from the class file representation */
public class CFMethod extends ClassFile.FieldMethodInfo implements IVariable, IMethodDefinition {
   public JavaType returnType;
   public JavaType[] parameterJavaTypes;
   public Object[] parameterTypes;
   public List<TypeParameter> typeParameters;
   public String typeSignature;

   public PropertyMethodType propertyMethodType;
   String propertyName;

   public void init() {
      if (initialized)
         return;

      parentNode = ownerClass;

      String methodDesc = getDescription();

      typeSignature = methodDesc; // TODO: not 100% this is defined the same in getTypeSignature as in the class format
      CFMethodSignature methodSig = SignatureLanguage.getSignatureLanguage().parseMethodSignature(methodDesc);
      methodSig.parentNode = this;
      returnType = methodSig.returnType;
      typeParameters = methodSig.typeParameters;
      List<JavaType> ptypes = methodSig.parameterTypes;
      parameterJavaTypes = ptypes == null ? null : ptypes.toArray(new JavaType[ptypes.size()]);

      /** Note: this needs to be done in initialize so we can do resolves without accessing the property name */
      if ((propertyName = ModelUtil.isGetMethod(name, parameterJavaTypes, returnType)) != null)
         propertyMethodType = propertyName.startsWith("i") ? PropertyMethodType.Is : PropertyMethodType.Get;
      else if ((propertyName = ModelUtil.isSetMethod(name, parameterJavaTypes, returnType)) != null)
         propertyMethodType = PropertyMethodType.Set;
      else if ((propertyName = ModelUtil.isGetIndexMethod(name, parameterJavaTypes, returnType)) != null)
         propertyMethodType = PropertyMethodType.GetIndexed;
      else if ((propertyName = ModelUtil.isSetIndexMethod(name, parameterJavaTypes, returnType)) != null)
         propertyMethodType = PropertyMethodType.SetIndexed;

      super.init();
   }

   public void start() {
      if (started)
         return;

      if (!initialized)
         init();

      if (parameterJavaTypes != null) {
         parameterTypes = new Object[parameterJavaTypes.length];
         for (int i = 0; i < parameterJavaTypes.length; i++) {
            parameterTypes[i] = parameterJavaTypes[i].getTypeDeclaration();
         }
      }

      super.start();
   }

   public Object findType(String typeName, Object refType, TypeContext ctx) {
      if (!initialized)
         init();
      if (typeParameters != null)
         for (TypeParameter tp:typeParameters)
            if (tp.name.equals(typeName))
               return tp;

      // CFClass overrides findTypeDeclaration
      return null;
   }

   public String getMethodName() {
      return name;
   }

   public Object getDeclaringType() {
      return ownerClass;
   }

   public Object getReturnType() {
      if (!started)
         start();
      if (returnType == null)
         return null;
      return returnType.getTypeDeclaration();
   }

   public Object getReturnJavaType() {
      return getReturnType();
   }

   public Object[] getParameterTypes(boolean bound) {
      if (!started)
         start();
      return parameterTypes;
   }

   public JavaType[] getParameterJavaTypes() {
      return parameterJavaTypes;
   }

   public Object getTypeDeclaration(List<? extends ITypedObject> args, boolean resolve) {
      // TODO: get type parameters and return the parameterized type here if the match
      return getReturnType();
   }

   public String getPropertyName() {
      return propertyName;
   }
   
   public boolean hasGetMethod() {
      return isGetMethod() ||
              ModelUtil.definesMember(ownerClass, propertyName, JavaSemanticNode.MemberType.GetMethodSet, null, null) != null;
   }

   public boolean hasSetMethod() {
      return isSetMethod() ||
              ModelUtil.definesMember(ownerClass, propertyName, JavaSemanticNode.MemberType.SetMethodSet, null, null) != null;
   }

   public Object getSetMethodFromGet() {
      if (isGetMethod())
        return ModelUtil.definesMember(ownerClass, propertyName, JavaSemanticNode.MemberType.SetMethodSet, null, null);
      return null;
   }

   public Object getGetMethodFromSet() {
      if (isSetMethod())
        return ModelUtil.definesMember(ownerClass, propertyName, JavaSemanticNode.MemberType.GetMethodSet, null, null);
      return null;
   }

   public boolean isGetMethod() {
      return propertyName != null && (name.startsWith("get") || name.startsWith("is"));
   }

   public boolean isSetMethod() {
      return propertyName != null && name.startsWith("set");
   }

   public boolean isGetIndexMethod() {
      return propertyMethodType == PropertyMethodType.GetIndexed;
   }

   public boolean isSetIndexMethod() {
      return propertyMethodType == PropertyMethodType.SetIndexed;
   }

   // Not public in Modifier class
   private final int ACC_VARARGS = 128;

   public boolean isVarArgs() {
      return (accessFlags & ACC_VARARGS) != 0;
   }

   /** Returns only the signature of the parameters of the method - strips off the ( ) and return value - e.g. (I)V becomes I */
   public String getTypeSignature() {
      if (typeSignature == null)
         return null;
      int closeIx = typeSignature.indexOf(")");
      if (closeIx == -1)
         return typeSignature; // Not reached
      return typeSignature.substring(1, closeIx);
   }

   public JavaType[] getExceptionTypes() {
      ClassFile.ExceptionsAttribute att = (ClassFile.ExceptionsAttribute) attributes.get("Exceptions");
      if (att == null || att.typeNames == null)
         return null;
      String[] tns = att.typeNames;
      JavaType[] types = new JavaType[tns.length];
      for (int i = 0; i < tns.length; i++) {
         types[i] = JavaType.createJavaType(tns[i]);
      }
      return types;
   }

   public String getThrowsClause() {
      return ModelUtil.typesToThrowsClause(getExceptionTypes());
   }

   public Object[] getMethodTypeParameters() {
      return parameterJavaTypes;
   }

   public String toString() {
      if (name != null)
         return name + "()";
      return super.toString();
   }

   public String getVariableName() {
      return propertyName;
   }

   public Object getTypeDeclaration() {
      if (returnType == null)
         return null;
      return returnType.getTypeDeclaration();
   }

   public String getGenericTypeName(Object resultType, boolean includeDims) {
      if (returnType == null)
         return null;
      return returnType.getGenericTypeName(resultType, includeDims);
   }

   public String getAbsoluteGenericTypeName(Object resultType, boolean includeDims) {
      if (returnType == null)
         return null;
      return returnType.getAbsoluteGenericTypeName(resultType, includeDims);
   }

   public List<TypeParameter> getTypeParameters() {
      return null;
   }

   public List<Object> getTypeArguments() {
      return null;
   }

   public Method getRuntimeMethod() {
      Class cl = getEnclosingIType().getCompiledClass();
      if (cl == null)
         return null;

      Class[] paramTypes = ModelUtil.typeToClassArray(parameterJavaTypes);
      return RTypeUtil.getMethod(cl, name, paramTypes);
   }
}
