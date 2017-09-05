/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.classfile;

import sc.type.PropertyMethodType;
import sc.type.RTypeUtil;
import sc.lang.java.*;

import java.lang.reflect.Method;
import java.util.List;

/** A Method definition that is extracted from the class file representation. */
public class CFMethod extends ClassFile.FieldMethodInfo implements IVariable, IMethodDefinition {
   public JavaType returnType;
   public JavaType[] parameterJavaTypes;
   public Object[] boundParameterTypes;
   public Object[] unboundParameterTypes;
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

      int numParams = 0;
      // To be consistent with the Class.getParameterTypes() method, we need to strip out the constructor for an inner type
      if (ptypes != null) {
         numParams = ptypes.size();
         if (isConstructor() && ownerClass.getEnclosingType() != null && numParams > 1 && !ownerClass.hasModifier("static")) {
            ptypes = ptypes.subList(1, numParams);
            numParams--;
         }
      }
      parameterJavaTypes = ptypes == null ? null : ptypes.toArray(new JavaType[numParams]);

      /** Note: this needs to be done in initialize so we can do resolves without accessing the property name */
      if ((propertyName = ModelUtil.isGetMethod(name, parameterJavaTypes, returnType)) != null)
         propertyMethodType = propertyName.startsWith("i") ? PropertyMethodType.Is : PropertyMethodType.Get;
      // Do this before set method because isSetMethod includes indexed properties
      else if ((propertyName = ModelUtil.isSetIndexMethod(name, parameterJavaTypes, returnType)) != null)
         propertyMethodType = PropertyMethodType.SetIndexed;
      else if ((propertyName = ModelUtil.isSetMethod(name, parameterJavaTypes, returnType)) != null)
         propertyMethodType = PropertyMethodType.Set;
      else if ((propertyName = ModelUtil.isGetIndexMethod(name, parameterJavaTypes, returnType)) != null)
         propertyMethodType = PropertyMethodType.GetIndexed;

      super.init();
   }

   public void start() {
      if (started)
         return;

      if (!initialized)
         init();

      if (parameterJavaTypes != null) {
         boundParameterTypes = new Object[parameterJavaTypes.length];
         unboundParameterTypes = new Object[parameterJavaTypes.length];
         for (int i = 0; i < parameterJavaTypes.length; i++) {
            boundParameterTypes[i] = parameterJavaTypes[i].getTypeDeclaration(null, null, true, false, true);
            unboundParameterTypes[i] = parameterJavaTypes[i].getTypeDeclaration(null, null, false, false, true);
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

   public Object getReturnType(boolean boundParams) {
      if (!started)
         start();
      if (returnType == null)
         return null;
      return returnType.getTypeDeclaration(null, null, boundParams, false, true);
   }

   public Object getReturnJavaType() {
      return returnType;
   }

   public Object[] getParameterTypes(boolean bound) {
      if (!started)
         start();
      return bound ? boundParameterTypes : unboundParameterTypes;
   }

   public JavaType[] getParameterJavaTypes(boolean convertRepeating) {
      // TODO: this most likely will already turn repeating args to array types... we are migrating from convertRepeating = false to true so hopefully convertRepeating is never true
      return parameterJavaTypes;
   }

   public Object getTypeDeclaration(List<? extends ITypedObject> args, boolean resolve) {
      // Type parameter resolution happens in ModelUtil so this code is shared
      return getReturnType(resolve);
   }

   public String getPropertyName() {
      return propertyName;
   }
   
   public boolean hasGetMethod() {
      return isGetMethod() ||
              ModelUtil.definesMember(ownerClass, propertyName, JavaSemanticNode.MemberType.GetMethodSet, null, null, null) != null;
   }

   public boolean hasSetMethod() {
      return isSetMethod() ||
              ModelUtil.definesMember(ownerClass, propertyName, JavaSemanticNode.MemberType.SetMethodSet, null, null, null) != null;
   }

   public Object getSetMethodFromGet() {
      if (isGetMethod())
        return ModelUtil.definesMember(ownerClass, propertyName, JavaSemanticNode.MemberType.SetMethodSet, null, null, getLayeredSystem());
      return null;
   }

   public Object getGetMethodFromSet() {
      if (isSetMethod())
        return ModelUtil.definesMember(ownerClass, propertyName, JavaSemanticNode.MemberType.GetMethodSet, null, null, getLayeredSystem());
      return null;
   }

   public Object getFieldFromGetSetMethod() {
      if (isSetMethod() || isGetMethod())
         return ModelUtil.definesMember(ownerClass, propertyName, JavaSemanticNode.MemberType.FieldSet, null, null, getLayeredSystem());
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
      if (!started)
         start();

      if (typeSignature == null)
         return null;

      // When type parameters are involved, we need to expand the types here - otherwise, we could try to substring this from typeSignature
      if (parameterJavaTypes != null) {
         StringBuilder sb = new StringBuilder();
         for (int i = 0; i < parameterJavaTypes.length; i++)
            sb.append(parameterJavaTypes[i].getSignature(true));
         return sb.toString();
      }
      return null;
      /*
      int openIx = typeSignature.indexOf("(");
      int closeIx = typeSignature.indexOf(")");
      if (closeIx == -1 || openIx == -1)
         return typeSignature; // Not reached
      return typeSignature.substring(openIx+1, closeIx);
      */
   }

   public JavaType[] getExceptionTypes() {
      ClassFile.ExceptionsAttribute att = (ClassFile.ExceptionsAttribute) attributes.get("Exceptions");
      if (att == null || att.typeNames == null)
         return null;
      String[] tns = att.typeNames;
      JavaType[] types = new JavaType[tns.length];
      for (int i = 0; i < tns.length; i++) {
         types[i] = JavaType.createJavaType(getLayeredSystem(), tns[i]);
      }
      return types;
   }

   public String getThrowsClause() {
      return ModelUtil.typesToThrowsClause(getExceptionTypes());
   }

   public Object[] getMethodTypeParameters() {
      if (typeParameters == null)
         return null;
      return typeParameters.toArray();
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      String modifiers = modifiersToString(true, true, true, true, false, null);
      if (modifiers != null) {
         sb.append(modifiers);
         sb.append(" ");
      }
      if (typeParameters != null) {
         sb.append("<");
         boolean first = true;
         for (TypeParameter typeParam:typeParameters) {
            if (!first) {
               sb.append(", ");
            }
            else
               first = false;
            if (typeParam == null)
               sb.append("<null>");
            else
               sb.append(typeParam.toString());
         }
         sb.append(">");
      }
      if (name != null)
         sb.append(name);
      else
         sb.append("<uninitialized method>");
      sb.append("(");
      if (parameterJavaTypes != null) {
         boolean first = true;
         for (JavaType paramJavaType:parameterJavaTypes) {
            if (!first)
               sb.append(", ");
            else
               first = false;
            if (paramJavaType != null) {
               sb.append(paramJavaType);
            }
            else
               sb.append("<null>");
         }
      }
      sb.append(")");

      if (ownerClass != null) {
         sb.append(" in: ");
         sb.append(ownerClass.getTypeName());
      }
      return sb.toString();
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

      Method res = RTypeUtil.getMethodFromTypeSignature(cl, name, getTypeSignature());
      return res;
   }

   public boolean isConstructor() {
      return name != null && name.equals("<init>");
   }
}
