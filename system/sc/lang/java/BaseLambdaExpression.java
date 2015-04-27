/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ILanguageModel;
import sc.lang.ISemanticNode;
import sc.lang.JavaLanguage;
import sc.lang.SemanticNodeList;
import sc.parser.IString;
import sc.parser.PString;
import sc.parser.ParseUtil;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.WildcardType;
import java.util.*;

/** Java 8 lambda expression.  To run these in JS we need to convert them to anonymous classes */
public abstract class BaseLambdaExpression extends Expression {
   public final static String LAMBDA_INFERRED_TYPE = new String("lambdaInferredTypeSentinel");

   /** These are the parameters to the lambda expression - they should match the inferredTypeMethod */
   transient Parameter parameters; // Either a copy of lambdaParams when they are full specified or Parameter types we create to mirror the method that will be generated
   /** This is the "functional interface" (i.e. one method which is unimplemented) we are implementing with this lambda expression. */
   transient Object inferredType = null;
   /** The new expression which we generate to implement the lambda expression */
   transient NewExpression newExpr = null;
   transient boolean needsStart = false;
   /** This is the method on the interface - the inferredType - that this lambda expression will supply a new implementation of  */
   transient Object inferredTypeMethod;
   /** This is the implementation we generate of the inferredTypeMethod */
   transient MethodDefinition lambdaMethod;

   abstract Object getLambdaParameters(Object methObj);
   abstract Statement getLambdaBody(Object methObj);
   abstract String getExprType();

   void updateInferredTypeMethod(Object methObj) {
      inferredTypeMethod = methObj;
   }

