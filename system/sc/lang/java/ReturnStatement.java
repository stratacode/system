/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

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
      AbstractMethodDefinition method;
      Object methodReturnType;

      if (expression != null && (returnType = expression.getGenericType()) != null && (method = getEnclosingMethod()) != null &&
          (methodReturnType = method.type.getTypeDeclaration()) != null &&
          // Verified at least that non-assignmentSemantics are too strict, i.e. method declared as char returning 0.
         !ModelUtil.isAssignableFrom(methodReturnType, returnType, true, null)) {
         if (!ModelUtil.hasUnboundTypeParameters(returnType)) {
            displayTypeError("Type mismatch - method return type: " + ModelUtil.getTypeName(methodReturnType, true, true) + " does not match expression type: " + ModelUtil.getTypeName(returnType, true, true) + " for: ");
            returnType = expression.getGenericType();
            ModelUtil.isAssignableFrom((methodReturnType = method.type.getTypeDeclaration()), returnType, true, null);
         }
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
}
