/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sc;

import sc.bind.Bind;
import sc.bind.BindingDirection;
import sc.dyn.DynUtil;
import sc.lang.ILanguageModel;
import sc.lang.INamedNode;
import sc.lang.ISrcStatement;
import sc.lang.SemanticNodeList;
import sc.lang.html.Attr;
import sc.layer.Layer;
import sc.obj.IObjectId;
import sc.type.CTypeUtil;
import sc.type.IBeanMapper;
import sc.type.TypeUtil;
import sc.lang.java.*;
import sc.parser.*;
import sc.util.StringUtil;

import java.util.*;

public class PropertyAssignment extends Statement implements IVariableInitializer, IObjectId, INamedNode {
   public String propertyName;
   public String operator;
   public Expression initializer;

   private transient boolean suppressGeneration = false;
   public transient Object assignedProperty;   // Reference to the field or set method involved in the assignment
   public transient boolean needsCastOnConvert = false;

   /** When a property assignment is created from an HTML attribute, this keeps the back pointer so we can re-propagate changes made in the editor to the property assignment */
   public transient Attr fromAttribute;

   public transient BindingDirection bindingDirection;

   private boolean wasBound = false;

   private transient boolean starting = false;

   public void init() {
      if (initialized) return;
      super.init();

      bindingDirection = ModelUtil.initBindingDirection(operator);
      if (bindingDirection != null) {
         if (initializer == null)
            System.err.println("*** Invalid null initializer for PropertyAssignment in binding expression: " + toDefinitionString());
         else
            initializer.setBindingInfo(bindingDirection, this, false);

         if (initializer instanceof AssignmentExpression)
            ((AssignmentExpression) initializer).assignmentBinding = true;
      }

   }

   public void start() {
      if (started) return;
      boolean isSetMethod = false;

      BodyTypeDeclaration encType = getEnclosingType();
      if (encType == null) {
         // System.out.println("*** Error starting PropertyAssignment that's not connected to any model");
         return;
      }

      EnumSet<MemberType> mtype;
      if (initializer == null)
         mtype = MemberType.PropertyGetSet;
      else if (bindingDirection == null || bindingDirection.doForward())
         mtype = MemberType.PropertySetSet;
      else {
         // Reverse only bindings - anything that's binding which includes a getX method with no setX.
         mtype = MemberType.PropertyGetSetSet;
      }

      // Sometimes happens during code-completion parsing
      if (propertyName == null) {
         super.start();
         return;
      }

      // Using "defines" here, not "find" as we do not want to inherit property assignments.  Why?  It's too easy to pick up a parent's property when a child's is not defined.  Get no error
      // and wonder why things are not working.  Secondly, we don't handle all of the cases for when the property is not in the type.  For example, when the type gets optimized away, we do not
      // implement the binding correctly.  So simpler is better.  Make sure the property is defined in the enclosing type.
      if (assignedProperty == null) {
         assignedProperty = encType.definesMember(propertyName, mtype, getEnclosingIType(), null);
         if (assignedProperty instanceof IMethodDefinition) {
            isSetMethod = true;
         }
      }

      if (assignedProperty == null) {
         if (!getJavaModel().disableTypeErrors) {
            // Get the member again with no reference type
            Object errorProp = encType.definesMember(propertyName, mtype, null, null);
            if (errorProp != null) {
               MemberType checkType;
               // Figure out whether it's a field, get or set method which failed so we give the right error
               if (errorProp instanceof IBeanMapper) {
                  errorProp = ((IBeanMapper) errorProp).getSetSelector();
               }
               checkType = ModelUtil.isField(errorProp) ? MemberType.Field : (mtype.contains(MemberType.GetMethod) ? MemberType.GetMethod : MemberType.SetMethod);
               displayTypeError("Inaccessible property: " + propertyName + " in type: " + ModelUtil.getTypeName(ModelUtil.getEnclosingType(errorProp)) + " has access level: " + ModelUtil.getAccessLevelString(errorProp, false, checkType) + " for: ");
            }
            else {
               displayTypeError("No property: ", propertyName, " in type: ", getEnclosingType().getFullTypeName(), " for ");
               errorProp = encType.definesMember(propertyName, mtype, null, null);
            }
         }
      }
      else if (!starting) {
         // setInferredType may loop around and try to start us again
         starting = true;

         // Check to be sure the initializer is compatible with the property
         // Skip this test for reverse-only bindings
         if (initializer != null && !isReverseOnlyExpression()) {
            Object propType = isSetMethod ? ModelUtil.getSetMethodPropertyType(assignedProperty) : ModelUtil.getVariableTypeDeclaration(assignedProperty);
            initializer.setInferredType(propType, true);
            Object initType = initializer.getGenericType();
            if (initType != null && propType != null &&
                !ModelUtil.isAssignableFrom(propType, initType, true, null, getLayeredSystem())) {
               displayTypeError("Type mismatch - assignment to property with type: " + ModelUtil.getTypeName(propType, true, true) + " does not match expression type: " + ModelUtil.getTypeName(initType, true, true) + " for: ");
               boolean x = ModelUtil.isAssignableFrom(propType, initType, true, null);
            }
            else if (initType != propType) {
               if (ModelUtil.isANumber(propType)) {
                  needsCastOnConvert = true;
               }
            }
         }
      }
      super.start();
   }

