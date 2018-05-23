/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.BindingDirection;
import sc.dyn.DynUtil;
import sc.lang.ISemanticNode;
import sc.lang.ISrcStatement;
import sc.lang.SemanticNodeList;
import sc.parser.ParseUtil;
import sc.type.InverseOp;
import sc.type.PTypeUtil;
import sc.type.TypeUtil;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The role of this class is to build the expression tree, implementing the precedence rules from
 * a single string of unparenthesized two operator expressions.
 */
public class BinaryExpression extends Expression {

   // These are the source properties when this is parsed, computed otherwise
   public Expression firstExpr;
   public SemanticNodeList<BaseOperand> operands;

   // These are the source properties when isTreeNode=true, computed otherwise
   public transient Expression lhs;
   public transient String operator;
   /** The rhs is an Expression for most binary expressions but for instanceof it's a JavaType */
   public transient JavaSemanticNode rhs;
   public transient BinaryExpression rootExpression;

   transient boolean isTreeNode;   // True if this node is generated from code, not parsed
   transient boolean isNestedExpr; // True if this node is a child of another BinaryExpression

   enum OperatorType {
      Arithmetic, Conditional, InstanceOf, BooleanArithmetic
   }

   static OperatorType getOperatorType(String operator) {
      if (operator == null)
         return null;
      if (operator.equals("instanceof"))
         return OperatorType.InstanceOf;

      switch (operator.charAt(0)) {
         case '*':
         case '/':
         case '%':
         case '+':
         case '-':
            return OperatorType.Arithmetic;
         case '^':
            return OperatorType.BooleanArithmetic;
         case '<':
         case '>':
            if (operator.length() == 1 || operator.charAt(1) == '=')
               return OperatorType.Conditional;
            else
               return OperatorType.Arithmetic;
         case '=':
         case '!':
            return OperatorType.Conditional;
         case '&':
         case '|':
            if (operator.length() == 1)
               return OperatorType.BooleanArithmetic;
            else
               return OperatorType.Conditional;
         default:
            throw new UnsupportedOperationException();
      }
   }

   private void initExprTree() {
      // When our lhs, rhs and operand are set explicitly we don't compute them.
      if (isTreeNode) {
         // When we are created programmatically and there's a parent BinaryExpression, it will have accumulated our operands under its operands list so this node is not used as part of the semantic model.
         // If we build the model, the child nodes end up having the wrong parent.
         if (isNestedExpr)
            return;

         assert lhs != null && operator != null && rhs != null;
         setProperty("operands", new SemanticNodeList<BaseOperand>());
         firstExpr = getFirstExpression();
         addOperands(operands);
      }
      else {
         if (operands == null || operands.size() < 1)
            return;

         BaseOperand op = operands.get(0);
         operator = op.operator;
         // Do not regenerate anything or change the parents here since these are computed values.  If we change the parent's they do not point to the proper node in the semantic tree.
         setProperty("rhs", op.getRhs(), false, false);
         setProperty("lhs", firstExpr, false, false);

         for (int i = 1; i < operands.size(); i++) {
            op = operands.get(i);
            Expression newExpr;
            if (TypeUtil.operatorPrecedes(op.operator, operator)) {
               // TODO: Is there a case where an instanceof operator could be used here?
               BinaryExpression lastParent = this;
               // Seems wrong as we'd be using a type as the "lhs" of some other expression.
               BinaryExpression newParent = this;
               while (newParent.rhs instanceof BinaryExpression &&
                       TypeUtil.operatorPrecedes(op.operator, newParent.operator)) {
                  lastParent = newParent;
                  newParent = (BinaryExpression) newParent.rhs;
               }

               if (TypeUtil.operatorPrecedes(op.operator, newParent.operator)) {
                  if (newParent.rhs instanceof Expression) {
                     newExpr = createExpression((Expression) newParent.rhs, op);
                     newParent.setProperty("rhs", newExpr, false, false);
                  }
                  else if (newParent.rhs instanceof ClassType) {
                     ClassType newClassType = ((ClassType) newParent.rhs).deepCopy(ISemanticNode.CopyNormal, null);
                     newParent.setProperty("rhs", newClassType, false, false);
                  }
               }
               else {
                  newExpr = createExpression(newParent,  op);
                  lastParent.setProperty("rhs", newExpr, false, false);
               }
            }
            else {
               // Clone this node
               newExpr = createExpression(lhs, operator, rhs);

               // Make it the new "lhs" for this new "rhs"
               setProperty("lhs", newExpr, false, false);
               operator = op.operator;
               setProperty("rhs", op.getRhs(), false, false);
            }
         }
      }

   }

