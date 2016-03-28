/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ISemanticNode;
import sc.lang.SemanticNodeList;
import sc.layer.LayeredSystem;

import java.util.List;
import java.util.Set;

public class MethodReference extends BaseLambdaExpression {
   public JavaSemanticNode reference; // Either a TypeExpression, JavaType or an Expression
   public SemanticNodeList<JavaType> typeArguments;
   public String methodName;

   enum MethodReferenceType {
      StaticMethod, InstanceMethod, ClassMethod, Constructor
   }

   transient MethodReferenceType referenceType;
   transient Object referenceMethod;
   /** Set to true when the first parameter of the interface's method is used as the instance for the reference method */
   transient boolean paramInstance = false;

   /** Maps the lhs of the MethodReference to either sa JavaType or an IdentifierExpression */
   private Object resolveReference() {
      Object ref = reference;
      if (reference instanceof TypeExpression) {
         ref = ((TypeExpression) reference).resolveReference();
      }
      return ref;
   }

   void updateInferredTypeMethod(Object meth) {
      super.updateInferredTypeMethod(meth);

      boolean isNewOperator = methodName.equals("new");

      initReferenceMethod();
      if (referenceMethod == null)
         return;

      Object ref = resolveReference();

      if (ref instanceof Expression) {
         referenceType = MethodReferenceType.InstanceMethod;
         if (isNewOperator)
            displayError("The 'new' operator is not allowed with expression: " + ref + " in method reference: ");
      }
      else if (ref instanceof JavaType) {
         if (ModelUtil.hasModifier(referenceMethod, "static")) {
            referenceType = MethodReferenceType.StaticMethod;
         }
         else {
            referenceType = MethodReferenceType.ClassMethod;
         }
      }
      if (isNewOperator) {
         if (!ModelUtil.isConstructor(referenceMethod))
            System.err.println("*** Internal error - invalid method reference");
         referenceType = MethodReferenceType.Constructor;
      }
   }

   @Override
   public void refreshBoundTypes(int flags) {
      Object ref = resolveReference();
      if (ref instanceof JavaType)
         ((JavaType) ref).refreshBoundType(flags);
      else if (ref instanceof Expression)
         ((Expression) ref).refreshBoundTypes(flags);

      if (typeArguments != null) {
         for (JavaType typeArg:typeArguments)
            typeArg.refreshBoundType(flags);
      }
   }

   @Override
   public void addDependentTypes(Set<Object> types) {
      Object ref = resolveReference();
      if (ref instanceof JavaType)
         ((JavaType) ref).addDependentTypes(types);
      else if (ref instanceof Expression)
         ((Expression) ref).addDependentTypes(types);

      if (typeArguments != null) {
         for (JavaType typeArg:typeArguments)
            typeArg.addDependentTypes(types);
      }
   }

   @Override
   Object getLambdaParameters(Object methObj, ITypeParamContext ctx) {
      Object[] ptypes = ModelUtil.getParameterTypes(methObj, true);
      Object[] resTypes = ptypes == null ? null : new Object[ptypes.length];
      if (ptypes != null) {
         for (int i = 0; i < ptypes.length; i++) {
            resTypes[i] = ptypes[i];
            if (ModelUtil.isTypeVariable(resTypes[i]))
               resTypes[i] = ModelUtil.getTypeParameterDefault(resTypes[i]);
         }
      }
      Parameter res = Parameter.create(resTypes,ModelUtil.getParameterNames(methObj), ctx, getEnclosingType());
      if (res != null)
         res.parentNode = this;
      return res;
   }