   public void validate() {
      if (validated) return;

      super.validate();

      boolean makeBindable = false;

      Object annot = ModelUtil.getBindableAnnotation(this);
      if (annot != null) {
         makeBindable = ModelUtil.isAutomaticBindingAnnotation(annot);
      }

      if (bindingDirection != null) {
         detectCycles();
      }

      if (bindingDirection != null || makeBindable) {
         Object referenceType = findMemberOwner(propertyName, MemberType.PropertyGetSet);
         IdentifierExpression.makeBindable(this, propertyName,
                 IdentifierExpression.getIdentifierTypeFromType(assignedProperty),
                 assignedProperty, getTypeDeclaration(), referenceType, !makeBindable && !bindingDirection.doReverse(), false);
      }
      // If the annotation is set on this assignment and there's no code-gen for this property
      else if (annot != null && getMyAnnotation("sc.bind.Bindable") != null) {
         Object referenceType = findMemberOwner(propertyName, MemberType.PropertyGetSet);
         if (referenceType instanceof BodyTypeDeclaration) {
            BodyTypeDeclaration decl = ((BodyTypeDeclaration) referenceType);
            decl.addPropertyAlreadyBindable(propertyName);
         }
         //else - live with the warning... can't change it anyway since the type is only compiled
         // assert makeBindable = false
      }
   }

   public Object definesMember(String name, EnumSet<MemberType> mtype, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      if (mtype.contains(MemberType.Assignment))
         if (propertyName.equals(name) && (!mtype.contains(MemberType.Initializer) || initializer != null))
            return this;
      if (mtype.contains(MemberType.ForwardAssignment) && propertyName.equals(name) && (bindingDirection == null || bindingDirection != BindingDirection.REVERSE) && initializer != null)
         return this;
      return super.definesMember(name, mtype, refType, ctx, skipIfaces, isTransformed);
   }

   public void initDynStatement(Object inst, ExecutionContext ctx, TypeDeclaration.InitStatementMode mode, boolean inherit) {
      // First doing any transformations that are necessary to execute this
      if (assignedProperty != null && ModelUtil.hasModifier(assignedProperty, "static"))
         return;

      Expression init = initializer;

      if (init == null && inherit)
         init = getInitializerExpr();

      if (init != null) {
         // This is a property assignment that had an old binding that now has a different one.  First we need to
         // remove the old ones before we add the new ones.
         if (wasBound) {
            Bind.removePropertyBindings(inst, propertyName, true, true);
         }

         Object retTypeObj = getTypeDeclaration();
         Class returnType = retTypeObj == null ? null : ModelUtil.getCompiledClass(retTypeObj);
         boolean eval = mode.evalExpression(init, bindingDirection);

         if (eval) {
            ctx.pushCurrentObject(inst);
            try {
               Object val = init.eval(returnType, ctx);

               // Reverse only bindings do not initialize the value
               if (bindingDirection == null || bindingDirection.doForward())
                  TypeUtil.setDynamicProperty(inst, propertyName, val, true);

            }
            finally {
               ctx.popCurrentObject();
            }
         }
      }
      // else - Do not process the previous definition of this property since the super type will have already handed it over to the super-type where the initializer was applied.
   }

   public void addInitStatements(List<Statement> res, InitStatementsMode mode) {
      // First doing any transformations that are necessary to execute this
      if (assignedProperty != null && ModelUtil.hasModifier(assignedProperty, "static") != mode.doStatic())
         return;

      res.add(this);
   }

   public boolean needsTransform() {
      return true;
   }