   public void init() {
      if (initialized) return;

      initExprTree();

      if (lhs != null)
         lhs.init();
      if (rhs != null)
         rhs.init();
      super.init();

      // If we get restarted, we need to reset all of this stuff
      if (bindingDirection != null)
         setBindingInfo(bindingDirection, bindingStatement, nestedBinding);
   }

   public void start() {
      if (started || inactive)
         return;

      //if (isNestedExpr) {
      //   return;
      //}
      super.start();

      if (lhs == null || rhs == null)
         return; // Partial values might not be initialized during fragment parsing

      // If we do this in the deepCopy it ends up breaking because we have not set the parentNode.
      if (getJavaModel() != null) {
         Class inferredType = getInferredType();
         if (inferredType != null) {
            lhs.setInferredType(inferredType, true);
            Expression rhsExpr = getRhsExpr();
            if (rhsExpr != null)
               rhsExpr.setInferredType(inferredType, true);
         }
      }

      lhs.start();
      rhs.start();

      /* Used to return here for nested exprs but would miss errors on nested expressions - eg. list + int - int
      if (isNestedExpr)
         return;
      */

      OperatorType type = getOperatorType(operator);

      Object lhsType, rhsType;
      switch (type) {
         case InstanceOf:
            break;
         case BooleanArithmetic:
            lhsType = lhs.getTypeDeclaration();
            rhsType = getRhsExpr().getTypeDeclaration();

            if (lhsType != null && rhsType != null) {
               boolean lhsIsBoolean = ModelUtil.isBoolean(lhsType);
               boolean rhsIsBoolean = ModelUtil.isBoolean(rhsType);

               if (!lhsIsBoolean || !rhsIsBoolean) {
                  boolean lhsIsInt = ModelUtil.isAnInteger(lhsType) || ModelUtil.isCharacter(lhsType);
                  boolean rhsIsInt = ModelUtil.isAnInteger(rhsType) || ModelUtil.isCharacter(rhsType);

                  if (!lhsIsInt || !rhsIsInt) {
                     getErrorRoot().displayError("Bitwise operator: " + operator + " types invalid: " + ModelUtil.getTypeName(lhsType) + " and " + ModelUtil.getTypeName(rhsType) + " both sides should be either boolean or integers for: ");
                  }
               }
            }
            break;
         case Arithmetic:

            /* Note: treating characters like numbers for arithmetic stuff because you can use them like ints */
            boolean lhsIsNumber = ModelUtil.isANumber(lhsType = lhs.getTypeDeclaration()) || ModelUtil.isCharacter(lhsType);
            boolean rhsIsNumber = ModelUtil.isANumber(rhsType = getRhsExpr().getTypeDeclaration()) || ModelUtil.isCharacter(rhsType);

            boolean lhsIsString = false;

            if (!lhsIsNumber)
               lhsIsString = ModelUtil.isString(lhsType);

            boolean rhsIsString = false;
            if (!rhsIsNumber)
               rhsIsString = ModelUtil.isString(rhsType);

            if (rhsIsString || lhsIsString) {
               if (!operator.equals("+"))
                  getErrorRoot().displayTypeError("String types can only use the '+' operator: ");

               if (bindingDirection != null && bindingDirection.doReverse()) {
                  getErrorRoot().displayError("Reverse bindings not allowed for String concatenation: ");
               }
            }
            else {
               // Don't display errors when types are not found.  Always use rootExpression here since we can't
               // generate non-root expressions.
               if (lhsType != null && rhsType != null) {
                  if (!lhsIsNumber || !rhsIsNumber) {
                     getErrorRoot().displayTypeError("Arithmetic operator - must be between number or string types: " + ModelUtil.getTypeName(lhsType) +
                                        " " + operator + " " + ModelUtil.getTypeName(rhsType) + " for: ");

                     // DEBUG:
                     rhsIsNumber = ModelUtil.isANumber(rhsType = getRhsExpr().getTypeDeclaration()) || ModelUtil.isCharacter(rhsType);
                  }
               }
            }

            break;
         case Conditional:
            break;
      }

      // Need to do this after we've started things.  isConstant needs to check @Constant and so identifiers need to be resolved.
      if (!isNestedExpr && !nestedBinding && bindingDirection != null && bindingDirection.doReverse() && rhs instanceof Expression && lhs.isConstant() == getRhsExpr().isConstant()) {
         getErrorRoot().displayError("Invalid expression with operator: " + operator + " for reverse binding expression (the '=:' operator) - one value in the equation must be constant, the other variable ");
      }
   }

