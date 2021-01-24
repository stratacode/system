/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.BindingDirection;
import sc.lang.SemanticNodeList;
import sc.type.Type;

import java.util.List;
import java.util.Set;

public class CastExpression extends ChainedExpression {
   public JavaType type;

   public static CastExpression create(String typeName, Expression chainedExpression) {
      // Check if this typeName is a primitive type.  The grammar treats PrimitiveType and Type slightly differently
      // so it is important that we get the right one.
      Type primType = Type.getPrimitiveType(typeName);
      return create(primType != null ? PrimitiveType.create(typeName) : ClassType.create(typeName), chainedExpression);
   }
   public static CastExpression create(JavaType type, Expression chainedExpression) {
      CastExpression castExpr = new CastExpression();
      castExpr.setProperty("type", type);
      // Cast cannot parse directly on a non-unary expression.  If you have (double) -2 / foo, it ends up parsing
      // it as ((double) -2 ) / foo.  For convenience sake, we'll wrap automatically in here if given something too
      // complicated for the cast in a paren expression  For convenience sake, we'll wrap automatically in here if given something too
      if (!(chainedExpression instanceof UnaryExpression) && !(chainedExpression instanceof ParenExpression) && !(chainedExpression instanceof IdentifierExpression) &&
          !(chainedExpression instanceof SelectorExpression) && !(chainedExpression instanceof AbstractLiteral))
         chainedExpression = ParenExpression.create(chainedExpression);
      castExpr.setProperty("expression", chainedExpression);
      return castExpr;
   }

   public Object eval(Class expectedType, ExecutionContext ctx) {
      if (bindingDirection != null)
         return initBinding(expectedType, ctx);
      
      Object val = expression.eval(expectedType, ctx);
      Object theClass = type.getTypeDeclaration();
      return ModelUtil.evalCast(theClass, val);
   }

   public Object getTypeDeclaration() {
      if (type == null)
         return null;
      return type.getTypeDeclaration();
   }

   /**
    *  For binding purposes, this acts like a pass-thru.
    *  we do not even have to insert parens since they are implied.
    */
   public String getBindingTypeName() {
      return nestedBinding ? "castP" : "cast";
   }

   /**
    * Produces binding arguments for the operator and the two nested bindings
    */
   public void transformBindingArgs(SemanticNodeList<Expression> bindArgs, BindDescriptor bd) {
      bindArgs.add(ClassValueExpression.create(type.getFullBaseTypeName(), type.arrayDimensions));
      // Passing our expression as the binding parameter directly.
      bindArgs.add(expression);
   }

   public void evalBindingArgs(List<Object> bindArgs, boolean isStatic, Class expectedType, ExecutionContext ctx) {
      bindArgs.add(type.getRuntimeClass());
      // Passing our expression as the binding parameter directly.
      bindArgs.add(expression.evalBinding(expectedType, ctx));
   }

   /**
    * Propagates the binding information to nested expressions
    */
   public void setBindingInfo(BindingDirection dir, Statement dest, boolean nested) {
      super.setBindingInfo(dir, dest, nested);
      if (expression != null)
         expression.setBindingInfo(bindingDirection, bindingStatement, true);
   }

   public boolean refreshBoundTypes(int flags) {
      boolean res = super.refreshBoundTypes(flags);
      if (type != null) {
         if (type.refreshBoundType(flags))
            res = true;
      }
      return res;
   }

   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      super.addDependentTypes(types, mode);
      if (type != null)
         type.addDependentTypes(types, mode);
   }

   public void setAccessTimeForRefs(long time) {
      super.setAccessTimeForRefs(time);
      if (type != null)
         type.setAccessTimeForRefs(time);
   }

   public Statement transformToJS() {
      expression.transformToJS();

      // Because JS does not have an explicit integer type, for a cast to (int) we'll do a Math.floor instead
      // to strip away any fractional part.
      if (type instanceof PrimitiveType && ModelUtil.isInteger(((PrimitiveType) type).getRuntimeClass())) {
         SemanticNodeList<Expression> args = new SemanticNodeList<Expression>();
         args.add(expression);
         IdentifierExpression toInt = IdentifierExpression.createMethodCall(args, "Math.floor");
         parentNode.replaceChild(this, toInt);
         return toInt;
      }
      else {
         Statement repl = ParenExpression.create(expression);
         parentNode.replaceChild(this, repl);
         return repl;
      }
   }

   public String toGenerateString() {
      StringBuilder sb = new StringBuilder();
      if (type == null || expression == null)
         return ("<uninitialized CastExpression>");
      sb.append("(");
      sb.append(type.toGenerateString());
      sb.append(") ");
      sb.append(expression.toGenerateString());
      return sb.toString();
   }

   public int suggestCompletions(String prefix, Object currentType, ExecutionContext ctx, String command, int cursor, Set<String> candidates, Object continuation, int max) {
      if (expression != null)
         return super.suggestCompletions(prefix, currentType, ctx, command, cursor, candidates, continuation, max);
      if (type != null)
         return type.suggestCompletions(prefix, currentType, ctx, command, cursor, candidates, continuation, max);
      return -1;
   }

   public boolean setInferredType(Object infType, boolean finalType) {
      if (expression != null) {
         Object typeDecl = type.getTypeDeclaration();
         // TODO: we might update the type parameters here but since they are not shared I think it's ok not to make a copy
         if (typeDecl instanceof ParamTypeDeclaration) {
            ((ParamTypeDeclaration) typeDecl).writable = true;
         }
         return expression.setInferredType(typeDecl, finalType);
      }
      return false;
   }

   // Don't add an extra level of indent for children of a cast
   public int getChildNestingDepth() {
      if (childNestingDepth != -1)
         return childNestingDepth;
      if (parentNode == null)
         return 0;
      return parentNode.getChildNestingDepth();
   }
}
