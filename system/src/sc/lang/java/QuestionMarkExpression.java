/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.BindingDirection;
import sc.lang.ISrcStatement;
import sc.lang.SemanticNodeList;

import java.util.List;
import java.util.Set;

public class QuestionMarkExpression extends Expression {
   public Expression condition;
   public Expression trueChoice;
   public Expression falseChoice;

   public void start() {
      if (started) return;
      super.start();

      // Check for coerceable types
      getTypeDeclaration();
   }

   public boolean setInferredType(Object type, boolean finalType) {
      boolean res = false;
      if (trueChoice != null)
         res = trueChoice.setInferredType(type, finalType);
      if (falseChoice != null)
         res |= falseChoice.setInferredType(type, finalType);
      return res;
   }

   public boolean propagatesInferredType(Expression child) {
      if (!hasInferredType())
         return false;
      return child == trueChoice || child == falseChoice;
   }

   public Object eval(Class expectedType, ExecutionContext ctx) {
      if (bindingDirection != null)
         return initBinding(expectedType, ctx);
      
      Boolean bval = (Boolean) condition.eval(Boolean.class, ctx);
      if (bval)
         return trueChoice.eval(expectedType, ctx);
      else
         return falseChoice.eval(expectedType, ctx);
   }

   public Object getTypeDeclaration() {
      Object trueType = trueChoice == null ? null : trueChoice.getGenericType(),
            falseType = falseChoice == null ? null : falseChoice.getGenericType();
      try {
         // Not resolved
         if (trueType == null || falseType == null)
            return null;
         return ModelUtil.coerceTypes(getLayeredSystem(), trueType, falseType);
      }
      catch (IllegalArgumentException exc) {
         // Don't care about the type of the question mark in the reverse only case since we are not returning anything.
         if (bindingDirection == BindingDirection.REVERSE)
            return Object.class;
         displayError("Types used in question mark operator do not match: ", " " + falseType + " != " + trueType);
         try {
            return ModelUtil.coerceTypes(getLayeredSystem(), trueType, falseType);
         }
         catch (IllegalArgumentException exc2) {
         }
      }
      return null;
   }

   public String getBindingTypeName() {
      return nestedBinding ? "ternaryP" : "ternary";
   }

   /**
    * Produces binding arguments for the operator and the two nested bindings
    */
   public void transformBindingArgs(SemanticNodeList<Expression> bindArgs, BindDescriptor bd) {
      bindArgs.add(createBindingParameters(false, condition, trueChoice, falseChoice));
   }

   public void evalBindingArgs(List<Object> bindArgs, boolean isStatic, Class expectedType, ExecutionContext ctx) {
      bindArgs.add(evalBindingParameters(expectedType, ctx, condition, trueChoice, falseChoice));
   }

   /**
    * Propagates the binding information to nested expressions
    */
   public void setBindingInfo(BindingDirection dir, Statement dest, boolean nested) {
      super.setBindingInfo(dir, dest, nested);
      BindingDirection propBD;
      if (dir.doReverse()) {
         propBD = dir.doForward() ? BindingDirection.FORWARD : BindingDirection.NONE;
      }
      else
         propBD = dir;
      if (condition != null)
         condition.setBindingInfo(propBD, bindingStatement, true);
      if (trueChoice != null)
         trueChoice.setBindingInfo(bindingDirection, bindingStatement, true);
      if (falseChoice != null)
         falseChoice.setBindingInfo(bindingDirection, bindingStatement, true);
   }

   public void visitTypeReferences(CycleInfo info, TypeContext ctx) {
      info.visit(condition, ctx, false);
      info.visit(trueChoice, ctx, false);
      info.visit(falseChoice, ctx, true);
      info.remove(condition);
      info.remove(trueChoice);
   }

   public int suggestCompletions(String prefix, Object currentType, ExecutionContext ctx, String command, int cursor, Set<String> candidates, Object continuation, int max) {
      if (falseChoice != null)
         return falseChoice.suggestCompletions(prefix, currentType, ctx, command, cursor, candidates, continuation, max);
      if (command.trim().endsWith(":")) {
         ModelUtil.suggestTypes(getJavaModel(), prefix, "", candidates, true, false, max);
         return command.length();
      }
      if (trueChoice != null)
         return trueChoice.suggestCompletions(prefix, currentType, ctx, command, cursor, candidates, continuation, max);
      if (condition != null)
         return condition.suggestCompletions(prefix, currentType, ctx, command, cursor, candidates, continuation, max);
      return -1;
   }