   /* TODO: weird that these exprs get validated and processed but not started
   public void validate() {
      if (isNestedExpr)
         return;
      super.validate();
   }

   public void process() {
      if (isNestedExpr)
         return;
      super.process();
   }
   */

   private void addOperands(SemanticNodeList<BaseOperand> operands) {
      addLeftOperands(operands);
      addRightOperands(operands);
   }

   private Expression getFirstExpression() {
      if (lhs instanceof BinaryExpression)
         return ((BinaryExpression) lhs).getFirstExpression();
      return lhs;
   }

   private void addLeftOperands(SemanticNodeList<BaseOperand> operands) {
      if (lhs instanceof BinaryExpression) {
         BinaryExpression lhsBinaryExpr = (BinaryExpression) lhs;
         lhsBinaryExpr.isNestedExpr = true;
         lhsBinaryExpr.addOperands(operands);
      }
   }

   private void addRightOperands(SemanticNodeList<BaseOperand> operands) {
      if (rhs instanceof BinaryExpression) {
         BinaryExpression rhsExpr = (BinaryExpression) rhs;
         rhsExpr.isNestedExpr = true;
         Expression nextExpr = rhsExpr.getFirstExpression();
         addOperand(operands, operator, nextExpr, true);
         rhsExpr.addOperands(operands);
      }
      else
         addOperand(operands, operator, rhs, true);
   }

   private static void addOperand(SemanticNodeList<BaseOperand> operands, String op, Object rhs, boolean nested) {
      if (op.equals("instanceof"))
         operands.add(new InstanceOfOperand(op, (JavaType)rhs, !nested, !nested));
      else
         operands.add(new BinaryOperand(op, (Expression) rhs, !nested, !nested));
   }

   public Expression createExpression(Expression lhs, BaseOperand op) {
      String operator = op.operator;

      OperatorType type = getOperatorType(operator);

      BinaryExpression expr;

      switch (type) {
         case InstanceOf:
            expr = InstanceOfExpression.create(lhs, ((InstanceOfOperand) op).rhs, true);
            break;
         case Arithmetic:
         case BooleanArithmetic:
            expr = ArithmeticExpression.create(lhs, operator, ((BinaryOperand) op).rhs, true);
            break;
         case Conditional:
            expr = ConditionalExpression.create(lhs, operator, ((BinaryOperand) op).rhs, true);
            break;
         default:
            throw new UnsupportedOperationException();
      }
      expr.rootExpression = this;
      expr.parentNode = this;
      expr.isNestedExpr = true;
      return expr;
   }

   private Expression getErrorRoot() {
      if (rootExpression == null)
         return this;
      return rootExpression;
   }

   public Expression createExpression(Expression lhs, String op, Object rhs) {
      OperatorType type = getOperatorType(op);

      BinaryExpression expr;

      switch (type) {
         case InstanceOf:
            expr = InstanceOfExpression.create(lhs, (JavaType) rhs, true);
            break;
         case Arithmetic:
         case BooleanArithmetic:
            expr = ArithmeticExpression.create(lhs, op, (Expression) rhs, true);
            break;
         case Conditional:
            expr = ConditionalExpression.create(lhs, op, (Expression) rhs, true);
            break;
         default:
            throw new UnsupportedOperationException();
      }
      expr.rootExpression = this;
      expr.parentNode = this;
      expr.isNestedExpr = true;
      return expr;
   }