   boolean isValidInferredTypeMethod(Object ifmeth) {
      Object refType = getReferencedType();

      if (refType == null)
         return false;

      Object[] meths;
      boolean isConstructor = false;
      if (methodName.equals("new")) {
         meths = ModelUtil.getConstructors(refType, getEnclosingType());
         isConstructor = true;
      }
      else {
         meths = ModelUtil.getMethods(refType, methodName, null);
      }
      if (meths == null || meths.length == 0) {
         // TODO: do we need to check if there's a valid constructor here?
         if (methodName.equals("new")) {
            return true;
         }
         return false;
      }
      Object[] paramTypes = ModelUtil.getParameterTypes(ifmeth, true);
      Object returnType = ModelUtil.getReturnType(ifmeth, true);
      LayeredSystem sys = getLayeredSystem();
      // Find the method in the list which matches the type parameters of the inferred type method
      // First pass is to look for a method where all of the parameters match each other
      for (Object meth:meths) {
         Object methReturnType = ModelUtil.getReturnType(meth, true);
         // Need to skip void methods here.  They will be assignable from a type parameter but here we know returnType is a real value (I think)
         if (ModelUtil.typeIsVoid(returnType) != ModelUtil.typeIsVoid(methReturnType))
            continue;
         if (ModelUtil.parametersMatch(ModelUtil.getParameterTypes(meth, true), paramTypes, true, sys) && (isConstructor || ModelUtil.isAssignableFrom(returnType, methReturnType, sys))) {
            return true;
         }
      }
      // Second case is used when the first parameter
      if (paramTypes != null && paramTypes.length > 0) {
         int newLen = paramTypes.length-1;
         Object[] nextParamTypes = new Object[newLen];
         System.arraycopy(paramTypes, 1, nextParamTypes, 0, newLen);
         for (Object meth:meths) {
            Object methReturnType = ModelUtil.getReturnType(meth, true);

            //if (!ModelUtil.isAssignableFrom(paramTypes[0], methReturnType))
            //   continue;

            if (!ModelUtil.isAssignableFrom(refType, paramTypes[0], false, null, true, sys))
               continue;
            Object[] refParamTypes = ModelUtil.getParameterTypes(meth, true);
            if (ModelUtil.parametersMatch(refParamTypes, nextParamTypes, true, sys)) {
               return true;
            }
         }
      }
      return false;
   }

   void initReferenceMethod() {
      Object refType = getReferencedType();
      if (refType == null) {
         referenceMethod = null;
         return;
      }

      Object[] meths;
      boolean isConstructor = false;
      if (methodName.equals("new")) {
         meths = ModelUtil.getConstructors(refType, getEnclosingType());
         isConstructor = true;
      }
      else {
         meths = ModelUtil.getMethods(refType, methodName, null);
      }
      if (meths == null || meths.length == 0) {
         if (methodName.equals("new")) {
            referenceMethod = new ConstructorDefinition();  // ModelUtil.getDefaultConstructor?
            return;
         }
         displayError("No method in type: " + ModelUtil.getTypeName(refType) + " for method reference: ");
         referenceMethod = null;
         return;
      }
      if (inferredType == null || inferredTypeMethod == null) {
         referenceMethod = meths[0];
      }
      else {
         Object res = null;
         Object[] paramTypes = ModelUtil.getParameterTypes(inferredTypeMethod, true);
         Object returnType = ModelUtil.getReturnType(inferredTypeMethod, true);
         LayeredSystem sys = getLayeredSystem();
         // Find the method in the list which matches the type parameters of the inferred type method
         // First pass is to look for a method where all of the parameters match each other
         for (Object meth:meths) {
            Object methReturnType = ModelUtil.getReturnType(meth, true);
            if (!isConstructor && ModelUtil.typeIsVoid(methReturnType) != ModelUtil.typeIsVoid(returnType))
               continue;
            if (ModelUtil.parametersMatch(ModelUtil.getParameterTypes(meth, true), paramTypes, true, sys) && (isConstructor || ModelUtil.isAssignableFrom(returnType, methReturnType, sys))) {
               if (res == null)
                  res = meth;
               else
                  res = ModelUtil.pickMoreSpecificMethod(res, meth, paramTypes);
            }
         }
         // Second case is used when the first parameter
         if (res == null && paramTypes != null && paramTypes.length > 0) {
            int newLen = paramTypes.length-1;
            Object[] nextParamTypes = new Object[newLen];
            System.arraycopy(paramTypes, 1, nextParamTypes, 0, newLen);
            for (Object meth:meths) {
               Object methReturnType = ModelUtil.getReturnType(meth, true);

               //if (!ModelUtil.isAssignableFrom(paramTypes[0], methReturnType))
               //   continue;

               if (!ModelUtil.isAssignableFrom(refType, paramTypes[0], sys))
                  continue;
               Object[] refParamTypes = ModelUtil.getParameterTypes(meth, true);
               if (ModelUtil.parametersMatch(refParamTypes, nextParamTypes, true, sys)) {
                  if (res == null)
                     res = meth;
                  else
                     res = ModelUtil.pickMoreSpecificMethod(res, meth, nextParamTypes);
               }
            }
            paramInstance = res != null;
         }
         referenceMethod = res;
         // TODO: better error message here - we can display the two different signatures that should match.
         if (res == null)
            displayError("No reference method for method reference: ");
      }
   }