   /** The PropertyAssignment in SC is not in Java - we do a simple conversion here to replace x = y by { x = y } */
   public boolean transform(ILanguageModel.RuntimeType runtime) {
      if (runtime == ILanguageModel.RuntimeType.JAVA) {
         // Skip the list and go to the declaration
         BodyTypeDeclaration decl = (BodyTypeDeclaration) parentNode.getParentNode();

         // TODO: we should pass this down from the parent node to avoid the N*N
         int ix = decl.body.indexOf(this);

         // Property assignments without an initializer are used to just merge modifiers and stuff into
         // the definition.  Just remove the property assignment and move on.
         if (initializer == null) {
            super.transform(runtime);
            decl.body.remove(ix);
            if (decl.body.size() > ix)
               decl.body.get(ix).transform(runtime);
            return true;
         }

         if (!suppressGeneration) {
            BlockStatement st = null;

            boolean isStatic = isStatic();

            // Check if there's a block statement there for us so we can avoid creating a second one.
            if (parentNode instanceof SemanticNodeList) {
               SemanticNodeList parentList = (SemanticNodeList) parentNode;
               int parentIx = parentList.indexOf(this);
               if (parentIx > 0) {
                  Object prev = parentList.get(parentIx-1);
                  if (prev instanceof BlockStatement) {
                     st = (BlockStatement) prev;
                     // Can't reuse a static block statement unless this property assignment is itself to a static variable.
                     // We put static assignments in a static section so they run even if there's no instance created.
                     if (!st.visible || (st.staticEnabled != isStatic))
                        st = null;
                  }
               }
            }

            boolean stCreated = false;
            if (st == null) {
               st = new BlockStatement();
               st.fromDefinition = this;
               st.visible = true;
               st.setProperty("statements", new SemanticNodeList<Statement>(st, 1));
               if (isStatic)
                  st.setProperty("staticEnabled", true);
               stCreated = true;
            }

            Statement ae = convertToAssignmentExpression(null, false, operator, true);

            st.statements.add(ae);

            decl.body.remove(ix);  // Remove this guy
            if (stCreated) {
               decl.addBodyStatementAtIndent(ix, st); // Insert the new assignment expression in a block statement

               // In case transformations are required in the new statement
               st.transform(runtime);
            }
            else {
               ae.transform(runtime);
               // Because we removed a node at this spot and did not add one back, we need to transform the guy at this spot or it is skipped in the parent list
               if (decl.body.size() > ix)
                  decl.body.get(ix).transform(runtime);
            }

         }
         else {// already processed this through collectReferenceInitializers so just remove this guy and transform the node that goes into its place
            decl.body.remove(ix);
            if (decl.body.size() > ix)
               decl.body.get(ix).transform(runtime);
         }

         decl.addToHiddenBody(this);

         return true;
      }
      throw new UnsupportedOperationException();
   }

   /**
    * Used in two situations - one where we are converting this node to an assignment.  We also use it
    * to move a copy of this initializer to the getX method of a constructor.
    */
   public Expression convertToAssignmentExpression(String prefix, boolean copy, String operator, boolean removeReverseSet) {
      Expression newInit;
      // Since we are converting this and moving it elsewhere in the model, don't transform this guy
      suppressGeneration = true;

      // For reverse bindings, we do not generate an initializer - instead, just an expression
      // that will init the binding, i.e. foo =: bar turns into just what the initializer transforms into
      // after it is converted to a binding expression, i.e. Bind.bind(...)
      if ((bindingDirection != null && !bindingDirection.doForward()) && removeReverseSet) {
         if (copy) {
            newInit = (Expression) getInitializerExpr().deepCopy(CopyNormal, null);
            newInit.setBindingInfo(bindingDirection, this, false);
            newInit.parentNode = this;
         }
         else {
            newInit = getInitializerExpr();
         }
         if (newInit.fromStatement == null)
            newInit.fromStatement = fromStatement;
         /*
         if (!removeReverseSet) {
            ParseUtil.initAndStartComponent(newInit);
            ParseUtil.processComponent(newInit);
            newInit.transform(ILanguageModel.RuntimeType.JAVA);
         }
         */
         return newInit;
      }
      else {
         IdentifierExpression ie = new IdentifierExpression();
         ie.setProperty("identifiers", new SemanticNodeList<IString>(ie, prefix == null ? 1 : 2));
         if (prefix != null)
            ie.identifiers.add(PString.toIString(prefix));
         ie.identifiers.add(PString.toIString(propertyName));

         AssignmentExpression ae = new AssignmentExpression();
         ae.fromDefinition = this;
         ie.parentNode = ae;
         ae.setProperty("lhs", ie);
         //ae.operator = bindingDirection != null ? "=" : operator;
         ae.operator = operator;

         if (copy) {
            newInit = (Expression) getInitializerExpr().deepCopy(CopyNormal, null);
         }
         else {
            newInit = getInitializerExpr();
         }
         ae.setProperty("rhs", newInit);
         TransformUtil.convertArrayInitializerToNewExpression(ae);
         return ae;
      }
   }