   public void changeExpressionsThis(TypeDeclaration td, TypeDeclaration outer, String newName) {
      condition.changeExpressionsThis(td, outer, newName);
      trueChoice.changeExpressionsThis(td, outer, newName);
      falseChoice.changeExpressionsThis(td, outer, newName);
   }

   @Override
   public boolean isStaticTarget() {
      return condition.isStaticTarget() && trueChoice.isStaticTarget() && falseChoice.isStaticTarget();
   }

   public boolean refreshBoundTypes(int flags) {
      boolean res = false;
      if (condition != null)
         res = condition.refreshBoundTypes(flags);
      if (trueChoice != null) {
         if (trueChoice.refreshBoundTypes(flags))
            res = true;
      }
      if (falseChoice != null) {
         if (falseChoice.refreshBoundTypes(flags))
            res = true;
      }
      return res;
   }

   public int transformTemplate(int ix, boolean statefulContext) {
      if (condition != null)
         ix = condition.transformTemplate(ix, statefulContext);
      if (trueChoice != null)
         ix = trueChoice.transformTemplate(ix, statefulContext);
      if (falseChoice != null)
         ix = falseChoice.transformTemplate(ix, statefulContext);
      return ix;
   }

   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      if (condition != null)
         condition.addDependentTypes(types, mode);
      if (trueChoice != null)
         trueChoice.addDependentTypes(types, mode);
      if (falseChoice != null)
         falseChoice.addDependentTypes(types, mode);
   }

   public void setAccessTimeForRefs(long time) {
      if (condition != null)
         condition.setAccessTimeForRefs(time);
      if (trueChoice != null)
         trueChoice.setAccessTimeForRefs(time);
      if (falseChoice != null)
         falseChoice.setAccessTimeForRefs(time);
   }

   public Statement transformToJS() {
      if (condition != null)
         condition.transformToJS();
      if (trueChoice != null)
         trueChoice.transformToJS();
      if (falseChoice != null)
         falseChoice.transformToJS();
      return this;
   }

   public static Expression create(Expression cond, Expression trueC, Expression falseC) {
      QuestionMarkExpression qme = new QuestionMarkExpression();
      qme.setProperty("condition", cond);
      qme.setProperty("trueChoice", trueC);
      qme.setProperty("falseChoice", falseC);
      return qme;
   }

   public String toGenerateString() {
      StringBuilder sb = new StringBuilder();
      String condStr = condition.toGenerateString();
      sb.append(condStr);
      if (!condStr.endsWith(" "))
         sb.append(" ");
      sb.append("? ");
      if (trueChoice != null)
         sb.append(trueChoice.toGenerateString());
      else
         sb.append("null");
      sb.append(" : ");
      if (falseChoice != null)
         sb.append(falseChoice.toGenerateString());
      else
         sb.append("null");
      return sb.toString();
   }

   public boolean applyPartialValue(Object value) {
      return super.applyPartialValue(value);
   }

   public void addBreakpointNodes(List<ISrcStatement> res, ISrcStatement srcStatement) {
      super.addBreakpointNodes(res, srcStatement);
      if (condition != null)
         condition.addBreakpointNodes(res, srcStatement);
      if (trueChoice != null)
         trueChoice.addBreakpointNodes(res, srcStatement);
      if (falseChoice != null)
         falseChoice.addBreakpointNodes(res, srcStatement);
   }

   /**
    * If either one says it's HTML omit the escape.
    * TODO: should we rewrite the expression if one does and the other does not to move the escape so it wraps only the trueChoice or the falseChoice not both?
    */
   public boolean producesHtml() {
      if (trueChoice != null && trueChoice.producesHtml())
         return true;
      if (falseChoice != null && falseChoice.producesHtml())
         return true;
      return false;
   }
}