   Object getReferencedType() {
      Object ref = resolveReference();
      if (ref instanceof Expression)
         return ((Expression) ref).getGenericType();
      else if (ref instanceof JavaType) {
         return ((JavaType) ref).getTypeDeclaration();
      }
      return null;
   }

   @Override
   Statement getLambdaBody(Object methObj) {
      if (referenceMethod == null || inferredTypeMethod == null || referenceType == null)
         return null;

      Expression bodyExpr = null;
      SemanticNodeList<Expression> args = new SemanticNodeList<Expression>();
      List<Parameter> paramList = parameters == null ? null : parameters.getParameterList();
      if (paramList != null) {
         for (int i = paramInstance ? 1 : 0; i < paramList.size(); i++) {
            Parameter param = paramList.get(i);
            args.add(IdentifierExpression.create(param.variableName));
         }
      }
      Object ref = resolveReference();
      switch (referenceType) {
         case InstanceMethod:
            bodyExpr = SelectorExpression.create(((Expression) ref).deepCopy(ISemanticNode.CopyNormal, null), VariableSelector.create(methodName, args));
            break;
         case StaticMethod:
            bodyExpr = IdentifierExpression.createMethodCall(args, ((JavaType)ref).getFullTypeName(), methodName);
            break;
         case ClassMethod:
            if (paramInstance) {
               bodyExpr = IdentifierExpression.createMethodCall(args, paramList.get(0).variableName, methodName);
            }
            else {
               // Now sure what we are supposed to do here... create a new instance or something?
               System.err.println("*** Unhandled case for MethodReference: ");
            }
            break;
         case Constructor:
            if (ref instanceof JavaType) {
               JavaType typeRef = (JavaType) ref;
               if (typeRef.arrayDimensions != null) {
                  if (args.size() != 1) {
                     displayError("Method reference with array constructor - missing 'length' parameter: ");
                     return null;
                  }
                  // Create new <type>[intArg] - exclude dimensions from the type name with fullBaseTypeName
                  bodyExpr = NewExpression.create(typeRef.getFullBaseTypeName(), args, null);
               }
               else // Create new <type>(arg1, arg2)
                  bodyExpr = NewExpression.create(typeRef.getFullTypeName(), args);
            }
            else
               System.err.println("*** Unhandled case for MethodReference new operator: ");
            break;
         default:
            throw new UnsupportedOperationException();
      }

      if (bodyExpr == null)
         throw new UnsupportedOperationException();

      return bodyExpr;
   }

   public String getExprType() {
      return "method reference";
   }

   public String toGenerateString() {
      StringBuilder sb = new StringBuilder();
      sb.append(reference.toGenerateString());
      sb.append("::");
      sb.append(methodName);
      return sb.toString();
   }

   // For MethodReferences, we know the return type directly from the reference type.  We need this type to properly
   // set type parameters in the newMethod's parameter list.
   public Object getNewMethodReturnType() {
      if (referenceType == null)
         return null;
      switch (referenceType) {
         case StaticMethod:
         case InstanceMethod:
         case ClassMethod:
            return referenceMethod == null ? null : ModelUtil.getReturnType(referenceMethod, false);
         case Constructor:
            return ModelUtil.getEnclosingType(referenceMethod);
      }
      return null;
   }

   void updateMethodTypeParameters(Object ifaceMeth) {
      if (referenceMethod != null) {
         Object[] ifaceParamTypes = ModelUtil.getParameterTypes(ifaceMeth, false);
         Object[] refParamTypes = ModelUtil.getParameterTypes(referenceMethod, true);
         if (ifaceParamTypes != null && refParamTypes != null) {
            int j = 0;
            int start = paramInstance ? 1 : 0;
            for (int i = start; i < ifaceParamTypes.length; i++) {
               Object ifaceParamType = ifaceParamTypes[i];
               Object refParamType = refParamTypes[j];
               if (ModelUtil.isTypeVariable(ifaceParamType)) {
                  addTypeParameterMapping(ifaceMeth, ifaceParamType, refParamType);
               }
            }
         }
      }
   }

   public boolean referenceMethodMatches(Object type, Object ifaceMeth) {
      if (!isValidInferredTypeMethod(ifaceMeth))
         return false;
      return true;
   }
}