   public Object getPreviousDefinition() {
      BodyTypeDeclaration btd = getEnclosingType();
      Object base = btd.getDerivedTypeDeclaration();
      if (!(base instanceof BodyTypeDeclaration))
         return null;
      return ((BodyTypeDeclaration) base).definesMember(propertyName, MemberType.PropertyAnySet, null, null);
   }

   public JavaSemanticNode modifyDefinition(BodyTypeDeclaration base, boolean doMerge, boolean inTransformed) {
      Object var;
      TypeContext ctx = new TypeContext();
      ctx.transformed = true;
      if ((var = base.definesMember(propertyName, MemberType.PropertyAnySet, null, ctx, false, inTransformed)) != null) {

         // Reverse-only bindings do not replace the underlying assignment, they append to it.  So just add this statement to the base type.
         if (bindingDirection == BindingDirection.REVERSE) {
            base.addBodyStatement(this);
            return this;
         }
         if (var instanceof VariableDefinition) {
            /* Remove the old variable and field if it is the last variable in that field def */
            VariableDefinition oldVarDef = (VariableDefinition) var;
            TypeDeclaration encType;

            boolean definedInThisType = (encType = oldVarDef.getEnclosingType()).getFullTypeName().equals(base.getFullTypeName());
            boolean definedInThisLayer = definedInThisType && encType.getLayer() == base.getLayer();

            // If this variable is defined in our base not, not inherited, we can modify the asssignment directly.
            // Otherwise, we just need to initialize the property when our base type is constructed.
            if ((doMerge && definedInThisType) || definedInThisLayer) {
               FieldDefinition fieldDef = (FieldDefinition) oldVarDef.parentNode.getParentNode();
               // mergeDefaultModifiers is false here for two reasons.  #1 because if it's true we have to start the property
               // assignment to do the merge which is wrong.  #2 if the property assignment is in a public layer but the field
               // is not, that should not make the field public.
               fieldDef.mergeModifiers(this, true, false);

               // TODO: with += and other operators, we could just insert a new "+" etc. expression

               // Why not just set operator and initializer in the old prop?  If operator is null we can't
               // smoothly take the model from one state to the other since it is invalid without both operator
               // and initializer.  Should have a way to make multiple changes but this so far has been pretty rare
               // and this workaround is easy enough.
               VariableDefinition newVarDef = (VariableDefinition) oldVarDef.deepCopy(CopyNormal, null);
               if (operator != null)
                  newVarDef.operator = operator;
               // The simple "override X y;" should just set modifies and annotations and leave the initializer alone
               if (initializer != null)
                  newVarDef.setProperty("initializer", initializer);
               newVarDef.bindingDirection = bindingDirection;
               oldVarDef.parentNode.replaceChild(oldVarDef, newVarDef);
               return newVarDef;
            }
            else {
               if (modifiers != null && !definedInThisType)
                  displayError("Cannot modify field attributes. Property: " + propertyName + " not defined in type " + base.getFullTypeName() + ": " + toDefinitionString());

               base.addBodyStatement(this);
               return this;
            }
         }
         else if (var instanceof PropertyAssignment) {
            PropertyAssignment oldAssign = (PropertyAssignment) var;

            TypeDeclaration encType = oldAssign.getEnclosingType();
            if (encType == null) {
               System.err.println("No enclosing type for old assignment in modify!");
               return this;
            }

            boolean definedInThisType = encType.getFullTypeName().equals(base.getFullTypeName());
            boolean definedInThisLayer = definedInThisType && encType.getLayer() == base.getLayer();

            // If this variable is defined in our base not, not inherited, we can modify the asssignment directly.
            // Otherwise, we just need to initialize the property when our base type is constructed.
            if ((doMerge && definedInThisType) || definedInThisLayer) {
               oldAssign.mergeModifiers(this, true, false);

               if (initializer != null) {
                  Expression oldInit = oldAssign.initializer;
                  oldAssign.setProperty("initializer", initializer);
                  if (initializer.anyError()) {
                      oldAssign.setProperty("initializer", oldInit);
                      return null;
                  }
                  if (oldAssign.operator == null || !oldAssign.operator.equals(operator))
                     oldAssign.setProperty("operator", operator);
                  ParseUtil.restartComponent(oldAssign);
               }
               return oldAssign;
            }
            else {
               if (!definedInThisType && modifiers != null)
                  displayError("Cannot modify field attributes. Property: " + propertyName + " not defined in type " + base.getFullTypeName() + ": ");
               if (initializer != null)
                  base.addBodyStatement(this);
               return this;
            }
         }
         // Get method case
         else if (var instanceof MethodDefinition) {
            MethodDefinition oldMethDef = (MethodDefinition) var;

            if (oldMethDef.getEnclosingType().getFullTypeName().equals(base.getFullTypeName())) {
               oldMethDef.mergeModifiers(this, true, false);
            }
            else  {
               if (modifiers != null)
                  displayError("Cannot modify field attributes. Property: " + propertyName + " not defined in type " + base.getFullTypeName() + ": ");
            }
            base.addBodyStatement(this);
            return oldMethDef;
         }
         else {
            if (modifiers != null)
               displayError("Cannot modify property attributes. Property: " + propertyName + " no source definition for field definition " + var + ": ");
            // Just reinitialize the property since that's all we can do
            base.addBodyStatement(this);
            return this;
         }
      }
      else {
         displayError("No field: ", propertyName, " in type: ", base.toString(), " for: ");
         var = base.definesMember(propertyName, MemberType.PropertyAnySet, null, null, false, true); // TODO: remove for debugging purposes only
         return null;
      }
   }