   public static Expression createMultiExpression(Expression[] exprs, String op) {
      BinaryExpression be = new BinaryExpression();
      be.operator = op;
      be.setProperty("firstExpr", exprs[0]);

      SemanticNodeList<BaseOperand> newOps = new SemanticNodeList<BaseOperand>();
      for (int i = 1; i < exprs.length; i++) {
         Expression elemExpr = exprs[i];
         if (elemExpr instanceof BinaryExpression) {
            BinaryExpression innerBE = (BinaryExpression) elemExpr;
            if (innerBE.firstExpr instanceof BinaryExpression)
               System.out.println("*** Error: need to flatten multi expression");
            newOps.add(new BinaryOperand(op, innerBE.firstExpr, true, true));
            for (int j = 0; j < innerBE.operands.size(); j++) {
               BinaryOperand innerBO = (BinaryOperand) innerBE.operands.get(j);
               if (innerBO.rhs instanceof BinaryExpression)
                  System.out.println("*** Error: need to flatten multi expression");
               newOps.add(new BinaryOperand(innerBO.operator, innerBO.rhs, true, true));
            }
         }
         // Question mark expressions cannot be concatenated directly - they need an extra set of () around them
         else if (elemExpr instanceof QuestionMarkExpression) {
            newOps.add(new BinaryOperand(op, ParenExpression.create(elemExpr), true, true));
         }
         else
            newOps.add(new BinaryOperand(op, elemExpr, true, true));
      }
      be.setProperty("operands", newOps);
      return be;
   }

   public void setOperator(String op) {
      if (operands.size() == 0) {
         operands.add(new BinaryOperand());
      }
      operands.get(0).operator = op;
      operator = op;
   }

   public void setRhs(JavaSemanticNode rhsVal) {
      JavaSemanticNode oldRhs = rhs;
      rhs = rhsVal;
      // Don't do this until we've sync'd up the two
      if (!initialized)
         return;
      if (operands == null)
         operands = new SemanticNodeList<BaseOperand>();
      if (operands.size() == 0) {
         operands.add(rhsVal instanceof Expression ? new BinaryOperand() : new InstanceOfOperand());
         if (operator != null)
            operands.get(0).operator = operator;
      }
      // Rhs always mirrors some element in the operands... this is a bit brute
      // force but should work fine.
      else {
         replaceOperand(oldRhs, rhsVal);
      }
      if (rootExpression != null)
         rootExpression.replaceOperand(oldRhs, rhsVal);
   }

   private void replaceOperand(JavaSemanticNode toReplace, JavaSemanticNode replaceWith) {
      if (lhs == toReplace)
         setProperty("lhs", replaceWith, false, true);
      else if (rhs == toReplace)
         setProperty("rhs", replaceWith, false, true);
      else if (firstExpr == toReplace)
         setProperty("firstExpr", replaceWith);
      else {
         for (int i = operands.size()-1; i >= 0; i--) {
            if (operands.get(i).getRhs() == toReplace || toReplace == null) {
               operands.get(i).setProperty("rhs", replaceWith);
               return;
            }
         }
      }
   }

   public Expression getRhsExpr() {
      return (Expression) rhs;
   }

   public JavaType getRhsType() {
      return (JavaType) rhs;
   }

   public Object eval(Class expectedType, ExecutionContext ctx) {
      if (bindingDirection != null)
         return initBinding(expectedType, ctx);

      OperatorType opType = getOperatorType(operator);
      if (opType == OperatorType.Arithmetic || opType == OperatorType.BooleanArithmetic) {
         // Passing null for expected type to the lhs and rhs since we may have to do a conversion from number to string
         return DynUtil.evalArithmeticExpression(operator, expectedType, lhs.eval(null, ctx), getRhsExpr().eval(null, ctx));
      }
      else if (opType == OperatorType.Conditional) {
         Object lhsVal;
         Object res = DynUtil.evalPreConditionalExpression(operator, lhsVal = lhs.eval(null, ctx));
         if (res != null)
            return res;
         return DynUtil.evalConditionalExpression(operator, lhsVal, getRhsExpr().eval(null, ctx));
      }
      else if (opType == OperatorType.InstanceOf)
         return ModelUtil.evalInstanceOf(lhs.eval(null, ctx), getRhsType().getTypeDeclaration(), null);
      throw new UnsupportedOperationException();
   }

   public Object getTypeDeclaration() {
      String op = operator;
      Expression rhsExpr = null, lhsExpr = null;
      if (op == null && operands != null && operands.size() > 0) {
         BaseOperand firstOp = operands.get(0);
         if (firstOp instanceof InstanceOfOperand)
            return Boolean.TYPE;
         else {
            BinaryOperand bop = (BinaryOperand) firstOp;
            op = bop.operator;
            rhsExpr = bop.rhs;
            lhsExpr = firstExpr;
         }
      }

      OperatorType opType = getOperatorType(op);
      if (opType == OperatorType.Arithmetic || opType == OperatorType.BooleanArithmetic) {
         if (rhsExpr == null)
            rhsExpr = getRhsExpr();
         if (lhsExpr == null)
            lhsExpr = lhs;
         return ArithmeticExpression.getExpressionType(lhsExpr, op, rhsExpr);
      }
      else if (opType == OperatorType.Conditional)
         return Boolean.TYPE;
      else if (opType == OperatorType.InstanceOf)
         return Boolean.TYPE;
      else if (opType == null)
         return null; // Could be a partial value
      throw new UnsupportedOperationException();
   }

