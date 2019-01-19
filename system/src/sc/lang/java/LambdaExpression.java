/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.parser.ParseUtil;

import java.util.EnumSet;
import java.util.Set;

/**
 * Java 8 lambda expression.  To run these in JS we convert them to anonymous classes.
 * TODO: ideally we could optimize the JS code-gen of lambda classes where 'this' of the anonymous class is not used like in java
 */
public class LambdaExpression extends BaseLambdaExpression implements IStatementWrapper {
   public Object lambdaParams; // An identifier, e.g. x -> y, Parameter list (int a, int b) -> y, or SemanticNodeList<IString> (a, b) -> y
   public Statement lambdaBody; // Either an expression or a BlockStatement

   public Object getLambdaParameters(Object meth, ITypeParamContext ctx) {
      return lambdaParams;
   }

   public Statement getLambdaBody(Object meth) {
      return lambdaBody;
   }

   public void startLambdaBody() {
      super.startLambdaBody();
      if (lambdaBody != null)
         ParseUtil.realInitAndStartComponent(lambdaBody);
   }

   public String getExprType() {
      return "lambda expression";
   }

   @Override
   public void refreshBoundTypes(int flags) {
      if (lambdaParams instanceof Parameter)
         ((Parameter) lambdaParams).refreshBoundType(flags);
      if (lambdaBody instanceof BlockStatement)
         lambdaBody.refreshBoundTypes(flags);
   }

