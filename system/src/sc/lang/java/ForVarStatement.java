/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ISemanticNode;
import sc.lang.SemanticNodeList;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Iterator;

public class ForVarStatement extends ForStatement implements IVariable {
   // final or Annotation
   public SemanticNodeList<Object> variableModifiers;
   public JavaType type;
   public String identifier;
   public Expression expression;

   public static ForVarStatement create(JavaType type, String identifier, Expression loopExpr, Statement body) {
      ForVarStatement newFor = new ForVarStatement();
      newFor.setProperty("type", type);
      newFor.identifier = identifier;
      newFor.setProperty("expression", loopExpr);
      newFor.setProperty("statement", body);
      return newFor;
   }

   public Object findMember(String name, EnumSet<MemberType> mtype, Object fromChild, Object refType, TypeContext ctx, boolean skipIfaces) {
      if (mtype.contains(MemberType.Variable) && identifier != null && identifier.equals(name))
         return this;

      return super.findMember(name, mtype, this, refType, ctx, skipIfaces);
   }

   public Object getTypeDeclaration() {
      return type == null ? null : type.getTypeDeclaration();
   }

   public String getGenericTypeName(Object resultType, boolean includeDims) {
      return type == null ? null : type.getGenericTypeName(resultType, includeDims);
   }

   public String getAbsoluteGenericTypeName(Object resultType, boolean includeDims) {
      return type == null ? null : type.getAbsoluteGenericTypeName(resultType, includeDims);
   }

   public String getVariableName() {
      return identifier;
   }

   public void start() {
      super.start();
      if (type != null && expression != null) {
         Object exprType = expression.getGenericType();
         if (exprType != null && !ModelUtil.isArray(exprType) && !ModelUtil.isAssignableFrom(Iterable.class, exprType)) {
            displayTypeError("For loop - expression after the ':' must be an array or a java.lang.Iterable");
         }
         else {
            JavaModel model = getJavaModel();
            Object componentType = null;
            if (model != null)
               componentType = ModelUtil.getArrayOrListComponentType(model, exprType);
            Object varType = type.getTypeDeclaration();
            if (componentType != null && !ModelUtil.isAssignableFrom(varType, componentType) && varType != null) {
               displayTypeError("The 'for' statement's variable type: " + ModelUtil.getTypeName(varType) + " does not match the collection's component type: " + ModelUtil.getTypeName(componentType) + "\n:   ");
               boolean removeMe = ModelUtil.isAssignableFrom(varType, componentType);
            }
         }
      }
   }

   public ExecResult exec(ExecutionContext ctx) {
      ctx.pushFrame(false, 1);
      try {
         ctx.defineVariable(identifier, null);
         Object arrObj = expression.eval(null, ctx);
         if (arrObj instanceof Object[]) {
            Object[] arrVal = (Object[]) arrObj;
            for (int i = 0; i < arrVal.length; i++) {
               ctx.setVariable(identifier, arrVal[i]);
               switch (statement.exec(ctx)) {
                  case Return:
                     return ExecResult.Return;
                  case Break:
                     if (ctx.currentLabel == null || isLabeled(ctx.currentLabel))
                        return ExecResult.Next;
                     return ExecResult.Break;
                  case Continue:
                     if (ctx.currentLabel == null || isLabeled(ctx.currentLabel))
                        continue;
                     return ExecResult.Continue;
                  // Fall through - next
               }
            }
         }
         else if (arrObj instanceof Iterable) {
            Iterable itable = (Iterable) arrObj;
            Iterator it = itable.iterator();
            while (it.hasNext()) {
               ctx.setVariable(identifier, it.next());
               switch (statement.exec(ctx)) {
                  case Return:
                     return ExecResult.Return;
                  case Break:
                     if (ctx.currentLabel == null || isLabeled(ctx.currentLabel))
                        return ExecResult.Next;
                     return ExecResult.Break;
                  case Continue:
                     if (ctx.currentLabel == null || isLabeled(ctx.currentLabel))
                        continue;
                     return ExecResult.Continue;
                  // Fall through - next
               }
            }
         }
         else if (arrObj == null) throw new NullPointerException("For expression null: " + toDefinitionString());
         else throw new IllegalArgumentException("Loop variable of incorrect type: " + arrObj.getClass() + " for: " + toDefinitionString());
      }
      finally {
         ctx.popFrame();
      }
      return ExecResult.Next;
   }

   public void refreshBoundTypes(int flags) {
      super.refreshBoundTypes(flags);
      if (type != null)
         type.refreshBoundType(flags);
      if (expression != null)
         expression.refreshBoundTypes(flags);
   }

