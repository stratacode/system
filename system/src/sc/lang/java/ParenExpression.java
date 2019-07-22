/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.BindingDirection;
import sc.lang.SemanticNodeList;

import java.util.List;
import java.util.Set;

public class ParenExpression extends ChainedExpression {
   public Object eval(Class expectedType, ExecutionContext ctx) {
      return expression.eval(expectedType, ctx);
   }

   public static ParenExpression create(Expression ex) {
      ParenExpression pe = new ParenExpression();
      pe.setProperty("expression", ex);
      return pe;
   }

   /**
    *  For binding purposes, this acts like a pass-thru.  Since we are generating a method call
    *  we do not even have to insert parens since they are implied.
    */
   public String getBindingTypeName() {
      return expression.getBindingTypeName();
   }

   /**
    * Produces binding arguments for the operator and the two nested bindings
    */
   public void transformBindingArgs(SemanticNodeList<Expression> bindArgs, BindDescriptor bd) {
      expression.transformBindingArgs(bindArgs, bd);
   }

   public void evalBindingArgs(List<Object> bindArgs, boolean isStatic, Class expectedType, ExecutionContext ctx) {
      expression.evalBindingArgs(bindArgs, isStatic, expectedType, ctx);
   }

   /**
    * Propagates the binding information to nested expressions
    */
   public void setBindingInfo(BindingDirection dir, Statement dest, boolean nested) {
      super.setBindingInfo(dir, dest, nested);
      expression.setBindingInfo(dir, dest, nested);
   }

   public Object evalBinding(Class expectedType, ExecutionContext ctx) {
      return expression.evalBinding(expectedType, ctx);
   }

   public int suggestCompletions(String prefix, Object currentType, ExecutionContext ctx, String command, int cursor, Set<String> candidates, Object continuation, int max) {
      if (expression != null)
         return expression.suggestCompletions(prefix, currentType, ctx, command, cursor, candidates, continuation, max);
      return -1;
   }

   public Object getParentReferenceType() {
      if (expression != null)
         return expression.getParentReferenceType();
      return null;
   }

   /** Only implemented for subclasses that can return needsSetMethod=true.  For "a.b.c" returns "c" */
   public String getReferencePropertyName() {
      if (expression != null)
         return expression.getReferencePropertyName();
      return null;
   }

   /** Only implemented for subclasses that can return needsSetMethod=true.  For "a.b.c" returns an expr for "a.b" */
   public Expression getParentReferenceTypeExpression() {
      if (expression != null)
         return expression.getParentReferenceTypeExpression();
      return null;
   }

   public boolean isSimpleReference() {
      if (expression != null)
         return expression.isSimpleReference();
      return false;
   }

   public boolean needsSetMethod() {
      if (expression != null)
         return expression.needsSetMethod();
      return false;
   }

   public void setAssignment(boolean assign) {
      if (expression != null)
         expression.setAssignment(assign);
   }

   public void setValue(Object value, ExecutionContext ctx) {
      if (expression != null)
         expression.setValue(value, ctx);
   }

   public Object getGenericType() {
      return expression.getGenericType();
   }

   public String toGenerateString() {
      StringBuilder sb = new StringBuilder();
      sb.append("(");
      if (expression != null)
         sb.append(expression.toGenerateString());
      sb.append(")");
      return sb.toString();
   }

   public boolean isIncompleteStatement() {
      return false;
   }
}