   void initNewExpression() {
      if (inferredType == null) {
         // TODO: will this happen?
         displayError("No inferredType for lambda expression: ");
         return;
      }

      if (!ModelUtil.isInterface(inferredType)) {
         displayError("Type for lambda expression must be an interface with one method: ");
         return;
      }

      Object[] methods = ModelUtil.getAllMethods(inferredType, null, false, false, false);
      Object ifaceMeth = null;
      if (methods == null) {
         displayError("Type for lambda expression has no methods: ");
         return;
      }
      else if (methods.length != 1) {
         for (Object meth:methods) {
            if (ModelUtil.isDefaultMethod(meth))
               continue;
            if (ModelUtil.hasModifier(meth, "private"))
               continue;
            if (ModelUtil.hasModifier(meth, "static"))
               continue;
            if (ifaceMeth != null) {
               displayError("Type for lambda expression must have only one method.  Interface " + ModelUtil.getTypeName(inferredType) + " has both: " + ModelUtil.toDeclarationString(ifaceMeth) + " and " + ModelUtil.toDeclarationString(meth));
               return;
            }
            else
               ifaceMeth = meth;
         }
      }
      else
         ifaceMeth = methods[0];

      updateInferredTypeMethod(ifaceMeth);

      Object lambdaParams = getLambdaParameters(ifaceMeth);
      if (!parametersMatch(lambdaParams, ifaceMeth)) {
         displayError("Mismatch between lambda method: " + ModelUtil.getMethodName(ifaceMeth) + " and " + getExprType() + " parameters: " + getParamString(lambdaParams) + " and: " + ModelUtil.toDeclarationString(ifaceMeth));
      }

      if (ModelUtil.isParameterizedMethod(ifaceMeth) && ModelUtil.hasTypeParameters(inferredType)) {
         ifaceMeth = new ParamTypedMethod(ifaceMeth, (ITypeParamContext) inferredType);
      }

      ITypeParamContext typeCtx = inferredType instanceof ITypeParamContext ? (ITypeParamContext) inferredType : null;

      newExpr = new NewExpression();
      newExpr.lambdaExpression = true;
      newExpr.setProperty("typeIdentifier", ModelUtil.getTypeName(inferredType));
      SemanticNodeList<Statement> classBody = new SemanticNodeList<Statement>();

      MethodDefinition newMeth = new MethodDefinition();
      newMeth.name = ModelUtil.getMethodName(ifaceMeth);
      newMeth.setProperty("parameters", parameters = createLambdaParams(lambdaParams, ifaceMeth));
      newMeth.setProperty("type", JavaType.createFromParamType(ModelUtil.getParameterizedReturnType(ifaceMeth, null, true), typeCtx));
      lambdaMethod = newMeth;

      // Always public since they are in an interface
      SemanticNodeList<Object> mods = new SemanticNodeList<Object>();
      mods.add("public");
      newMeth.setProperty("modifiers", mods);

      Statement lambdaBody = getLambdaBody(ifaceMeth);

      BlockStatement methodBody;
      if (lambdaBody instanceof BlockStatement) {
         methodBody = (BlockStatement) lambdaBody.deepCopy(ISemanticNode.CopyNormal, null);
      }
      else if (lambdaBody instanceof Expression) {
         methodBody = new BlockStatement();
         if (ModelUtil.typeIsVoid(ModelUtil.getReturnType(ifaceMeth))) {
            // The method is void and so does not return anything.  Just use <expr>
            methodBody.addStatementAt(0, ((Expression) lambdaBody).deepCopy(ISemanticNode.CopyNormal, null));
         }
         else{
            // Do the return <expr> ;
            methodBody.addStatementAt(0, ReturnStatement.create(((Expression) lambdaBody).deepCopy(ISemanticNode.CopyNormal, null)));
         }
      }
      else {
         displayError("Invalid method body type: ");
         return;
      }
      newMeth.setProperty("body", methodBody);

      classBody.add(newMeth);
      newExpr.setProperty("classBody", classBody);
      newExpr.parentNode = parentNode;

      Object ifaceMethReturnType = ModelUtil.getReturnJavaType(ifaceMeth);
      if (ModelUtil.isParameterizedType(ifaceMethReturnType) && inferredType instanceof ParamTypeDeclaration && ((ParamTypeDeclaration) inferredType).hasUnboundParameters()) {
         Object methodReturnType = newMeth.getInferredReturnType();

         ParamTypeDeclaration paramType = (ParamTypeDeclaration) inferredType;

         if (ModelUtil.isTypeVariable(ifaceMethReturnType)) {
            String typeParamName = ModelUtil.getTypeParameterName(ifaceMethReturnType);

            // The owner type of ifaceMeth may be a subclass of paramType and so we need to map the parameters.

            if (!ModelUtil.sameTypes(paramType, ModelUtil.getEnclosingType(ifaceMeth))) {
               // TODO: need to map type parameters from one type to the other...
               System.out.println("*** Unsupported type parameters case");
            }
            paramType.setTypeParameter(typeParamName, methodReturnType);
         }
      }

      // Setting these after we've added any type parameters whose type is inferred form the method's return type
      newMeth.setProperty("type", JavaType.createFromParamType(ModelUtil.getParameterizedReturnType(ifaceMeth, null, true), typeCtx));
      if (ModelUtil.hasTypeParameters(inferredType)) {
         SemanticNodeList<JavaType> typeParams = new SemanticNodeList<JavaType>();
         int numParams = ModelUtil.getNumTypeParameters(inferredType);
         for (int i = 0; i < numParams; i++) {
            Object typeParam = ModelUtil.getTypeParameter(inferredType, i);
            if (typeParam instanceof JavaType)
               typeParams.add((JavaType) ((JavaType) typeParam).deepCopy(ISemanticNode.CopyNormal, null));
            else if (typeParam instanceof WildcardType || typeParam instanceof ParameterizedType) {
               typeParams.add((JavaType.createFromParamType(typeParam, inferredType instanceof ITypeParamContext ? (ITypeParamContext) inferredType : null)));
            }
            else
               typeParams.add(ClassType.create(ModelUtil.getTypeName(typeParam)));
         }
         newExpr.setProperty("typeArguments", typeParams);
      }
   }