   public void collectReferenceInitializers(List<Statement> refInits) {
      Expression init = getInitializerExpr();
      if (init == null)
         return;
      if (init.isReferenceInitializer()) {
         // Make sure to pick up the inherited initializer/operator if this is an override statement
         refInits.add(convertToAssignmentExpression(null, false, getInheritedMember().getOperatorStr(), true));

      }
   }

   public Object getTypeDeclaration() {
      if (assignedProperty == null)
         return null;
      if (assignedProperty instanceof IMethodDefinition) {
         IMethodDefinition meth = (IMethodDefinition) assignedProperty;
         return meth.isSetMethod() ?  meth.getParameterTypes(false)[0] : meth.getReturnType(false);
      }
      return ModelUtil.getVariableTypeDeclaration(assignedProperty);
   }

   public String getVariableTypeName() {
      Object type = getTypeDeclaration();
      if (type == null)
         return null;
      return ModelUtil.getTypeName(type);
   }

   public String getGenericTypeName(Object resultType, boolean includeDims) {
      if (assignedProperty == null)
         return null;
      // This is always the set method so get the type from the first parameter
      if (assignedProperty instanceof IMethodDefinition) {
         IMethodDefinition meth = (IMethodDefinition) assignedProperty;
         if (meth.getMethodName().startsWith("set"))
            return meth.getParameterJavaTypes(true)[0].getGenericTypeName(resultType, includeDims);
      }
      return ModelUtil.getGenericTypeName(resultType, assignedProperty, includeDims);
   }

   public String getAbsoluteGenericTypeName(Object resultType, boolean includeDims) {
      if (assignedProperty == null)
         return null;
      // This is always the set method so get the type from the first parameter
      if (assignedProperty instanceof IMethodDefinition) {
         IMethodDefinition meth = (IMethodDefinition) assignedProperty;
         if (meth.getMethodName().startsWith("set"))
            return meth.getParameterJavaTypes(true)[0].getAbsoluteGenericTypeName(resultType, includeDims);
      }
      return ModelUtil.getAbsoluteGenericTypeName(resultType, assignedProperty, includeDims);
   }

   public Object getPropertyDefinition() {
      if (!started)
         start();
      if (assignedProperty == null)
         return null;

      if (assignedProperty instanceof VariableDefinition)
         return ((VariableDefinition) assignedProperty).getDefinition();
      return assignedProperty;
   }

   public Object getAssignedProperty() {
      if (!started) {
         if (assignedProperty != null)
            return assignedProperty;
         ParseUtil.realInitAndStartComponent(this);
      }
      return assignedProperty;
   }

   public boolean isStatic() {
      if (assignedProperty == null)
         return false;
      return ModelUtil.hasModifier(assignedProperty, "static");
   }

