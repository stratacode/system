/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.List;

public class ReturnStatement extends ExpressionStatement {
   public static ReturnStatement create(Expression expr) {
      ReturnStatement st = new ReturnStatement();
      st.operator = "return";
      st.setProperty("expression", expr);
      return st;
   }

   public void start() {
      super.start();

      Object returnType;
      AbstractMethodDefinition method = getEnclosingMethod();
      Object methodReturnType;

      if (expression != null && method != null && method.type != null &&
          (methodReturnType = method.type.getTypeDeclaration()) != null) {

         // Need to propagate our type to the lambda expressions before we can accurately get our type.
         expression.setInferredType(methodReturnType);

         returnType = expression.getGenericType();
         // Verified at least that non-assignmentSemantics are too strict, i.e. method declared as char returning 0.
         if (returnType != null && !ModelUtil.isAssignableFrom(methodReturnType, returnType, true, null, getLayeredSystem())) {
            if (!ModelUtil.hasUnboundTypeParameters(returnType)) {
               displayTypeError("Type mismatch - method return type: " + ModelUtil.getTypeName(methodReturnType, true, true) + " does not match expression type: " + ModelUtil.getTypeName(returnType, true, true) + " for: ");
               returnType = expression.getGenericType();
               ModelUtil.isAssignableFrom((methodReturnType = method.type.getTypeDeclaration()), returnType, true, null, getLayeredSystem());
            }
         }
      }

      if (expression == null && method != null) {
         JavaType retType = method.getReturnJavaType();
         if (retType != null && !retType.isVoid())
            displayError("Method: ", method.name, " ", " must return type: ", retType.toString());
      }
   }

   public ExecResult exec(ExecutionContext ctx) {
      if (expression != null)
         ctx.currentReturnValue = expression.eval(null, ctx);
      return ExecResult.Return;
   }

   public boolean canInsertStatementBefore(Expression fromExpr) {
      return false;
   }

   public void addReturnStatements(List<Statement> res) {
      res.add(this);
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("return ");
      if (expression == null)
         sb.append("<null>");
      sb.append(expression.toString());
      return sb.toString();
   }
}