   private static boolean parametersMatch(Object lambdaParams, Object meth) {
      Object[] params = ModelUtil.getParameterTypes(meth);
      // One parameter special case for lambda's
      if (PString.isString(lambdaParams)) {
         return params.length == 1;
      }
      // Lambda sends in the list of names - the length only needs to match
      else if (lambdaParams instanceof SemanticNodeList) {
         return ((SemanticNodeList) lambdaParams).size() == params.length;
      }
      else if (lambdaParams instanceof Parameter) {
         Object[] paramTypes = ((Parameter) lambdaParams).getParameterTypes();
         return ModelUtil.parametersMatch(paramTypes, params);
      }
      // () turns into null?
      else if (lambdaParams == null) {
         return params == null || params.length == 0;
      }
      else {
         throw new UnsupportedOperationException("Lambda expression has invalid parameter type");
      }
   }

   String getParamString(Object lambdaParams) {
      return (lambdaParams == null ? "()" : lambdaParams instanceof IString ? lambdaParams : ParseUtil.toLanguageString(JavaLanguage.getJavaLanguage().lambdaParameters, (ISemanticNode) lambdaParams)).toString();
   }

   public MethodDefinition getLambdaMethod() {
      if (lambdaMethod == null)
         initNewExpression();
      return lambdaMethod;
   }

   public void start() {
      if (inferredType != null)
         super.start();
      else
         needsStart = true;
   }

   public void validate() {
      if (inferredType == null)
         displayError("Unrecognized use of lambda expression: ");
      if (needsStart) {
         needsStart = false;
         super.start();
      }
      super.validate();
   }

   private static Parameter createLambdaParams(Object params, Object meth) {
      ArrayList<String> names = new ArrayList<String>();

      if (PString.isString(params)) {
         names.add(params.toString());
      }
      else if (params instanceof SemanticNodeList) {
         SemanticNodeList paramNameArray = (SemanticNodeList) params;
         for (Object paramName:paramNameArray)
            names.add(paramName.toString());
      }
      else if (params instanceof Parameter) {
         return (Parameter) ((Parameter) params).deepCopy(ISemanticNode.CopyNormal, null);
      }
      // Empty parameters
      else if (params == null) {
         return null;
      }
      else {
         throw new UnsupportedOperationException();
      }

      Object[] methTypes = ModelUtil.getParameterTypes(meth);

      return Parameter.create(methTypes, names.toArray(new String[names.size()]));
   }

   @Override
   public boolean isStaticTarget() {
      // TODO: is this right?
      return false;
   }

   public Object getTypeDeclaration() {
      if (inferredType == null)
         return LAMBDA_INFERRED_TYPE;
      if (newExpr == null)
         initNewExpression();
      if (newExpr == null)
         return LAMBDA_INFERRED_TYPE;
      return inferredType;
   }

   public Object eval(Class expectedType, ExecutionContext ctx) {
      if (newExpr == null)
         initNewExpression();
      if (newExpr != null)
         return newExpr.eval(expectedType, ctx);
      throw new IllegalArgumentException("eval of undefined lambda expression");
   }

   public boolean transform(ILanguageModel.RuntimeType runtime) {
      if (getLayeredSystem().getNeedsAnonymousConversion() || bindingStatement != null) {
         initNewExpression();
         parentNode.replaceChild(this, newExpr);
         newExpr.transform(runtime);
         return true;
      }
      return super.transform(runtime);
   }

   public Statement transformToJS() {
      if (newExpr != null)
         return newExpr;
      // Not reached
      throw new UnsupportedOperationException("Invalid transform of lambda expression");
   }

   public void transformBinding(ILanguageModel.RuntimeType runtime) {
      if (newExpr == null)
         throw new UnsupportedOperationException("lambda expression in binding mishap!");
      newExpr.transformBinding(runtime);
   }

   public void setInferredType(Object parentType) {
      // Convert between the ParameterizedType and ParamTypeDeclaration
      if (parentType instanceof ParameterizedType)
         parentType = ModelUtil.getTypeDeclFromType(parentType, parentType, false, getLayeredSystem(), true);
      inferredType = parentType;
      if (needsStart) {
         needsStart = false;
         super.start();
      }
   }
}