   public void addOperator(String operator, Expression rhs) {
      if (operands == null)
         setProperty("operands", new SemanticNodeList<BaseOperand>());

      BinaryOperand op = new BinaryOperand();
      op.setProperty("rhs", rhs);
      op.operator = operator;
      operands.add(op);
   }

   public void setLhs(Expression l) {
      lhs = l;
   }

   public void setBindingInfo(BindingDirection dir, Statement dest, boolean nested) {
      super.setBindingInfo(dir, dest, nested);
      if (!initialized)
         init();
      if (lhs != null && rhs != null) {
         lhs.setBindingInfo(bindingDirection, bindingStatement, true);
         if (rhs instanceof Expression) {
            getRhsExpr().setBindingInfo(bindingDirection, bindingStatement, true);
            if (dir.doReverse()) {
               if (InverseOp.get(operator) == null)
                  getErrorRoot().displayError("Invalid reverse binding (=:) expression.  Operator: ", operator, " is not invertible in: ");
            }
         }
         else if (!operator.equals("instanceof"))
            System.err.println("Unknown rhs");
      }
      else
         System.out.println("*** no lhs or rhs for bound expression");
   }

   /**
    * Produces binding arguments for the operator and the two nested bindings
    */
   public void transformBindingArgs(SemanticNodeList<Expression> bindArgs, BindDescriptor bd) {
      if (operator.equals("instanceof")) {
         bindArgs.add(StringLiteral.create(operator));

         Expression rhsExpr = getRhsExprForInstanceof();
         bindArgs.add(createBindingParameters(false, lhs, rhsExpr));
      }
      else {
         bindArgs.add(StringLiteral.create(operator));
         bindArgs.add(createBindingParameters(false, lhs, getRhsExpr()));
      }
   }

   private Expression getRhsExprForInstanceof() {
      Expression rhsExpr;
      Object td = ((JavaType)rhs).getTypeDeclaration();
      String rtTypeName = ModelUtil.getRuntimeTypeName(td);
      if (ModelUtil.isDynamicType(td)) {
         SemanticNodeList<Expression> args = new SemanticNodeList<Expression>();
         args.add(StringLiteral.create(rtTypeName));
         rhsExpr = IdentifierExpression.createMethodCall(args, "sc.dyn.DynUtil.findType");
      }
      else
         rhsExpr = ClassValueExpression.create(rtTypeName);
      rhsExpr.setBindingInfo(bindingDirection, bindingStatement, true);
      rhsExpr.setParentNode(this);
      ParseUtil.initAndStartComponent(rhsExpr);
      return rhsExpr;

   }

   public void evalBindingArgs(List<Object> bindArgs, boolean isStatic, Class expectedType, ExecutionContext ctx) {
      if (operator.equals("instanceof")) {
         bindArgs.add(operator);
         bindArgs.add(evalBindingParameters(expectedType, ctx, lhs, getRhsExprForInstanceof()));
      }
      else {
         bindArgs.add(operator);
         bindArgs.add(evalBindingParameters(expectedType, ctx, lhs, getRhsExpr()));
      }
   }

   public String getBindingTypeName() {
      OperatorType opType = getOperatorType(operator);
      if (opType == OperatorType.Arithmetic || opType == OperatorType.BooleanArithmetic)
         return nestedBinding ? "arithP" : "arith";
      else if (opType == OperatorType.Conditional)
         return nestedBinding ? "conditionP" : "condition";
      else if (opType == OperatorType.InstanceOf) {
         return nestedBinding ? "conditionP" : "condition";
      }
      else
         throw new UnsupportedOperationException();
   }

