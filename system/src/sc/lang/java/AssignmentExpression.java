/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.BindingDirection;
import sc.dyn.DynUtil;
import sc.lang.ILanguageModel;
import sc.lang.ISemanticNode;
import sc.lang.ISrcStatement;
import sc.lang.SemanticNodeList;
import sc.lang.sc.PropertyAssignment;
import sc.layer.LayeredSystem;
import sc.parser.Language;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public class AssignmentExpression extends TwoOperatorExpression {
   public transient String selfOperator;

   // TODO performance: investigate if we can use fromStatement for this to save on the field
   public transient JavaSemanticNode fromDefinition; // Either a VariableDefinition or a PropertyAssignment when this is a convert of a field or property

   public transient Object assignedProperty;  // Reference to the field or set method involved in the assignment if this is to a field

   public transient boolean assignmentBinding = false;

   // public SymbolChoiceSpace assignmentOperator = new SemanticTokenChoice("=", "+=", "-=", "*=", "/=", "%=", "^=", "|=", "&=", "<<=", ">>=", ">>>=");
   public void init() {
      if (lhs != null)
         lhs.setAssignment(true);
      super.init();

      if (bindingDirection == null)
         bindingDirection = ModelUtil.initBindingDirection(operator);
      if (bindingDirection != null) {
         if (rhs == null)
            System.err.println("*** Invalid null initializer for PropertyAssignment in binding expression: " + toDefinitionString());
         else {
            BindingDirection propBD;
            // Weird case:  convert reverse only bindings to a "none" binding for the arguments.
            // We do need to reflectively evaluate the parameters but do not listen on them like
            // in a forward binding.  If we propagate "REVERSE" down the chain, we get invalid errors
            // like when doing arithmetic in a reverse expr.
            if (bindingDirection.doReverse() && !bindingDirection.doForward())
               propBD = BindingDirection.NONE;
            else
               propBD = bindingDirection;
            boolean isNested = false;
            // Typically the top level rhs does not get the nested flag.  But for assignment expressions
            // we do because it is a nested assignment.  If this assignment itself is nested, the rhs should also be nested.
            if (rhs instanceof IdentifierExpression && bindingDirection.doReverse() && !bindingDirection.doForward() && assignmentBinding) {
               isNested = true;
            }
            rhs.setBindingInfo(propBD, this, rhs instanceof AssignmentExpression || nestedBinding || isNested);
            if (rhs instanceof AssignmentExpression)
               ((AssignmentExpression) rhs).assignmentBinding = true;
         }
      }
      else {
         if (operator != null && !operator.equals("="))
            selfOperator = operator.substring(0,operator.indexOf("="));
      }
   }

   public void stop() {
      super.stop();
      selfOperator = null;
      fromDefinition = null;
      assignedProperty = null;
      assignmentBinding = false;
   }

   private boolean isArithSelfOperator() {
      if (selfOperator == null)
         return false;
      return selfOperator.equals("+") || selfOperator.equals("-") || selfOperator.equals("/") || selfOperator.equals("*") || selfOperator.equals("%");
   }

   private boolean isIntSelfOperator() {
      if (selfOperator == null)
         return false;
      return selfOperator.equals("<<") || selfOperator.startsWith(">>") || selfOperator.equals("^") || selfOperator.equals("|") || selfOperator.equals("&");
   }

   public void start() {
      if (started) return;

      // Make sure our lhs is started so we can get its type
      super.start();

      if (bindingDirection != null) {
         if (lhs instanceof IdentifierExpression) {
            IdentifierExpression varRef = (IdentifierExpression) lhs;
            assignedProperty = varRef.getAssignedProperty();
            if (assignedProperty == null)
               displayTypeError("Binding expression on assignment must refer to a field or a property of an object for: ");
         }

         if (assignedProperty != null) {
            String propertyName = ModelUtil.getPropertyName(assignedProperty);
            Object referenceType = ((IdentifierExpression) lhs).getReferenceType();
            IdentifierExpression.makeBindable(this, propertyName,
                    IdentifierExpression.getIdentifierTypeFromType(assignedProperty),
                    assignedProperty, getTypeDeclaration(), referenceType, !bindingDirection.doReverse(), false);
         }
      }
      if (assignedProperty != null && ModelUtil.isConstant(assignedProperty))
         displayError("Property: " + ModelUtil.getPropertyName(assignedProperty) + " is marked as constant - cannot assign values: ");

      if (lhs != null && rhs != null) {
         boolean useGenericTypes = true;
         Object lhsType = useGenericTypes ? lhs.getGenericType() : lhs.getTypeDeclaration();
         // TODO: should we clone the type here to avoid modifying the source code's type declaration's type parameters
         if (lhsType instanceof ParamTypeDeclaration)
            ((ParamTypeDeclaration) lhsType).writable = true;

         // Need to do this to initialize the lambda expression before we try to get the tyep of the rhs
         rhs.setInferredType(lhsType, true);

         Object rhsType = useGenericTypes ? rhs.getGenericType() : rhs.getTypeDeclaration();
         if (lhsType != null && rhsType != null && !ModelUtil.isAssignableFrom(lhsType, rhsType, true, null, getLayeredSystem())) {
            if (selfOperator != null) {
               if (selfOperator.equals("+") && ModelUtil.isString(lhsType)) {
                  // Accept this case:  str += object - it will call the toString
                  return;
               }
               if (isArithSelfOperator() && ModelUtil.isANumber(lhsType) && ModelUtil.isANumber(rhsType))
                  return;
               if (isIntSelfOperator() && ModelUtil.isAnInteger(lhsType) && ModelUtil.isAnInteger(rhsType))
                  return;
            }
            /*
             * Implement the weird rule for methods with parameterized return types and no arguments.  In that case, for assignments
             * only, it inherits the type of the assignment.  Only thing is that we need to still validate that we can coerce
             * the rhs to the type of the lhs, in other words, they are assignable the other way.
             */
            if (!rhs.getLHSAssignmentTyped() || !ModelUtil.isAssignableFrom(rhsType, lhsType, true, null, getLayeredSystem())) {
               displayTypeError("Incompatible types for assignment: ", ModelUtil.getTypeName(lhsType, true, true), " and ", ModelUtil.getTypeName(rhsType, true, true), " for: ");
               rhsType = rhs.getGenericType(); // TODO: REMOVE For easier error debugging only
               boolean x = ModelUtil.isAssignableFrom(lhsType, rhsType, true, null, getLayeredSystem()); // TODO: REMOVE!
            }
         }
      }
   }

   public Object eval(Class expectedType, ExecutionContext ctx) {
      if (bindingDirection != null) {
         return evalBinding(expectedType, ctx);
      }
      Class cl = ModelUtil.typeToClass(lhs.getTypeDeclaration());
      Object rval = rhs.eval(cl, ctx);

      if (selfOperator != null) {
         Object lval = lhs.eval(cl, ctx);
         rval = DynUtil.evalArithmeticExpression(selfOperator, expectedType, lval, rval);
      }

      lhs.setValue(rval, ctx);
      return rval;
   }

   public ExecResult exec(ExecutionContext ctx) {
      eval(null, ctx);
      return ExecResult.Next;
   }

   @Override
   public boolean isStaticTarget() {
      return lhs.isStaticTarget() && rhs.isStaticTarget();
   }

   public boolean needsTransform() {
      return lhs.needsSetMethod() || super.needsTransform();
   }

   public boolean transform(ILanguageModel.RuntimeType runtime) {
      boolean any = false;
      boolean removed = false;

      /** TODO: this feels like a special case that should be consolidated somehow -- needed for a simple =: foo = !foo;  */
      if (bindingDirection != null && rhs instanceof UnaryExpression && assignmentBinding) {
         rhs.nestedBinding = true;
      }

      // For top-level reverse only bindings, we don't need to set the variable - just define the binding which is
      // in this case the rhs.
      if (bindingDirection != null && !bindingDirection.doForward() && !assignmentBinding && !nestedBinding) {
         if (bindingDirection.doReverse()) {
            // Need to toggle this back to reverse because otherwise we lose the direction entirely.
            rhs.bindingDirection = BindingDirection.REVERSE;
            // And it is now the top level binding if this one is a top level binding
            if (rhs instanceof AssignmentExpression)
               rhs.nestedBinding = nestedBinding;
            if (rhs instanceof UnaryExpression)
               rhs.nestedBinding = nestedBinding;
         }
         // If you have a = (y)  you need to strip off those parens before doing it into just (y).  First we need to
         // set the binding direction for the inner node
         if (!(parentNode instanceof Expression)) {
            Expression rhsExpr = rhs;
            while (rhsExpr instanceof ParenExpression) {
               rhsExpr = ((ParenExpression) rhsExpr).getChainedExpression();
               if (bindingDirection.doReverse())
                  rhsExpr.bindingDirection = BindingDirection.REVERSE;
            }
         }
         rhs.transform(runtime);
         Expression rhsExpr = rhs;
         // Now peel off the paren expression
         if (!(parentNode instanceof Expression)) {
            while (rhsExpr instanceof ParenExpression) {
               rhsExpr = ((ParenExpression) rhsExpr).getChainedExpression();
            }
         }
         replaceStatementChild(rhsExpr);
         return true;
      }

      // Useful to trace the conversion of x = y to setX(y)
      //if (lhs instanceof IdentifierExpression && ((IdentifierExpression) lhs).identifiers.get(0).equals("convertedProperty"))
      //   System.out.println("*");

      if (lhs.needsSetMethod()) {
         // If we are like a *= b, first convert this to a = a * b, then transform that
         if (selfOperator != null) {
            Expression lhsCopy = (Expression) lhs.deepCopy(ISemanticNode.CopyNormal, null);
            lhsCopy.changeToRHS();
            ArithmeticExpression ae = ArithmeticExpression.create(lhsCopy, selfOperator, rhs);
            setProperty("rhs", ae);
         }

         any = true;

         // TODO: We can do more optimizations to split up simple cases like a = b = c.  Need to differentiate
         // though between a = b.c = d which is harder to split up without potential side affects.
         boolean canInsertBefore = canInsertStatementBefore(this);
         AssignmentExpression parentExpr;
         if (bindingDirection != null) {
            super.transform(runtime);
            // In some cases, the super transform process will remove this statement entirely, for example when you have
            // an =: assignmnet expression.  In those cases, just skip these last steps as we are done.
            if (parentNode.containsChild(this)) {
               convertAssignmentToSetMethod();
            }
            return true;
         }
         if (!canInsertBefore && parentNode instanceof AssignmentExpression &&
             (parentExpr = (AssignmentExpression) parentNode).canInsertStatementBefore(this) && lhs.isSimpleReference()) {
            Expression lhsCopy = (Expression) lhs.deepCopy(ISemanticNode.CopyNormal, null);
            lhsCopy.convertToSetMethod(rhs);
            // canInsertStatementBefore should ensure this is true - maybe break out an interface?
            AbstractBlockStatement newParent = (AbstractBlockStatement) parentExpr.getEnclosingStatement();
            newParent.insertStatementBefore(parentExpr, lhsCopy);
            lhsCopy.transform(runtime);
            markReplacedFromStatement(lhsCopy);
         }
         else if (canInsertBefore) {
            // Need to do the binding stuff here
            super.transform(runtime);

            convertAssignmentToSetMethod();
            removed = true;
         }
         else {
            super.transform(runtime);

            boolean isStatic = isStatic();
            IdentifierExpression call = IdentifierExpression.create("sc", "dyn", "DynUtil",
                    "setAndReturn" + (isStatic ? "Static" : ""));
            SemanticNodeList<Expression> args = new SemanticNodeList<Expression>();
            call.setProperty("arguments", args);

            Object lhsTypeDecl = lhs.getTypeDeclaration(); // Grab before we start moving things around

            // TODO: this should be cleaned up but am not even sure there are enough test cases here.  like:
            // a = a.b.c = d
            if (lhs instanceof IdentifierExpression && bindingDirection != null) {
               BindDescriptor bd = lhs.getBindDescriptor();
               bd.isSetAndReturnLHS = true;
               lhs.transformBindingArgs(args, bd);
            }
            else {
               args.add(lhs.getParentReferenceTypeExpression());
               args.add(StringLiteral.create(lhs.getReferencePropertyName()));
            }
            args.add(rhs);
            CastExpression castExpr = new CastExpression();
            castExpr.setProperty("type", JavaType.createObjectType(lhsTypeDecl));
            castExpr.setProperty("expression", call);
            parentNode.replaceChild(replacedByStatement == null ? this : replacedByStatement, castExpr);
            castExpr.transform(runtime);
            markReplacedFromStatement(castExpr);
            removed = true;
         }
      }
      if (!removed) {
         if (super.transform(runtime))
            any = true;
      }
      else
         any = true;
      // Should this be for the !nestedBinding case only?
      if (bindingDirection != null) {
         setProperty("operator", "=");
         any = true;
      }
      return any;
   }

   private void convertAssignmentToSetMethod() {
      lhs.convertToSetMethod(rhs);
      replaceStatementChild(lhs);
   }

   private void replaceStatementChild(Statement newChild) {
      parentNode.replaceChild(this, newChild);
      markReplacedFromStatement(newChild);
   }

   private void markReplacedFromStatement(Statement newSt) {
      // Propagate our fromDefinition if we were generated.  Probably fromDefinition and fromStatement should be combiend
      if (fromDefinition instanceof Statement)
         newSt.fromStatement = (Statement) fromDefinition;
      else if (fromStatement != null)
         newSt.fromStatement = fromStatement;
      else
         newSt.fromStatement = this;
   }

   public void transformBindingArgs(SemanticNodeList<Expression> bindArgs, BindDescriptor bd) {
      bindArgs.add(IdentifierExpression.create("this"));
      // Will get transformed into the bind calls
      bindArgs.add(lhs);
      bindArgs.add(rhs);
   }

   public String getBindingTypeName() {
      return (nestedBinding ? "assignP" : "assign");
   }

   public void evalBindingArgs(List<Object> bindArgs, boolean isStatic, Class expectedType, ExecutionContext ctx) {
      bindArgs.add(ctx.getCurrentObject());
      bindArgs.add(lhs.eval(null, ctx));
      bindArgs.add(rhs.eval(null, ctx));
   }

   public Object getTypeDeclaration() {
      return lhs.getTypeDeclaration();
   }

   public static AssignmentExpression create(Expression lhsArg, String operator, Expression rhsArg) {
      AssignmentExpression ae = new AssignmentExpression();
      ae.setProperty("lhs", lhsArg);
      ae.operator = operator;
      ae.setProperty("rhs", rhsArg);
      return ae;
   }

   public String getInitializedProperty() {
      if (fromDefinition == null) return null;
      if (fromDefinition instanceof PropertyAssignment)
         return ((PropertyAssignment) fromDefinition).propertyName;
      else if (fromDefinition instanceof VariableDefinition)
         return ((VariableDefinition) fromDefinition).variableName;
      else
         throw new UnsupportedOperationException();
   }

   public boolean isBindableProperty() {
      if (fromDefinition == null) return false;
      if (fromDefinition instanceof PropertyAssignment)
         return ModelUtil.isBindable(((PropertyAssignment) fromDefinition).assignedProperty);
      else if (fromDefinition instanceof VariableDefinition)
         return ModelUtil.isBindable(fromDefinition);
      else
         throw new UnsupportedOperationException();
   }

   public boolean isAutomaticBindableProperty() {
      if (fromDefinition == null) return false;
      if (fromDefinition instanceof PropertyAssignment)
         return ModelUtil.isAutomaticBindable(((PropertyAssignment) fromDefinition).assignedProperty);
      else if (fromDefinition instanceof VariableDefinition)
         return ModelUtil.isAutomaticBindable(fromDefinition);
      else
         throw new UnsupportedOperationException();
   }

   public ISrcStatement getFromStatement() {
      if (fromDefinition instanceof VariableDefinition)
         return ((VariableDefinition) fromDefinition).getDefinition();
      else if (fromDefinition instanceof PropertyAssignment)
         return (PropertyAssignment) fromDefinition;
      else
         return super.getFromStatement();
   }

   public int suggestCompletions(String prefix, Object currentType, ExecutionContext ctx, String command, int cursor, Set<String> candidates, Object continuation, int max) {
      if (rhs == null)
         return -1;
      // Remove the data binding info since that gets in the way here...
      /*
      if (bindingDirection != null)
         rhs.setBindingInfo(null, null, false);
      */
      return rhs.suggestCompletions(prefix, currentType, ctx, command, cursor, candidates, continuation, max);
   }

   public String addNodeCompletions(JavaModel origModel, JavaSemanticNode origNode, String matchPrefix, int offset, String dummyIdentifier, Set<String> candidates, boolean nextNameInPath, int max) {
      if (rhs != null) {
         return rhs.addNodeCompletions(origModel, origNode, matchPrefix, offset, dummyIdentifier, candidates, nextNameInPath, max);
      }
      return super.addNodeCompletions(origModel, origNode, matchPrefix, offset, dummyIdentifier, candidates, nextNameInPath, max);
   }

   public boolean applyPartialValue(Object partial) {
      return rhs != null && rhs.applyPartialValue(partial);
   }

   public AssignmentExpression deepCopy(int options, IdentityHashMap<Object,Object> oldNewMap) {
      AssignmentExpression res = (AssignmentExpression) super.deepCopy(options, oldNewMap);

      if ((options & CopyInitLevels) != 0) {
         res.selfOperator = selfOperator;
         res.fromDefinition = fromDefinition;
         res.assignedProperty = assignedProperty;
         res.assignmentBinding = assignmentBinding;
      }
      return res;
   }

   public String toGenerateString() {
      StringBuilder sb = new StringBuilder();
      if (lhs == null || operator == null || rhs == null)
         return ("<uninitialized AssignmentExpression>");
      sb.append(lhs.toGenerateString());
      sb.append(" ");
      sb.append(operator);
      sb.append(" ");
      sb.append(rhs.toGenerateString());
      return sb.toString();
   }

   public ISrcStatement findFromStatement (ISrcStatement st) {
      if (fromDefinition instanceof ISrcStatement && ((ISrcStatement) fromDefinition).findFromStatement(st) != null)
         return this;
      if (lhs != null && st.getNodeContainsPart(lhs))
         return this;
      if (rhs != null && st.getNodeContainsPart(rhs))
         return this;
      return super.findFromStatement(st);
   }

   public ISrcStatement getSrcStatement(Language lang) {
      if (fromDefinition instanceof ISrcStatement)
         return ((ISrcStatement) fromDefinition).getSrcStatement(lang);
      return super.getSrcStatement(lang);
   }

   public boolean needsEnclosingClass() {
      if (lhs != null && lhs.needsEnclosingClass())
         return true;
      if (rhs != null && rhs.needsEnclosingClass())
         return true;
      return false;
   }

   public boolean propagatesInferredType(Expression child) {
      return true;
   }

   public boolean execForRuntime(LayeredSystem sys) {
      return lhs != null && lhs.execForRuntime(sys);
   }
}