   public int transformTemplate(int ix, boolean statefulContext) {
      ix = super.transformTemplate(ix, statefulContext);
      if (type != null)
         ix = type.transformTemplate(ix, statefulContext);
      if (expression != null)
         ix = expression.transformTemplate(ix, statefulContext);
      return ix;
   }

   private int countNestedForVarLoops() {
      ISemanticNode parent = parentNode;
      int ct = 0;
      while (parent != null && !(parent instanceof AbstractMethodDefinition) && !(parent instanceof BodyTypeDeclaration)) {
         if (parent instanceof ForVarStatement)
            ct++;
         parent = parent.getParentNode();
      }
      return ct;
   }

   /** Javascript does not support the foo(x : collection) syntax of Java so this will convert it to a regular ForControlStatement which is supported */
   public Statement transformToJS() {
      Object dataType = expression.getTypeDeclaration();
      boolean isArray = ModelUtil.isArray(dataType);

      expression.transformToJS();
      statement.transformToJS();

      ForControlStatement newFor = new ForControlStatement();
      SemanticNodeList init = new SemanticNodeList();
      String loopVar = "_i";
      String arrVal = "_lv";
      int ct = countNestedForVarLoops();
      if (ct != 0) {
         loopVar += ct;
         arrVal += ct;
      }
      SemanticNodeList<Definition> forInitVal = new SemanticNodeList<Definition>();
      Statement toInsert;
      Statement toPrepend;
      if (isArray) {
         VariableStatement vs = VariableStatement.create(ClassType.createStarted(Object.class, "var"), loopVar, "=", IntegerLiteral.create(0));
         forInitVal.add(vs);
         newFor.setProperty("forInit", forInitVal);
         Expression loopExpr = (Expression) expression.deepCopy(ISemanticNode.CopyNormal, null);
         toPrepend = VariableStatement.create(ClassType.createStarted(Object.class, "var"), arrVal, "=", loopExpr);
         newFor.setProperty("condition", ConditionalExpression.create(IdentifierExpression.create(loopVar), "<", IdentifierExpression.create(arrVal, "length")));
         SemanticNodeList<Expression> repeatVal = new SemanticNodeList<Expression>();
         repeatVal.add(PostfixUnaryExpression.create(loopVar, "++"));
         newFor.setProperty("repeat", repeatVal);
         toInsert = VariableStatement.create(ClassType.createStarted(Object.class, "var"), identifier, "=", SelectorExpression.create((Expression) expression.deepCopy(ISemanticNode.CopyNormal, null), ArraySelector.create(IdentifierExpression.create(loopVar))));
      }
      else {
         SelectorExpression selExpr;
         SemanticNodeList args = new SemanticNodeList();
         // Selector expressions can't chain off of other selector expressions so need to use the existing one and just add to it.
         if (expression instanceof SelectorExpression) {
            selExpr = (SelectorExpression) expression.deepCopy(ISemanticNode.CopyNormal, null);
            selExpr.selectors.add(VariableSelector.create("iterator", args));
         }
         else {
            selExpr = SelectorExpression.create((Expression) expression.deepCopy(ISemanticNode.CopyNormal, null), VariableSelector.create("iterator", args));
         }

         VariableStatement vs = VariableStatement.create(ClassType.createStarted(Object.class, "var"), loopVar, "=", selExpr);
         forInitVal.add(vs);
         newFor.setProperty("forInit", forInitVal);
         newFor.setProperty("condition", IdentifierExpression.createMethodCall(new SemanticNodeList(), loopVar, "hasNext"));
         toInsert = VariableStatement.create(ClassType.createStarted(Object.class, "var"), identifier, "=", IdentifierExpression.createMethodCall(new SemanticNodeList(), loopVar, "next"));
         toPrepend = null;
      }
      BlockStatement bst;
      if (statement instanceof BlockStatement) {
         bst = (BlockStatement) statement;
         bst.addStatementAt(0, toInsert);
      }
      else {
         bst = new BlockStatement();
         bst.addStatementAt(0, toInsert);
         bst.addStatementAt(1, statement);
      }
      newFor.setProperty("statement", bst);

      if (toPrepend != null) {
         BlockStatement outerBS = new BlockStatement();
         outerBS.addStatementAt(0, toPrepend);
         outerBS.addStatementAt(1, newFor);
         parentNode.replaceChild(this, outerBS);
         return outerBS;
      }
      else {
         parentNode.replaceChild(this, newFor);
         return newFor;
      }
   }

}