   public void visitTypeReferences(CycleInfo info, TypeContext ctx) {
      JavaSemanticNode propNode = null;
      CycleInfo.ThisContext thisCtx = null;

      // Do not detect cycles for reverse binding... like a valid thing is prop =: prop ? a : b
      if (bindingDirection != null && !bindingDirection.doForward())
         return;

      if (info.context == null || info.context.parentVar == null) {
         thisCtx = new CycleInfo.ThisContext(getEnclosingType(), null);
         // Need this set for the first addToVisitedList...
         info.context = thisCtx;
      }

      // Add the property to the visited list if this starts the cycle.  we don't want to visit its initializer here
      // as it may have been overridden so we just add it manually to the visited list.
      if (info.start == this && assignedProperty instanceof JavaSemanticNode) {
         info.addToVisitedList(propNode = (JavaSemanticNode) assignedProperty);
      }
      ctx = new TypeContext(ctx);
      // Only push a ThisContext if we are being visited on the top level, i.e. without having traversed a parent var.  Otherwise, we are part of the a.x expression
      if (thisCtx != null)
         info.visit(thisCtx, getInitializerExpr(), ctx, true);
      else
         info.visit(getInitializerExpr(), ctx, true);

      if (propNode != null)
         info.remove(propNode);
   }

   public boolean isReferenceValueObject() {
      return true;
   }

   public String getUserVisibleName() {
      return "assignment";
   }

   public void refreshBoundTypes(int flags) {
      Expression init = getInitializerExpr();
      if (init != null)
         init.refreshBoundTypes(flags);
      if (assignedProperty != null)
         assignedProperty = ModelUtil.refreshBoundProperty(getLayeredSystem(), assignedProperty, flags);
   }

   public void addDependentTypes(Set<Object> types) {
      Expression init = getInitializerExpr();
      if (init != null)
         init.addDependentTypes(types);
   }

   public boolean hasModifier(String modifier) {
      if (assignedProperty != null) {
         AccessLevel requestLevel, overrideLevel;
         if ((requestLevel = AccessLevel.getAccessLevel(modifier)) != null && (overrideLevel = ModelUtil.getAccessLevel(assignedProperty, true)) != null)
            return overrideLevel == requestLevel;

         if (ModelUtil.hasModifier(assignedProperty, modifier))
            return true;
      }
      return super.hasModifier(modifier);
   }

   public AccessLevel getAccessLevel(boolean explicitOnly) {
      if (explicitOnly)
         return super.getAccessLevel(explicitOnly);
      AccessLevel internal = getInternalAccessLevel();
      if (internal != null)
         return internal;

      if (!isStarted())
         ParseUtil.realInitAndStartComponent(this);
      if (assignedProperty != null)
         return ModelUtil.getAccessLevel(assignedProperty, explicitOnly);
      return null;
   }

   public Object getMyAnnotation(String annotation) {
      return super.getAnnotation(annotation);
   }

   public Object getAnnotation(String annotation) {
      Object thisAnnot = super.getAnnotation(annotation);
      if (assignedProperty != null) {
         Object overriddenAnnotation = ModelUtil.getAnnotation(assignedProperty, annotation);

         if (thisAnnot == null) {
            return overriddenAnnotation == null ? null : Annotation.toAnnotation(overriddenAnnotation);
         }
         if (overriddenAnnotation == null)
            return thisAnnot;

         thisAnnot = ModelUtil.mergeAnnotations(thisAnnot, overriddenAnnotation, false);
         return thisAnnot;
      }
      return thisAnnot;
   }

   public void styleNode(IStyleAdapter adapter) {
      ParentParseNode pnode = (ParentParseNode) parseNode;
      ParseUtil.styleString(adapter, "member", pnode.children.get(0).toString(), false);
      for (int i = 1; i < pnode.children.size(); i++) {
         Object childNode = pnode.children.get(i);
         if (childNode instanceof IParseNode && ((IParseNode) childNode).getSemanticValue() == initializer)
            initializer.styleNode(adapter);
         else
            ParseUtil.toStyledString(adapter, pnode.children.get(i));
      }
   }

   public static PropertyAssignment create(String pname, Expression expr, String op) {
      PropertyAssignment pa = new PropertyAssignment();
      pa.propertyName = pname;
      if (op == null)
         op = "=";
      pa.operator = op;
      pa.setProperty("initializer", expr);
      return pa;
   }

   public static PropertyAssignment createStarted(String pname, Expression expr, String op, Object boundType) {
      PropertyAssignment pa = create(pname, expr, op);
      pa.assignedProperty = boundType;
      return pa;
   }

   /** This returns the initializer for this assignment.  If this is an overrie statement with no initializer, the OverrideAssigment class will return the inherited initializer. */
   public IVariableInitializer getInheritedMember() {
      return this;
   }

   /** This returns the initializer for this assignment.  If this is an overrie statement with no initializer, the OverrideAssigment class will return the inherited initializer. */
   public Expression getInitializerExpr() {
      return initializer;
   }

   // This is needed so this property is settable in the client/server sync
   public void setInitializerExprStr(String s) {
      throw new UnsupportedOperationException();
   }