   public int replaceChild(Object toReplace, Object replaceWith) {
      if (rootExpression != null)
         rootExpression.replaceOperand((JavaSemanticNode) toReplace, (JavaSemanticNode) replaceWith);

      int ix =  super.replaceChild(toReplace, replaceWith);

      if (toReplace == lhs) {
         setProperty("lhs", replaceWith, false, !isTreeNode);
         if (ix == -1)
            ix = PTypeUtil.getPropertyMapping(BinaryExpression.class, "lhs").getPropertyPosition();
      }
      if (toReplace == rhs) {
         setProperty("rhs", replaceWith, false, !isTreeNode);
         if (ix == -1)
            ix = PTypeUtil.getPropertyMapping(BinaryExpression.class, "rhs").getPropertyPosition();
      }

      if (ix != -1) {
         regenerateIfTracking(false);
      }
      return ix;
   }

   public void changeExpressionsThis(TypeDeclaration td, TypeDeclaration outer, String newName) {
      lhs.changeExpressionsThis(td, outer, newName);
      if (rhs instanceof Expression)
         getRhsExpr().changeExpressionsThis(td, outer, newName);
   }

   /** Try completing the last operand */
   public int suggestCompletions(String prefix, Object currentType, ExecutionContext ctx, String command, int cursor, Set<String> candidates, Object continuation) {
      if (operands.size() == 0)
         return -1;
      return operands.get(operands.size()-1).suggestCompletions(prefix, currentType, ctx, command, cursor, candidates, continuation);
   }

   public boolean applyPartialValue(Object partial) {
      return operands != null && operands.size() > 0 && operands.get(operands.size()-1).applyPartialValue(partial);
   }

   public void visitTypeReferences(CycleInfo info, TypeContext ctx) {
      info.visit(firstExpr, ctx);
      info.visitList(operands, ctx);
   }

   public boolean callsSuper() {
      return lhs.callsSuper() || getRhsExpr().callsSuper();
   }

   public boolean callsThis() {
      return lhs.callsThis() || getRhsExpr().callsThis();
   }

   public void refreshBoundTypes(int flags) {
      if (lhs != null)
         lhs.refreshBoundTypes(flags);
      if (rhs instanceof JavaType) {
         ((JavaType) rhs).refreshBoundType(flags);
      }
      else {
         Expression rhsExpr = getRhsExpr();
         if (rhsExpr != null)
            rhsExpr.refreshBoundTypes(flags);
      }
   }

   public int transformTemplate(int ix, boolean statefulContext) {
      if (firstExpr != null)
         ix = firstExpr.transformTemplate(ix, statefulContext);
      if (operands != null) {
         for (BaseOperand base:operands) {
            ix = base.transformTemplate(ix, statefulContext);
         }
      }
      return ix;
   }

   public void addDependentTypes(Set<Object> types) {
      if (lhs != null)
         lhs.addDependentTypes(types);
      if (rhs instanceof JavaType) {
         ((JavaType) rhs).addDependentTypes(types);
      }
      else {
         Expression rhsExpr = getRhsExpr();
         if (rhsExpr != null)
            rhsExpr.addDependentTypes(types);
      }
   }

   public boolean isStaticTarget() {
      return lhs.isStaticTarget() && getRhsExpr().isStaticTarget();
   }

