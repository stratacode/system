/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.dyn.DynUtil;
import sc.lang.ILanguageModel;
import sc.lang.SemanticNodeList;
import sc.type.CTypeUtil;

import java.util.List;

/* ~ and ! for this class, subclasses are +, -, ++, -- (for prefix) and ++ and -- for postfix. */
public class UnaryExpression extends ChainedExpression {
   public String operator;

   public boolean isPrefix() {
      return true;
   }

   public void init() {
      if (initialized)
         return;

      if (isIncrementOperator() && expression != null)
         expression.setAssignment(true);

      super.init();
   }

   public Object eval(Class expectedType, ExecutionContext ctx) {
      if (bindingDirection != null) {
         return initBinding(expectedType, ctx);
      }
      
      Object value = expression.eval(null, ctx);
      Object newValue = DynUtil.evalUnaryExpression(operator, null, value);
      if (isIncrementOperator()) {
         expression.setValue(newValue, ctx);
         return isPostfixUnaryOperator() ? value : newValue;
      }
      else
         return newValue;
   }

   private boolean isPostfixUnaryOperator() {
      return this instanceof PostfixUnaryExpression;
   }

   /** ++ or -- pre/post */
   private boolean isIncrementOperator() {
      return operator.equals("++") || operator.equals("--");
   }

   public Object getTypeDeclaration() {
      if (operator.charAt(0) == '!')
         return Boolean.TYPE;
      else
         return super.getTypeDeclaration();
   }
   public String getBindingTypeName() {
      return nestedBinding ? "unaryP" : "unary";
   }

   public void transformBindingArgs(SemanticNodeList<Expression> bindArgs, BindDescriptor bd) {
      bindArgs.add(StringLiteral.create(operator));
      bindArgs.add(createBindingParameters(false, expression));
   }

   public void evalBindingArgs(List<Object> bindArgs, boolean isStatic, Class expectedType, ExecutionContext ctx) {
      bindArgs.add(operator);
      bindArgs.add(evalBindingParameters(expectedType, ctx, expression));
   }

   public boolean transform(ILanguageModel.RuntimeType runtime) {
      boolean any = false;
      boolean removed = false;

      // The ++ and -- operators require special handling for get/set conversion
      if (isIncrementOperator()) {
         String op = String.valueOf(operator.charAt(0));
         if (expression.needsSetMethod()) {

            // If we can insert statements ahead of our parent statement, (i.e. no one uses this value), we
            // can convert this to simply: setX(getX() +/- 1
            if (canInsertStatementBefore(this)) {
               if (expression.isSimpleReference()) {
                  Expression retVal = ArithmeticExpression.create((Expression) expression.deepCopy(CopyNormal, null), op, IntegerLiteral.create(1));
                  // Can't convert it to a set method when it is a child of the post/prefix unary
                  parentNode.replaceChild(this, expression);
                  removed = true;
                  expression.convertToSetMethod(retVal);
                  retVal.transform(runtime);
               }
               else {
                  BlockStatement st = new BlockStatement();
                  st.addStatementAt(0, VariableStatement.create(JavaType.createJavaTypeFromName(ModelUtil.getCompiledClassName(expression.getParentReferenceType())), "_refTmp",
                                    "=", expression.getParentReferenceTypeExpression()));
                  IdentifierExpression setCall;
                  String upperPropName = CTypeUtil.capitalizePropertyName(expression.getReferencePropertyName());
                  st.addStatementAt(1, setCall = IdentifierExpression.create("_refTmp", "set" + upperPropName));
                  SemanticNodeList setArgs = new SemanticNodeList(1);
                  IdentifierExpression getCall = IdentifierExpression.create("_refTmp", "get" + upperPropName);
                  getCall.setProperty("arguments", new SemanticNodeList(0));
                  setArgs.add(ArithmeticExpression.create(getCall, op, IntegerLiteral.create(1)));
                  setCall.setProperty("arguments", setArgs);
                  // Convert to { <referenceType _ref = <referenceExpr>; _ref.setX(_ref.getX()+/-1); }
                  parentNode.replaceChild(this, st);
                  st.transform(runtime);
                  removed = true;
               }
            }
            else {
               boolean isStatic = isStatic();
               IdentifierExpression call = IdentifierExpression.create("sc", "dyn", "DynUtil",
                       "evalPropertyIncr" +
                               (this instanceof PostfixUnaryExpression ? "Orig" : "") +
                               (isStatic ? "Static" : ""));
               SemanticNodeList args = new SemanticNodeList();
               call.setProperty("arguments", args);
               args.add(expression.getParentReferenceTypeExpression());
               args.add(StringLiteral.create(expression.getReferencePropertyName()));
               args.add(IntegerLiteral.create(op.equals("-") ? -1 : 1));
               CastExpression castExpr = new CastExpression();
               castExpr.setProperty("type", JavaType.createObjectType(expression.getTypeDeclaration()));
               castExpr.setProperty("expression", call);
               parentNode.replaceChild(this, castExpr);
               castExpr.transform(runtime);
               removed = true;
            }
         }
      }
      if (!removed) {
         if (super.transform(runtime))
            any = true;
      }
      else
         any = true;
      return any;
   }

   public String toGenerateString() {
      StringBuilder sb = new StringBuilder();
      if (operator == null || expression == null)
         return "<uninitialized unary expression>";
      if (isPrefix()) {
         sb.append(operator);
         sb.append(expression.toGenerateString());
      }
      else {
         sb.append(expression.toGenerateString());
         sb.append(operator);
      }
      return sb.toString();
   }
}