   public String getInitializerExprStr() {
      if (initializer == null)
         return null;
      return initializer.toLanguageString();
   }

   public void updateInitializer(String op, Expression expr) {
      setProperty("operator", op);
      setProperty("initializer", expr);
      if (isInitialized()) {

         // Keep track if this property used to be a bound property and now is not.  In initDynStatement this is a signal to clear
         // the bindings on the instance when we do the update there.
         wasBound = bindingDirection != null;

         bindingDirection = ModelUtil.initBindingDirection(operator);

         if (bindingDirection != null) {
            expr.setBindingInfo(bindingDirection, this, false);

            if (expr instanceof AssignmentExpression)
               ((AssignmentExpression) expr).assignmentBinding = true;
         }
      }
      Bind.sendChangedEvent(this, null);
   }

   public void setOperatorStr(String s) {
      operator = s;
   }

   public String getOperatorStr() {
      return operator;
   }

   public void updateComplete() {
      wasBound = false;
   }

   public String getVariableName() {
      return propertyName;
   }

   public String toString() {
      try {
         if (JavaSemanticNode.debugDisablePrettyToString)
            return toModelString();
         //return toDeclarationString();
         // Skiping the heavy computation in this method in part cause it gets called from getInstanceName
         return propertyName + " " + operator + " " + (initializer == null ? "<null>" : initializer.toSafeLanguageString());
      }
      catch (IllegalArgumentException exc) {
         return propertyName + (operator != null ? operator : "") + (initializer != null ? initializer : "");
      }
      catch (RuntimeException exc) {
         return "<unintialized assignment of: " + propertyName + ">";
      }
   }

   public String toSafeLanguageString() {
      if (parseNode != null && !parseNodeInvalid)
         return toLanguageString();
      if (propertyName != null) {
         StringBuilder sb = new StringBuilder();
         sb.append(propertyName);

         if (initializer != null && operator != null) {
            sb.append(" ");
            sb.append(operator);
            sb.append(" ");
            sb.append(initializer.toSafeLanguageString());
         }
         return sb.toString();
      }
      return getUserVisibleName();
   }

   /*
    * use getInializerExpr which is overridden in OverrideAssignment
    *
   public Expression getInheritedInitializer() {
      if (initializer != null)
         return initializer;

      Object prevDef = getPreviousDefinition();
      if (prevDef == null)
         return null;
      if (prevDef instanceof IVariableInitializer)
         return ((IVariableInitializer) prevDef).getInitializerExpr();

      System.out.println("*** unhandled case in getInheritedInitializer");

      return null;
   }
   */

   // We transform JS after going to Java so this won't be there
   public Statement transformToJS() {
      return this;
   }

   public boolean needsDataBinding() {
      return bindingDirection != null;
   }

   public int transformTemplate(int ix, boolean statefulContext) {
      if (initializer != null)
         ix = initializer.transformTemplate(ix, statefulContext);
      return ix;
   }

   public void setLayer(Layer l) {
      throw new UnsupportedOperationException();
   }

   public Layer getLayer() {
      TypeDeclaration typeDecl = getEnclosingType();
      if (typeDecl == null)
         return null;
      return typeDecl.getLayer();
   }

   protected IParseNode getAnyChildParseNode() {
      if (initializer != null)
         return initializer.parseNode;
      return null;
   }

   public PropertyAssignment deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      PropertyAssignment res = (PropertyAssignment) super.deepCopy(options, oldNewMap);