   public Statement transformToJS() {

      //if (operands != null && operands.size() == 3 && lhs instanceof IdentifierExpression)  {
      //   IdentifierExpression idlhs = (IdentifierExpression) lhs;
      //   if (idlhs.identifiers.size() == 1 && idlhs.identifiers.get(0).equals("c"))
      //      System.out.println("---");
      //}

      // TODO: Right now we are transforming the firstExpr and operands - the way the binary expression was parsed.
      // but it seems like it would be easier and more robust in some ways to do it the other way around - i.e. transform the lhs, rhs.  Otherwise, we are not
      // transforming the expression nodes we create.  We also have to do the getRealLhs method which only works on leaf nodes.
      if (firstExpr != null)
         firstExpr.transformToJS();

      boolean needsParenWrapper = false;
      if (operands != null) {
         int numOps = operands.size();
         // Because this operation is transformational on the original semantic expression, we need to manipulate them instead
         // of the pre-computed rootExpression and rhs expr.
         for (int i = 0; i < numOps; i++) {
            BaseOperand bo = operands.get(i);
            if (bo instanceof InstanceOfOperand) {
               JavaType rhsType = ((InstanceOfOperand) bo).rhs;
               rhsType.transformToJS();

               int ndim = rhsType.getNdims();
               Object rhsTypeDecl = rhsType.getTypeDeclaration();
               // Special case for instanceof Class - we use an internal function because Number, String etc. should match here and they do not extend jv_Object
               boolean isClassType = ModelUtil.sameTypes(rhsTypeDecl, Class.class);
               if (ndim != -1 || ModelUtil.isInterface(rhsTypeDecl) || ModelUtil.isNumber(rhsTypeDecl) ||
                   ModelUtil.isAssignableFrom(String.class, rhsTypeDecl) || ModelUtil.isAssignableFrom(Boolean.class, rhsTypeDecl) || isClassType) {
                  IdentifierExpression iexpr = IdentifierExpression.create(isClassType ? "sc_instanceOfClass" : ndim == -1 ? "sc_instanceOf" : "sc_arrayInstanceOf");
                  SemanticNodeList<Object> snl = new SemanticNodeList<Object>();
                  if (i == 0) {
                     snl.add(firstExpr);
                  }
                  else {
                     snl.add(((BinaryOperand) operands.get(i-1)).rhs);
                  }
                  snl.add(IdentifierExpression.create(rhsType.getFullBaseTypeName()));
                  if (ndim != -1)
                     snl.add(IntegerLiteral.create(ndim-1)); // TODO: NOTE ndim here is 0 based but probably should be 1 based - see also changes in scccore.js and JSTypeParameters
                  iexpr.setProperty("arguments", snl);

                  // For a simple binary expr, we replace it entirely
                  if (numOps == 1) {
                     parentNode.replaceChild(this, iexpr);
                  }
                  else {
                     operands.remove(i);
                     numOps--;
                     if (i == 0) {
                        setProperty("lhs", iexpr, false, true);
                        setProperty("firstExpr", iexpr);
                     }
                     else {
                        BinaryOperand prevBO = (BinaryOperand) operands.get(i-1);
                        prevBO.setProperty("rhs", iexpr, false, true);
                     }
                     i--;
                  }
               }
               else {
                  // Wrap the entire expression in paren's.  TODO: should this only wrap the instance of operator?  If so, need to split it into
                  // a different structure.
                  needsParenWrapper = true;
               }
            }
            else if (bo instanceof BinaryOperand) {
               BinaryOperand bop = (BinaryOperand) bo;
               Expression rhsExpr = bop.rhs;

               if (bop.operator.equals("=="))
                  bop.setProperty("operator", "===");

               if (bop.operator.equals("!="))
                  bop.setProperty("operator", "!==");

               if (rhsExpr != null) {
                  rhsExpr.transformToJS();

                  Expression realLHS = getRealLhs(rhsExpr);
                  Expression replaceExpr;
                  if (realLHS != null) {
                     Object lhsType = realLHS.getTypeDeclaration();
                     replaceExpr = rhsExpr.applyJSConversion(lhsType);
                     if (replaceExpr != null) {
                        replaceChild(rhsExpr, replaceExpr);
                     }
                  }
                  if (realLHS != null) {
                     ISemanticNode parentNode = realLHS.parentNode;
                     Object rhsType = rhsExpr.getTypeDeclaration();
                     replaceExpr = realLHS.applyJSConversion(rhsType);
                     if (replaceExpr != null) {
                        parentNode.replaceChild(realLHS, replaceExpr);
                        // Updates the internal data structure
                        //setProperty("lhs", replaceExpr, false, !isTreeNode);
                        // Updates the grammar
                        //setProperty("firstExpr", replaceExpr);
                        //lhsType = rhsType;
                     }
                  }
               }
            }
            else
               System.err.println("*** Invalid binary expression");
         }
      }
      else {
         if (rhs instanceof JavaType)
            ((JavaType) rhs).transformToJS();
         else if (rhs instanceof Expression)
            ((Expression) rhs).transformToJS();
         else
            System.out.println("*** error invalid RHS");
      }
      if (needsParenWrapper) {
         ISemanticNode origParent = parentNode;
         Statement repl = ParenExpression.create(this);
         // JS treats: "x" + y instanceof foo differently than Java in terms of predendance
         origParent.replaceChild(this, repl);
         return repl;
      }
      return this;
   }

   public Expression getRealLhs(Expression toFindRHS) {
      if (toFindRHS == this.rhs)
         return lhs;

      Expression res;
      if (lhs instanceof BinaryExpression) {
         res = ((BinaryExpression) lhs).getRealLhs(toFindRHS);
         if (res != null)
            return res;
      }
      if (rhs instanceof BinaryExpression) {
         res = ((BinaryExpression) rhs).getRealLhs(toFindRHS);
         if (res != null)
            return res;
      }
      return null;
   }