   @Override
   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      if (lambdaParams instanceof Parameter)
         ((Parameter) lambdaParams).addDependentTypes(types, mode);
      if (lambdaBody != null)
         lambdaBody.addDependentTypes(types, mode);
   }

   public String toGenerateString() {
      StringBuilder sb = new StringBuilder();
      String params = getParamString(lambdaParams);
      sb.append(params);
      if (params.charAt(params.length()-1) != ' ')
         sb.append(" ");
      sb.append("-> ");
      sb.append(lambdaBody == null ? "{}" : lambdaBody.toLanguageString());
      return sb.toString();
   }

   public Object findMember(String memberName, EnumSet<MemberType> type, Object fromChild, Object refType, TypeContext ctx, boolean skipIfaces) {
      if (inferredType != null) {
         if (newExpr == null)
            initNewExpression(false);
         if (parameters != null) {
            Object v;
            if (type.contains(MemberType.Variable)) {
               for (Parameter p : parameters.getParameterList())
                  if ((v = p.definesMember(memberName, type, refType, ctx, skipIfaces, false)) != null)
                     return v;
            }
         }
      }
      return super.findMember(memberName, type, this, refType, ctx, skipIfaces);
   }

   protected void propagateInferredType(Object type, Object methReturnType, boolean inferredFinal) {
      super.propagateInferredType(type, methReturnType, inferredFinal);
      // If we are only creating the new expression for validation purposes, propagating the inferred type on the body
      // will force us to resolve types in this temporary inferredType.  We really only need to resolve the temporary
      // newExpr in validationMode - not the actual lambda body
      if (inferredFinal && lambdaBody instanceof Expression) {
         ((Expression) lambdaBody).setInferredType(methReturnType, inferredFinal);
      }
   }

   void updateMethodTypeParameters(Object ifaceMeth) {
      if (lambdaParams instanceof Parameter) {
         Object[] ifaceParamTypes = ModelUtil.getParameterTypes(ifaceMeth, false);
         Object[] refParamTypes = ((Parameter) lambdaParams).getParameterTypes();
         if (ifaceParamTypes != null && refParamTypes != null) {
            int j = 0;
            for (int i = 0; i < ifaceParamTypes.length; i++) {
               Object ifaceParamType = ifaceParamTypes[i];
               Object refParamType = refParamTypes[j];
               if (ModelUtil.isTypeVariable(ifaceParamType)) {
                  addTypeParameterMapping(ifaceMeth, ifaceParamType, refParamType);
               }
            }
         }
      }
   }

   protected boolean referenceMethodMatches(Object type, Object ifaceMeth, LambdaMatchContext ctx) {
      if (type instanceof ParamTypeDeclaration)
         type = ((ParamTypeDeclaration) type).cloneForNewTypes();
      JavaModel thisModel = getJavaModel();
      boolean origTypeErrors = thisModel.disableTypeErrors;
      thisModel.setDisableTypeErrors(true);
      try {
         setInferredType(type, false);
         if (lambdaBody == null || lambdaMethod == null) {
            initNewExpression(true);
            if (lambdaBody == null || lambdaMethod == null)
               return false;
         }

         Object ifaceReturnType = ModelUtil.getReturnType(ifaceMeth, true);
         Object inferredReturnType = lambdaMethod.getInferredReturnType(true);

         if (ctx != null) {
            ctx.ifaceReturnType = ifaceReturnType;
            ctx.inferredReturnType = inferredReturnType;
         }

         // Some cases we are not able to resolve the inferred return type.  E.g. methodCall(DefinesTypeParamT, Function<T, V> lambda expression and body expr depends on T to resolve method
         if (inferredReturnType == MethodDefinition.UNRESOLVED_INFERRED_TYPE)
            return true;
         boolean ifaceMethIsVoid = ModelUtil.typeIsVoid(ifaceReturnType);
         boolean lambdaBodyIsVoid = inferredReturnType == null || ModelUtil.typeIsVoid(inferredReturnType);

         if (ctx != null) {
            ctx.ifaceMethIsVoid = ifaceMethIsVoid;
            ctx.lambdaBodyIsVoid = lambdaBodyIsVoid;
         }
         // When we generate the new expression we won't put a return type for the expression and so don't get the real
         // lambda body return type.  It's a little awkward but we can just dig into the generated method body and get
         // the only statement which is the lambdaBody expression we've resolved, then get the type there.
         boolean isExpr = false;
         if (lambdaBodyIsVoid && lambdaBody instanceof Expression) {
            Statement st = lambdaMethod.body == null ? null : lambdaMethod.body.statements.get(0);
            if (st instanceof Expression) {
               Expression expr = (Expression) st;
               inferredReturnType = expr.getGenericType();
               lambdaBodyIsVoid = ModelUtil.typeIsVoid(inferredReturnType);
               if (ctx != null) {
                  ctx.inferredReturnType = inferredReturnType;
                  ctx.lambdaBodyIsVoid = lambdaBodyIsVoid;
               }
               isExpr = true;
            }
         }
         if (!ifaceMethIsVoid && lambdaBodyIsVoid)
            return false; // Weird case - at least this is ok when the method throws an exception so return never happens?
         if (!lambdaBodyIsVoid && ifaceMethIsVoid) {
            if (isExpr)
               return true;
            return true;
         }
      }
      finally {
         thisModel.setDisableTypeErrors(origTypeErrors);

         clearInferredType();
         // TODO: remove
         ParseUtil.stopComponent(lambdaBody);
      }

      return true;
   }

   public void clearInferredType() {
      super.clearInferredType();
      // TODO: remove should not be needed
      if (lambdaBody != null)
         ParseUtil.stopComponent(lambdaBody);
   }

   public String toDeclarationString() {
      String res = toSafeLanguageString();
      if (res.length() > 120)
         res = res.substring(0, 60) + " ... " + res.substring(res.length() - 60);
      return res;
   }

   public Object pickMoreSpecificMethod(Object meth1, Object argType1, boolean varArg1, Object meth2, Object argType2, boolean varArg2) {
      Object res = super.pickMoreSpecificMethod(meth1, argType1, varArg1, meth2, argType2, varArg2);
      if (res != null)
         return res;

      return null;
   }

   public Statement findStatement(Statement in) {
      if (lambdaBody != null) {
         Statement out = lambdaBody.findStatement(in);
         if (out != null)
            return out;
      }
      return null;
   }

   public boolean isLeafStatement() {
      return lambdaBody != null && lambdaBody.isLeafStatement();
   }

   public Statement getWrappedStatement() {
      return lambdaBody;
   }

   public String getFunctionEndString() {
      return "{";
   }
}