      if ((options & CopyInitLevels) != 0) {
         res.suppressGeneration = suppressGeneration;
         res.assignedProperty = assignedProperty;
         res.needsCastOnConvert = needsCastOnConvert;
         res.bindingDirection = bindingDirection;
         res.fromAttribute = fromAttribute;
         res.wasBound = wasBound;
      }
      return res;
   }

   public void stop() {
      super.stop();
      assignedProperty = null;
      starting = false;
   }

   /**
    * Return a small identifier that will typically usually identify this property.  As long as we process these in the same order
    * they will line up on the client with their proper correspondents, even after a restart.
    * NOTE: this same logic is duplicated in PropertyAssignment.sc on the client.
     */
   public String getObjectId() {
      BodyTypeDeclaration enclType = getEnclosingType();
      String typeName = enclType == null ? "null" : enclType.getTypeName();
      // Unique ids - MD = "metadata"
      return DynUtil.getObjectId(this, null, "MD_" + typeName + "_" + propertyName);
   }

   public int suggestCompletions(String prefix, Object currentType, ExecutionContext ctx, String command, int cursor, Set<String> candidates, Object continuation) {
      if (initializer != null) {
         return initializer.suggestCompletions(prefix, currentType, ctx, command, cursor, candidates, continuation);
      }

      Object obj = ctx.getCurrentObject();

      if (obj == null)
         obj = currentType;

      if (!(obj instanceof Class) && !(obj instanceof ITypeDeclaration)) {
         if (obj != null)
            obj = DynUtil.getType(obj);
      }

      String lastIdent = propertyName;
      if (lastIdent == null)
         lastIdent = "";
      // We don't end up with an identifier for the "foo." case.  Just look for all under foo in that case.
      int pos = -1;

      if (parseNode != null) {
         pos = parseNode.getStartIndex();
         if (pos != -1)
            pos += parseNode.lastIndexOf(lastIdent);
      }

      if (pos == -1)
         pos = command.lastIndexOf(lastIdent);

      JavaModel model = getJavaModel();
      if (obj != null)
         ModelUtil.suggestMembers(model, obj, lastIdent, candidates, false, true, true, false);

      return pos;
   }

   public boolean applyPartialValue(Object value) {
      if (value instanceof Expression) {
         if (initializer == null)
            setProperty("initializer", value);
         else {
            return initializer.applyPartialValue(value);
         }
      }
      return false;
   }

   public void setNodeName(String newName) {
      setProperty("propertyName", newName);
   }

   public String getNodeName() {
      return propertyName;
   }

   public String toListDisplayString() {
      StringBuilder res = new StringBuilder();
      res.append(propertyName);
      if (operator != null) {
         res.append(" ");
         res.append(operator);

         if (initializer != null) {
            res.append(" ");
            res.append(initializer.toLanguageString());
         }
      }
      return res.toString();
   }

   public PropertyAssignment refreshNode() {
      BodyTypeDeclaration type = getEnclosingType().refreshNode();
      if (type == null)
         return this;
      if (bindingDirection == null || bindingDirection.doForward()) {
         Object newAssign = type.declaresMember(propertyName, MemberType.AssignmentSet, null, null);
         if (newAssign instanceof PropertyAssignment)
            return (PropertyAssignment) newAssign;
         displayError("Property removed? ");
      }
      // Reverose only properties are not one per type - instead we need to match them structurally to be sure we have the right one in the new type.
      else {
         if (type != getEnclosingType() && type.body != null) {
            for (Statement st : type.body) {
               if (st instanceof PropertyAssignment) {
                  PropertyAssignment other = (PropertyAssignment) st;
                  if (other.propertyName.equals(propertyName) && StringUtil.equalStrings(operator, other.operator) && bindingDirection == other.bindingDirection &&
                          DynUtil.equalObjects(initializer, other.initializer))
                     return other;
               }
            }
            displayError("Property not found after refresh? ");
         }
      }
      return this;
   }

   public ISrcStatement findFromStatement(ISrcStatement st) {
      ISrcStatement res = super.findFromStatement(st);
      if (res != null)
         return res;

      if (st == fromAttribute || (fromAttribute != null && fromAttribute.findFromStatement(st) != null))
         return this;
      return null;
   }

   public void addBreakpointNodes(List<ISrcStatement> res, ISrcStatement st) {
      /* TODO: What we really want to do here is to stop when the binding fires.  If we set the assigned property, we'll stop too often when a class-hierarchy is involved if we just propagate to the property.
       * one way we could do this is to genenerate some code for each binding for debugging purposes, then call this code reflectively.   A better way would be to support dynamic code breakpoints... this will require
       * hooking into the runtime at a lower level... and building the RPC interface to dynamic code so we can communicate with the remote process.
      if (assignedProperty instanceof Statement) {
         ((Statement) assignedProperty).addBreakpointNodes(res, st);
      }
      */
      super.addBreakpointNodes(res, st);
   }

   public ISrcStatement getSrcStatement(Language lang) {
      // We will propagate this request as long as this node is not in the target language
      if (fromAttribute != null && (parseNode == null || parseNode.getParselet().getLanguage() != lang))
         return fromAttribute.getSrcStatement(lang);
      return super.getSrcStatement(lang);
   }

   public boolean needsEnclosingClass() {
      if (initializer != null)
         return initializer.needsEnclosingClass();
      return false;
   }

   public void addMembersByName(Map<String,List<Statement>> membersByName) {
      if (propertyName != null) {
         addMemberByName(membersByName, propertyName);
      }
   }

   public boolean isReverseOnlyExpression() {
      return bindingDirection != null && !bindingDirection.doForward();
   }
}