   // This ensures that an expression involving only constants does not generate a binding expression.
   public boolean isConstant() {
      if (lhs != null && lhs.isConstant()) {
         if (rhs instanceof Expression) {
            if (((Expression) rhs).isConstant())
               return true;
         }
      }
      return false;
   }

   /** These need a paren when combined with "+" */
   public boolean needsParenWrapper() {
      // Making a special case for "+" so generated a + b + c are easier to read.
      if (operands != null) {
         for (BaseOperand op:operands)
            if (!(op instanceof BinaryOperand) || !((BinaryOperand) op).operator.equals("+"))
               return true;
      }
      return false;
   }

   public String toGenerateString() {
      if (firstExpr != null && operands != null) {
         StringBuilder sb = new StringBuilder();
         sb.append(firstExpr.toGenerateString());
         for (BaseOperand op:operands) {
            sb.append(op.toGenerateString());
         }
         return sb.toString();
      }
      throw new UnsupportedOperationException();
   }

   public String toSafeLanguageString() {
      if (parseNode == null || parseNodeInvalid) {
         if (firstExpr != null && operands != null) {
            StringBuilder sb = new StringBuilder();
            sb.append(firstExpr.toSafeLanguageString());
            sb.append(" ");
            for (BaseOperand op:operands) {
               sb.append(op.toSafeLanguageString());
            }
            return sb.toString();
         }
      }
      return super.toSafeLanguageString();
   }

   public BinaryExpression deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      BinaryExpression res = (BinaryExpression) super.deepCopy(options, oldNewMap);
      if ((options & CopyInitLevels) != 0) {
         res.isTreeNode = false; // Always clone the nodes via the semantic tree, even if they were
         res.rootExpression = rootExpression;

         /*
          * lhs and rhs are not copied... they need to point to the nodes in the real tree
         res.setProperty("lhs", lhs.deepCopy(options), false, true);
         res.setProperty("rhs", rhs.deepCopy(options), false, true);
         */

         // This needs to be false or the setRhs method will try to replace things which messes up the parent
         res.initialized = false;
         res.initExprTree();

         res.initialized = isInitialized();

         if (isInitialized()) {
            if (bindingDirection != null) {
               if (res.lhs != null && res.rhs != null) {
                  res.lhs.setBindingInfo(bindingDirection, bindingStatement, true);
                  if (res.rhs instanceof Expression) {
                     res.getRhsExpr().setBindingInfo(bindingDirection, bindingStatement, true);
                  }
               }
            }
            if (res.lhs != null)
               res.lhs.init();
            if (res.rhs != null)
               res.rhs.init();
         }
         if (isStarted()) {
            if (res.lhs != null)
               res.lhs.start();
            if (res.rhs != null)
               res.rhs.start();
         }
         if (isValidated()) {
            if (res.lhs != null)
               res.lhs.validate();
            if (res.rhs != null)
               res.rhs.validate();
         }
         if (isProcessed()) {
            if (res.lhs != null)
               res.lhs.process();
            if (res.rhs != null)
               res.rhs.process();
         }
      }
      return res;
   }

   public void addBreakpointNodes(List<ISrcStatement> res, ISrcStatement srcStatement) {
      super.addBreakpointNodes(res, srcStatement);
      if (lhs != null) {
         lhs.addBreakpointNodes(res, srcStatement);
      }
      if (rhs instanceof Expression) {
         Expression rhsExpr = getRhsExpr();
         if (rhsExpr != null)
            rhsExpr.addBreakpointNodes(res, srcStatement);
      }
      else if (rhs instanceof JavaType) {
         //...
      }
   }

   public boolean needsEnclosingClass() {
      if (lhs != null && lhs.needsEnclosingClass())
         return true;
      Expression rhsExpr = getRhsExpr();
      if (rhsExpr != null && rhsExpr.needsEnclosingClass())
         return true;
      return false;
   }

   private Class getInferredType() {
      if (operator == null) {
         System.out.println("*** Uninitialized binary expression!");
         return null;
      }
      switch (getOperatorType(operator)) {
         case Conditional:
            return Boolean.class;
      }
      return null;
   }

   public boolean propagatesInferredType(Expression child) {
      Class inferredType = getInferredType();
      if (inferredType != null)
         return true;
      return false;
   }
}
