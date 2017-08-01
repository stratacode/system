/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.ArraySelectorBinding;
import sc.dyn.DynUtil;
import sc.lang.ISrcStatement;
import sc.lang.JavaLanguage;
import sc.lang.ILanguageModel;
import sc.lang.sc.PropertyAssignment;
import sc.parser.*;
import sc.type.CTypeUtil;
import sc.type.RTypeUtil;
import sc.bind.BindingDirection;
import sc.bind.Bind;
import sc.bind.IBinding;
import sc.lang.SemanticNodeList;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public class SelectorExpression extends ChainedExpression {
   public SemanticNodeList<Selector> selectors;

   transient IdentifierExpression.IdentifierType[] idTypes;
   transient Object[] boundTypes;
   transient boolean isAssignment;
   transient boolean referenceInitializer;
   transient Object inferredType;
   transient boolean inferredFinal = true;

   public static SelectorExpression create(Expression baseExpr, Selector... selectors) {
      SelectorExpression selExpr = new SelectorExpression();
      // Need to use chainedExpression here since this is what's in the model
      selExpr.setProperty("chainedExpression", baseExpr);
      selExpr.setProperty("selectors", new SemanticNodeList(0));
      for (Selector sel:selectors)
         selExpr.selectors.add(sel);
      return selExpr;
   }

   private void resolveTypeDeclaration() {
      if (expression == null)
         return; // fragment was parsed without an expression

      // The root expression in a selector does not have an inferred type.  This is just to trigger the propagation of inferred types
      // since it's the only case where a sub-expression does not have an inferred type.
      //expression.setInferredType(null);
      int sz;

      if (selectors != null && (sz = selectors.size()) > 0) {
         idTypes = new IdentifierExpression.IdentifierType[sz];
         boundTypes = new Object[sz];

         Object currentType = expression.getGenericType();
         Object origCurrentType = null;
         for (int i = 0; i < selectors.size(); i++) {
            Selector sel = selectors.get(i);
            if (currentType == null) {
               idTypes[i] = IdentifierExpression.IdentifierType.Unknown;
            }
            else {
               origCurrentType = currentType;
               if (sel instanceof VariableSelector) {
                  VariableSelector vsel = (VariableSelector) sel;
                  String nextName = vsel.identifier;
                  boolean isLast = i == selectors.size() - 1;
                  // We do not have an inferredType for intermediate elements in the selector expression - only the last one
                  Object useInferredType = isLast ? inferredType : null;
                  if (nextName.equals("this")) {
                     idTypes[i] = IdentifierExpression.IdentifierType.ThisExpression;
                     boundTypes[i] = currentType;

                     TypeDeclaration exprEnclType = getEnclosingType();
                     if (!ModelUtil.isOuterType(exprEnclType, currentType)) {
                        expression.displayError("Type: " + ModelUtil.getClassName(currentType) + " not an enclosing type of: " + ModelUtil.getClassName(exprEnclType) + " for: ");
                     }
                  }
                  else if (nextName.equals("super")) {
                     idTypes[i] = IdentifierExpression.IdentifierType.SuperExpression;
                     // You can prefix super in two different situations for different reasons.  One is to refer to the super of an enclosing class.
                     // In this case, the prefix is the enclosing type and we want to get the super class of that type.
                     // The second is from an interface default method which implements more than one interface, and where you want to call another default
                     // method on one of those interfaces.  Here the prefix to super that you specify is the interface whose method you should call.
                     if (ModelUtil.isOuterType(getEnclosingType(), currentType)) {
                        boundTypes[i] = ModelUtil.getExtendsClass(currentType);
                        if (boundTypes[i] == null)
                           displayError("No super class for type: " + currentType + " for 'super' ");
                     }
                     else {
                        boundTypes[i] = currentType;
                     }
                  }
                  else {
                     IdentifierExpression.bindNextIdentifier(this, currentType, nextName, i, idTypes, boundTypes,
                             vsel.isAssignment, vsel.arguments != null, vsel.arguments, vsel.getMethodTypeArguments(), bindingDirection, false, isLast ? useInferredType : null);
                     currentType = IdentifierExpression.getGenericTypeForIdentifier(idTypes, boundTypes, vsel.arguments, i, getJavaModel(), currentType, useInferredType, getEnclosingType());
                  }
                  // If we already have the inferred type or this is the root expression and so has no inferred type - we re-resolve the type after propagating the infeerred type.  This may fill in type parameters and other information
                  // we need to make the type correct.
                  if (vsel.arguments != null && boundTypes[i] != null && (inferredType != null || !hasInferredType())) {
                     IdentifierExpression.propagateInferredArgs(this, boundTypes[i], vsel.arguments);
                     currentType = IdentifierExpression.getGenericTypeForIdentifier(idTypes, boundTypes, vsel.arguments, i, getJavaModel(), origCurrentType, useInferredType, getEnclosingType());
                  }
               }
               else if (sel instanceof ArraySelector) {
                  boundTypes[i] = ModelUtil.getArrayComponentType(currentType);
                  idTypes[i] = IdentifierExpression.IdentifierType.ArraySelector;
                  currentType = boundTypes[i];
               }
               else // TODO: support the new selector
                  throw new UnsupportedOperationException();
            }
         }
      }
   }

   public void start() {
      if (started) return;

      super.start();

      resolveTypeDeclaration();
   }

   public void validate() {
      if (validated) return;

      super.validate();

      if (bindingDirection != null) {
         Object referenceType;
         int numSelectors = selectors.size();
         for (int i = 0; i < numSelectors; i++) {
            Selector sel = selectors.get(i);
            if (sel instanceof VariableSelector) {
               VariableSelector vsel = (VariableSelector) sel;
               if (vsel.arguments == null && !vsel.identifier.equals("this")) {
                  if (i == 0)
                     referenceType = expression.getTypeDeclaration();
                  else
                     referenceType = getTypeDeclaration(i-1);
                  if (idTypes != null && boundTypes != null) {
                     IdentifierExpression.makeBindable(this,
                         vsel.identifier, idTypes[i], boundTypes[i], getTypeDeclaration(i), referenceType, !bindingDirection.doForward(), true);
                  }
                  else {
                     System.out.println("*** Selector expression not started yet");
                  }
               }
            }
         }
      }
   }

   public void stop() {
      if (boundTypes == null || idTypes == null)
         return;

      // Force the subclass to do a stop even if we've only been resolved, not started
      if (!started)
         started = true;

      super.stop();
      boundTypes = null;
      idTypes = null;
   }

   public Object eval(Class expectedType, ExecutionContext ctx) {
      if (bindingDirection != null)
         return initBinding(expectedType, ctx);

      Object val = expression.eval(expectedType, ctx);
      int numSelectors = selectors.size();
      for (int i = 0; i < numSelectors; i++) {
         switch (idTypes[i]) {
            case ThisExpression:
               if (i != numSelectors - 1)
                  val = IdentifierExpression.getRootFieldThis(this, boundTypes[i+1], ctx, true);
               // This is the TypeName.this case.  the "val" should be the typeObj - just find the "this" of that type
               // in the current context.
               else
                  val = IdentifierExpression.getCurrentThisType(this, val, ctx);
               continue;

            case SuperExpression:
               if (!DynUtil.isType(val)) // Expr might have resolved to an object?
                  val = DynUtil.getType(val);
               val = IdentifierExpression.getCurrentThisType(this, val, ctx);
               continue;

            default:
               break;
         }
         Selector selector = selectors.get(i);
         if (val == null)
            throw new NullPointerException("Null value in expression: " + selector + " for: " + this);
         val = selector.evalSelector(val, expectedType, ctx, boundTypes[i]);
      }
      return val;
   }

   public void setValue(Object value, ExecutionContext ctx) {
      Object obj = expression.eval(null, ctx);
      int numSelectors = selectors.size()-1;
      int i;
      Selector selector;
      for (i = 0; i < numSelectors; i++) {
         selector = selectors.get(i);
         if (obj == null)
            throw new NullPointerException("Null encountered getting: " + selector + " for: " + toDefinitionString());

         switch (idTypes[i]) {
            case ThisExpression:
               obj = ctx.findThisType(obj);
               continue;
         }

         obj = selector.evalSelector(obj, null, ctx, boundTypes[i]);
      }
      selector = selectors.get(i);
      if (obj == null)
         throw new NullPointerException("Null encountered setting: " + selector + " for: " + toDefinitionString());
      selector.setValue(obj,value,ctx);
   }

   public void setAssignment(boolean assign) {
      if (selectors == null || selectors.size() == 0)
         throw new UnsupportedOperationException();

      isAssignment = true;
      selectors.get(selectors.size()-1).setAssignment(assign);
   }

   public void setProperty(Object selector, Object value) {
      String prop = RTypeUtil.getPropertyNameFromSelector(selector);
      if (prop.equals("expression"))
         prop = "chainedExpression";
      super.setProperty(prop, value);
   }

   public void convertToSetMethod(Expression arg) {
      int last = selectors.size()-1;
      if (idTypes[last] == IdentifierExpression.IdentifierType.ArraySelector) {
         assert last > 0;
         VariableSelector varSel = (VariableSelector) selectors.get(last-1);
         assert varSel.arguments == null;  // needsSetBelow controls this
         ArraySelector arrSel = (ArraySelector) selectors.get(last);
         SemanticNodeList<Expression> newArgs = new SemanticNodeList<Expression>(2);
         newArgs.add(arrSel.expression); // TODO: Need to cast this to an int in some cases like for short etc?
         newArgs.add(arg);
         selectors.remove(last);
         varSel.setProperty("arguments", newArgs);
         varSel.setProperty("identifier", IdentifierExpression.convertPropertyToSetName(varSel.identifier));
         ParseUtil.restartComponent(this); // since we made changes to selectors
      }
      else {
         VariableSelector lastSel = (VariableSelector)selectors.get(last);

         assert lastSel.arguments == null;

         SemanticNodeList<Expression> newArgs = new SemanticNodeList<Expression>(1);
         newArgs.add(arg);
         lastSel.setProperty("arguments", newArgs);
         lastSel.setProperty("identifier",  IdentifierExpression.convertPropertyToSetName(lastSel.identifier));
      }
   }

   public boolean needsSetMethod() {
      if (idTypes == null)
         return false;
      int last = idTypes.length-1;
      IdentifierExpression.IdentifierType type = idTypes[last];
      VariableSelector varSel;
      return type == IdentifierExpression.IdentifierType.SetVariable ||
              // VariableSelector.ArraySelector -> setIndexedProperty(array, value)
             (type == IdentifierExpression.IdentifierType.ArraySelector &&
             ((ArraySelector)selectors.get(last)).isAssignment && last > 0 && selectors.get(last-1) instanceof VariableSelector &&
             (varSel = (VariableSelector) selectors.get(last-1)).arguments == null && IdentifierExpression.needsGetSet(idTypes[last-1], boundTypes[last-1])) ||
             type == IdentifierExpression.IdentifierType.FieldName && IdentifierExpression.needsGetSet(idTypes[last], boundTypes[last]) && ((VariableSelector) selectors.get(last)).isAssignment;
   }

   public Object getTypeDeclaration() {
      if (!started)
         start();
      if (boundTypes == null)
         return null;
      return getTypeDeclaration(boundTypes.length-1);
   }

   /** For this.a[i].b returns the type of this.a[i] */
   public Object getParentReferenceType() {
      if (boundTypes == null || boundTypes.length < 2)
         return null;
      return getTypeDeclaration(boundTypes.length-2);
   }

   /** For this.a[i].b returns "b" */
   public String getReferencePropertyName() {
      int sz = selectors.size();
      Selector sel;
      if ((sel = selectors.get(sz-1)) instanceof VariableSelector)
         return ((VariableSelector) sel).identifier;
      return null;
   }

   /** Returns a new expression for the root part of this selector expression: a[i].b returns expr for a[i] */
   public Expression getParentReferenceTypeExpression() {
      Expression baseExpr = (Expression) expression.deepCopy(CopyNormal, null);
      int sz;
      if ((sz = selectors.size()) == 1)
         return baseExpr;
      else {
         Selector[] rootSels = selectors.subList(0,sz-1).toArray(new Selector[sz-1]);
         return  SelectorExpression.create(baseExpr, rootSels);
      }
   }

   public boolean getLHSAssignmentTyped() {
      if (boundTypes == null)
         return false;

      int last = boundTypes.length - 1;
      return idTypes[last] == IdentifierExpression.IdentifierType.MethodInvocation && ModelUtil.isLHSTypedMethod(boundTypes[last]);
   }

   public Object getTypeDeclaration(int ix) {
      if (boundTypes == null)
         return null;
      int last = selectors.size()-1;
      Object rootType = last == 0 ? expression.getGenericType() : getGenericTypeForSelector(last, null);
      return IdentifierExpression.getTypeForIdentifier(idTypes, boundTypes, getArguments(ix), ix, getJavaModel(), rootType, inferredType, getEnclosingType());
   }

   public Object getGenericType() {
      if (!isStarted()) {
         ParseUtil.initComponent(this);
         ParseUtil.startComponent(this);
      }

      int last = selectors.size()-1;

      Object rootType = last == 0 ? expression.getGenericType() : getGenericTypeForSelector(last, null);
      return IdentifierExpression.getGenericTypeForIdentifier(idTypes, boundTypes, getArguments(last), last, getJavaModel(), rootType, inferredType, getEnclosingType());
   }

   private Object getGenericTypeForSelector(int i, Object currentType) {
      if (currentType == null) {
         currentType = i == 0 ? expression.getGenericType() : getGenericTypeForSelector(i-1, null);
      }
      Selector sel = selectors.get(i);
      if (sel instanceof VariableSelector) {
         VariableSelector vsel = (VariableSelector) sel;
         return IdentifierExpression.getGenericTypeForIdentifier(idTypes, boundTypes, vsel.arguments, i, getJavaModel(), currentType, inferredType, getEnclosingType());
      }
      else if (sel instanceof ArraySelector) {
         ArraySelector asel = (ArraySelector) sel;
         if (boundTypes[i] == null)
            return ModelUtil.getArrayComponentType(currentType);
         return boundTypes[i];
      }
      else
         System.err.println("*** unrecognized selector type:");
      return null;
   }

   public Object getAssignedProperty() {
      if (boundTypes == null)
         return null;

      int last = selectors.size()-1;

      if (idTypes == null)
            return null;

      switch (idTypes[last]) {
         case FieldName:
         case GetVariable:
         case IsVariable:
         case SetVariable:
            return boundTypes[last];
         case UnboundName:
            Selector lastSel = selectors.get(last);
            if (lastSel instanceof VariableSelector)
               return ((VariableSelector) lastSel).identifier;
            return null;
      }
      return null;
   }

   private List<Expression> getArguments(int ix) {
      Selector sel = selectors.get(ix);
      if (sel instanceof VariableSelector)
         return ((VariableSelector) sel).arguments;
      return null;
   }

   public boolean isThisExpression() {
      if (idTypes == null) return false;
      for (IdentifierExpression.IdentifierType type:idTypes)
         if (type == IdentifierExpression.IdentifierType.ThisExpression)
            return true;
      return false;
   }

   public boolean isSuperExpression() {
      if (idTypes == null) return false;
      for (IdentifierExpression.IdentifierType type:idTypes)
         if (type == IdentifierExpression.IdentifierType.SuperExpression)
            return true;
      return false;
   }

   // For expression=<type> this => ConstantBinding(<type>.this);
   // For expression=<type>, selectors = this.x ...
   //    bind/bindP:  new IBinding[] { getPropertyMapping(expression.this, x}, nextSelector, ...}
   // For expression=<type>  selectors = this.x(args)
   //    method/methodP:  new IBinding[] { getPropertyMapping(expression.this, x}, nextSelector, ...}

   // When we have additional selectors, we need to go to a base SelectorBinding which takes the above as the first
   // binding expression.  The whole "this.x" thing replaces the first expression in what we do below.

   // Can't use VariableBinding as we have no srcObject. Need a new selectorBinding which takes [dstObj, dstProp] IBinding, IBinding, ...
   // For expression=<value>, [x].y[z]
   //  selector/newSelectorBinding( expression, ArraySelectorBinding(x),
   public void transformBindingArgs(SemanticNodeList<Expression> bindArgs, BindDescriptor bd) {
      if (idTypes[0] == IdentifierExpression.IdentifierType.ThisExpression) {
         if (selectors.size() == 1) {
            bindArgs.add((Expression) this.deepCopy(CopyNormal, null));
         }
         else {
            // We want expression.this, new IBinding[] {getPropertyMapping("n") }
            String exprString = expression.toLanguageString(JavaLanguage.getJavaLanguage().expression);
            // This can sometimes have the spacing on it...
            exprString = exprString.trim();
            bindArgs.add(IdentifierExpression.create(CTypeUtil.prefixPath(exprString, "this")));

            SemanticNodeList<Expression> props = new SemanticNodeList<Expression>(selectors.size()-1);
            Object firstType = expression.getTypeDeclaration();
            selectors(firstType, props, 1);
            bindArgs.add(createBindingArray(props, true));
         }
      }
      else {
         SemanticNodeList<Expression> props = new SemanticNodeList<Expression>(selectors.size()+1);
         Object lastType = expression.getTypeDeclaration(); // Get type before changing parent so it is in context
         props.add(expression);

         selectors(lastType, props, 0);
         bindArgs.add(createBindingArray(props, true));
      }
   }

   public void evalBindingArgs(List<Object> bindArgs, boolean isStatic, Class expectedType, ExecutionContext ctx) {
      if (idTypes[0] == IdentifierExpression.IdentifierType.ThisExpression) {
         if (selectors.size() == 1) {
            bindArgs.add(ctx.getCurrentObject());
         }
         else {
            // We want expression.this, new IBinding[] {getPropertyMapping("n") }
            // Disable binding for this expression - we really want to just eval it...
            // probably should not have propagated bindingDirection in the first place but it only matters
            // here cause above we just skip transforming the expression.
            //
            // the nested arg is false here because we need the value, not the IBinding object
            //expression.setBindingInfo(null, bindingStatement, true);
            //bindArgs.add(expression.eval(expectedType, ctx));

            //bindArgs.add(IdentifierExpression.getRootFieldThis(this, boundTypes[1], ctx));
            Object thisType = expression.getTypeDeclaration();
            Object thisObj;
            thisObj = ctx.findThisType(thisType);
            if (thisObj == null)
               return;
            bindArgs.add(thisObj);
            /*
            int i;
            for (i = ctx.currentObjects.size()-1; i >= 0; i--) {
               thisObj = ctx.currentObjects.get(i);
               if (ModelUtil.isAssignableFrom(thisType, thisObj.getClass())) {
                  bindArgs.add(thisObj);
                  break;
               }
            }
            if (i < 0)
               return;
            */

            List<IBinding> props = new ArrayList<IBinding>(selectors.size()-1);
            evalSelectorBindings(thisType, props, 1, expectedType, ctx);
            bindArgs.add(props.toArray(new IBinding[props.size()]));
         }
      }
      else {
         List<IBinding> props = new ArrayList<IBinding>(selectors.size()+1);
         props.add((IBinding) expression.evalBinding(expectedType, ctx));
         Object lastType = expression.getTypeDeclaration();

         evalSelectorBindings(lastType, props, 0, expectedType, ctx);
         bindArgs.add(props.toArray(new IBinding[props.size()]));
      }
   }

   private void selectors(Object lastType, SemanticNodeList<Expression> props, int start) {
      for (int i = start; i < selectors.size(); i++) {
         VariableSelector vsel;
         switch (idTypes[i]) {
            case RemoteMethodInvocation:
            case MethodInvocation:
               vsel = (VariableSelector) selectors.get(i);
               props.add(createChildMethodBinding(lastType, vsel.identifier, boundTypes[i], vsel.arguments, idTypes[i] == IdentifierExpression.IdentifierType.RemoteMethodInvocation));
               break;
            case BoundTypeName:
            case BoundObjectName:
            case FieldName:
            case GetVariable:
            case IsVariable:
            case SetVariable:
               vsel = (VariableSelector) selectors.get(i);
               props.add(createGetPropertyMappingCall(lastType, vsel.identifier));
               break;
            case ArraySelector:
               ArraySelector asel = (ArraySelector) selectors.get(i);
               props.add(createArraySelectorBinding(asel.expression));
               break;
         }
         lastType = getTypeDeclaration(i);
      }
   }

   private void evalSelectorBindings(Object lastType, List<IBinding> props, int start, Class expectedType, ExecutionContext ctx) {
      for (int i = start; i < selectors.size(); i++) {
         VariableSelector vsel;
         switch (idTypes[i]) {
            case MethodInvocation:
               vsel = (VariableSelector) selectors.get(i);
               props.add(Bind.methodP(ModelUtil.getRuntimeMethod(boundTypes[i]),
                       evalBindingParameters(expectedType, ctx, vsel.arguments.toArray(new Expression[vsel.arguments.size()]))));
               break;
            case BoundTypeName:
            case BoundObjectName:
            case FieldName:
            case GetVariable:
            case IsVariable:
            case SetVariable:
               vsel = (VariableSelector) selectors.get(i);
               props.add(DynUtil.getPropertyMapping(ModelUtil.getRuntimeType(lastType), vsel.identifier));
               break;
            case ArraySelector:
               ArraySelector asel = (ArraySelector) selectors.get(i);
               props.add(new ArraySelectorBinding((IBinding)asel.expression.evalBinding(expectedType, ctx)));
               break;
            case RemoteMethodInvocation:
               System.err.println("*** Remote method invocation for dyanmic type not implemented");
               break;
         }
         lastType = getTypeDeclaration(i);
      }
   }

   private Expression createArraySelectorBinding(Expression arrayExpression) {
      NewExpression boundExpr = new NewExpression();
      boundExpr.typeIdentifier = "sc.bind.ArraySelectorBinding";
      SemanticNodeList<Expression> args = new SemanticNodeList<Expression>(1);
      boundExpr.setProperty("arguments", args);
      args.add(arrayExpression);
      return boundExpr;
   }

   public String getBindingTypeName() {
      if (idTypes[0] == IdentifierExpression.IdentifierType.ThisExpression) {
         if (selectors.size() == 1) {
            if (nestedBinding)
               return "constantP";
            else {
               System.err.println("*** Data binding illegal on constant expression: " + toDefinitionString());
               return null;
            }
         }
         else if (selectors.size() > 1) {
            if (selectors.get(1) instanceof VariableSelector) {
               VariableSelector vsel = (VariableSelector) selectors.get(1);
               if (vsel.arguments == null)
                  return nestedBinding ? "bindP" : "bind";
               else
                  return nestedBinding ? "methodP" : "method";
            }
            else {
               System.err.println("*** Illegal selector type with 'this' expression: " + toDefinitionString());
               return super.getBindingTypeName();
            }
         }
      }
      return nestedBinding ? "selectorP" : "selector";
   }

   public void setBindingInfo(BindingDirection dir, Statement dest, boolean nested) {
      super.setBindingInfo(dir, dest, nested);
      expression.setBindingInfo(dir, dest, true);
      for (int i = 0; i < selectors.size(); i++) {
         Object sel = selectors.get(i);
         if (sel instanceof ArraySelector) {
            ArraySelector asel = (ArraySelector) selectors.get(i);
            asel.expression.setBindingInfo(dir, dest, true);
         }
         else if (sel instanceof VariableSelector) {
            VariableSelector vsel = (VariableSelector) selectors.get(i);
            if (vsel.arguments != null) {
               for (Expression arg:vsel.arguments)
                  arg.setBindingInfo(dir, dest, true);
            }
         }
      }
   }

   public boolean needsTransform() {
      if (bindingStatement != null || super.needsTransform())
         return true;

      int sz;
      if (selectors != null && (sz = selectors.size()) > 0) {
         for (int i = 0; i < sz; i++) {
            switch (idTypes[i]) {
               case BoundObjectName:
                  return true;
               case FieldName:
                  return ModelUtil.needsGetSet(boundTypes[i]);
               case GetVariable:
               case IsVariable:
                  return true;
            }
         }
      }
      return false;
   }

   public boolean transform(ILanguageModel.RuntimeType runtime) {
      if (transformed)
         return false;

      if (bindingStatement != null)
         return super.transform(runtime);

      JavaModel model = getJavaModel();
      if (!model.enableExtensions())
         return super.transform(runtime);

      int sz;
      if (selectors != null && (sz = selectors.size()) > 0) {
         for (int i = 0; i < sz; i++) {
            // Do not transform to a get the last selector in the RHS
            if (isAssignmentSelector(i))
               continue;
            Selector sel = selectors.get(i);
            switch (idTypes[i]) {
               case BoundObjectName:
                  VariableSelector vsel = (VariableSelector) sel;
                  if (vsel.arguments == null)
                     convertToGetMethod((VariableSelector) sel, boundTypes[i], false);
                  else if (!vsel.identifier.startsWith("get"))
                     System.err.println("*** Selector expression bound to object name but not using a get method");
                  break;
               case FieldName:
                  if (!ModelUtil.needsGetSet(boundTypes[i]))
                     break;
               case GetVariable:
                  convertToGetMethod((VariableSelector) sel, boundTypes[i], false);
                  break;
               case IsVariable:
                  convertToGetMethod((VariableSelector) sel, boundTypes[i], true);
                  break;
            }
         }
      }
      return super.transform(runtime);
   }

   private boolean isAssignmentSelector(int sel) {
      // This selector index is an assignment if it is the last one or the last one is an array selector
      return isAssignment && (sel == selectors.size() - 1 || (sel == selectors.size() - 2 && selectors.get(sel+1) instanceof ArraySelector));
   }

   public boolean isReferenceInitializer() {
      referenceInitializer = true;
      return true;
   }

   private void convertToGetMethod(VariableSelector sel, Object type, boolean isPropertyIs) {
      assert sel.arguments == null;
      sel.setProperty("identifier", (isPropertyIs ? "is" : "get") + CTypeUtil.capitalizePropertyName(sel.identifier));
      SemanticNodeList<Expression> args = new SemanticNodeList<Expression>(1);
      if (referenceInitializer && ModelUtil.isComponentType(type))
         args.add(BooleanLiteral.create(false));
      sel.setProperty("arguments", args); // Turn this into an empty method
      ParseUtil.restartComponent(this);
   }

   public int suggestCompletions(String prefix, Object currentType, ExecutionContext ctx, String command, int cursor, Set<String> candidates, Object continuation) {
      boolean dotCommand = command.endsWith(".");

      if (expression == null)
         return -1;

      Object obj = null;
      try {
         obj = expression.eval(null, ctx);
      }
      catch (RuntimeException exc) {
         // ignore these completions
      }

      // First path component is not valid so no real completions from here on
      if (obj == null && (selectors == null || selectors.size() > 0 || dotCommand)) {
         return -1;
      }

      int lastSel = dotCommand ? selectors.size() : selectors.size()-1;

      Selector selector;
      for (int i = 0; i < lastSel; i++) {
         switch (idTypes[i]) {
            case ThisExpression:
               continue;
         }
         selector = selectors.get(i);
         obj = selector.evalSelector(obj, null, ctx, boundTypes[i]);
         if (obj == null)
            return -1;
      }

      String lastIdent;
      int pos = -1;
      if (dotCommand) {
         lastIdent = "";
         pos = command.length();
      }
      else {
         selector = selectors.get(lastSel);
         if (selector instanceof VariableSelector) {
            VariableSelector vsel = (VariableSelector) selector;
            lastIdent = vsel.identifier;
            // TODO: ArraySelector - should we just treat it like dotCommand?
            if (vsel.parseNode != null)
               pos = vsel.parseNode.getStartIndex() + vsel.parseNode.lastIndexOf(lastIdent);
         }
         else
            return -1;

         if (pos == -1)
            pos = command.lastIndexOf(lastIdent);
      }

      if (obj != null) {
         if (!(obj instanceof Class) && !(obj instanceof ITypeDeclaration)) {
            obj = obj.getClass();
         }
         ModelUtil.suggestMembers(getJavaModel(), obj, lastIdent, candidates, false, true, true, false);
         return pos;
      }
      else {
         return -1;
      }
   }

   public String addNodeCompletions(JavaModel origModel, JavaSemanticNode origNode, String extMatchPrefix, int offset, String dummyIdentifier, Set<String> candidates) {
      if (selectors == null)
         return null;
      SelectorExpression origSel = origNode instanceof SelectorExpression ? (SelectorExpression) origNode : null;
      // The origIdent inside of an Element tag will not have been started, but the replacedByStatement which represents in the objects is started
      if (origSel != null && origSel.replacedByStatement instanceof SelectorExpression)
         origSel = (SelectorExpression) origSel.replacedByStatement;

      Object[] useTypes = origSel == null ? boundTypes : origSel.boundTypes;

      for (int i = 0; i < selectors.size(); i++) {
         Selector sel = selectors.get(i);
         if (sel instanceof VariableSelector) {
            VariableSelector vsel = (VariableSelector) sel;
            String ident = vsel.identifier;
            if (ident != null) {
               int dummyIx = ident.indexOf(dummyIdentifier);
               if (dummyIx != -1) {
                  String matchPrefix = ident.substring(0, dummyIx);

                  Object curType = i == 0 ? (expression == null ? null :expression.getTypeDeclaration()) : getTypeDeclaration(i-1);
                                            //(useTypes == null ? null : useTypes[i-1]);

                  if (curType != null)
                     ModelUtil.suggestMembers(origModel, curType, matchPrefix, candidates, false, true, true, false);

                  return matchPrefix;
               }
            }
         }
      }
      return null;
   }

   public void visitTypeReferences(CycleInfo info, TypeContext ctx) {
      info.visit(expression, ctx, false);
      if (boundTypes != null) {
         int ix = 0;
         for (Object type:boundTypes) {
            if (ix > 0)
               info.visit(new CycleInfo.ThisContext(getEnclosingType(), boundTypes[ix-1]), ctx, false);
            else
               info.visit(type, ctx, false);
         }
      }
      info.remove(expression);
      if (boundTypes != null) {
         for (Object type:boundTypes) {
            info.remove(type);
         }
      }
   }

   public boolean applyPartialValue(Object value) {
      if (value instanceof Selector) {
         if (value instanceof VariableSelector) {
            VariableSelector vsel = (VariableSelector) value;
            if (vsel.identifier == null)
               vsel.identifier = "";
         }
         selectors.add((Selector) value);
         return true;
      }
      int sz = selectors.size();
      Selector last = selectors == null || sz == 0 ? null : selectors.get(sz-1);
      if (last instanceof VariableSelector && value instanceof IdentifierExpression) {
         VariableSelector lastVsel = (VariableSelector) last;
         IdentifierExpression partial = (IdentifierExpression) value;
         if (lastVsel.arguments == null && partial.arguments != null) {
            lastVsel.arguments = partial.arguments;
            return true;
         }
      }
      return false;
   }

   public void changeExpressionsThis(TypeDeclaration td, TypeDeclaration outer, String newName) {
      if (isThisExpression()) {
         Object exprType = expression.getTypeDeclaration();
         Object encType = getEnclosingType();
         if (ModelUtil.isAssignableFrom(exprType, encType)) {
            // FirstLevelClass.this -> varName
            IdentifierExpression newExpr = IdentifierExpression.create(newName);
            if (selectors.size() == 1) {
               parentNode.replaceChild(this, newExpr);
            }
            // Convert FirstLevelClass.this.x.y -> varName.x.y
            else {
               setProperty("expression", newExpr);
               selectors.remove(0);
            }
         }
         else {
            // Second level this ref - should be unaffected by transforming a class into a getX method
            super.changeExpressionsThis(td, outer, newName);
            return;
         }
      }
      else {
         super.changeExpressionsThis(td, outer, newName);
         int sz = selectors.size();
         for (int i = 0; i < sz; i++)
            selectors.get(i).changeExpressionsThis(td, outer, newName);
      }
   }

   public void refreshBoundTypes(int flags) {
      super.refreshBoundTypes(flags);
      for (Selector sel:selectors)
         sel.refreshBoundType(flags);
      if (boundTypes != null) {
         for (int i = 0; i < boundTypes.length; i++)
            boundTypes[i] = ModelUtil.refreshBoundIdentifierType(getLayeredSystem(), boundTypes[i], flags);
      }
   }

   public void addDependentTypes(Set<Object> types) {
      super.addDependentTypes(types);
      for (Selector sel:selectors)
         sel.addDependentTypes(types);
      if (boundTypes != null) {
         for (int i = 0; i < boundTypes.length; i++) {
            if (boundTypes[i] != null) {
               switch (idTypes[i]) {
                  case MethodInvocation:
                     types.add(ModelUtil.getEnclosingType(boundTypes[i]));
                     break;
                  default:
                     types.add(getTypeDeclaration(i));
               }
            }
         }
      }
   }

   private void removeSelector(int ix) {
      selectors.remove(ix);
      if (boundTypes != null) {
         int newLen = boundTypes.length - 1;
         Object[] newBoundTypes = new Object[newLen];
         IdentifierExpression.IdentifierType[] newIdTypes = new IdentifierExpression.IdentifierType[newLen];
         int j = 0;
         for (int i = 0; i < boundTypes.length; i++) {
            if (i != ix) {
               newBoundTypes[j] = boundTypes[i];
               newIdTypes[j] = idTypes[i];
               j++;
            }
         }
         boundTypes = newBoundTypes;
         idTypes = newIdTypes;
      }
   }

   public Statement transformToJS() {
      if (idTypes[0] == IdentifierExpression.IdentifierType.ThisExpression) {
         IdentifierExpression newExpr = IdentifierExpression.create("this");
         Object refType = boundTypes[0];
         Object curType = getEnclosingType();
         int i = 1;
         while (curType != null && !ModelUtil.isAssignableFrom(refType, curType)) {
            String identifier = "_outer" + ModelUtil.getOuterInstCount(curType);
            newExpr.addIdentifier(i++, identifier, IdentifierExpression.IdentifierType.FieldName, curType);
            curType = ModelUtil.getEnclosingType(curType);
         }
         if (selectors.size() == 1) {
            parentNode.replaceChild(this, newExpr);
            return newExpr;
         }
         else {
            setProperty("expression", newExpr);
            removeSelector(0);
         }
      }
      super.transformToJS();
      if (selectors != null) {
         int ix = 0;
         for (Selector sel:selectors) {
            switch (idTypes[ix]) {
               case MethodInvocation:
               case RemoteMethodInvocation:
                  Object meth = boundTypes[ix];
                  if (meth != null) {
                     JavaModel model = getJavaModel();
                     boolean cachedOnly = model.customResolver != null && model.customResolver.useRuntimeResolution();
                     meth = ModelUtil.resolveSrcMethod(getLayeredSystem(), meth, cachedOnly, false);

                     Object jsMethSettings = ModelUtil.getAnnotation(meth, "sc.js.JSMethodSettings");
                     if (jsMethSettings != null) {
                        String replaceWith = (String) ModelUtil.getAnnotationValue(jsMethSettings, "replaceWith");
                        if (replaceWith != null && replaceWith.length() > 0) {
                           VariableSelector vsel = (VariableSelector) sel;
                           RTypeUtil.addMethodAlias(ModelUtil.getTypeName(ModelUtil.getEnclosingType(meth)), vsel.identifier, replaceWith);
                           vsel.setProperty("identifier", replaceWith);
                        }
                     }
                  }
                  break;
            }
            sel.transformToJS();
            ix++;
         }
      }
      return this;
   }

   public SelectorExpression deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      SelectorExpression res = (SelectorExpression) super.deepCopy(options, oldNewMap);

      if ((options & CopyState) != 0) {
         // TODO: should we be preserving idTypes and boundTypes in this case like IdentifierExpression.  If so, also need
         // to preserve them the way it does in start.
      }

      if ((options & CopyInitLevels) != 0) {
         if (idTypes != null) {
            res.idTypes = idTypes.clone();
         }
         if (boundTypes != null) {
            res.boundTypes = boundTypes.clone();
         }
         res.isAssignment = isAssignment;
         res.referenceInitializer = referenceInitializer;
      }

      return res;
   }

   public String toGenerateString() {
      StringBuilder sb = new StringBuilder();
      if (expression != null)
         sb.append(expression.toGenerateString());
      if (selectors != null) {
         for (Selector sel:selectors) {
            sb.append(sel.toGenerateString());
         }
      }
      return sb.toString();
   }

   public boolean producesHtml() {
      if (selectors != null) {
         int lastIx = selectors.size()-1;
         Selector last = selectors.get(lastIx);
         if (last instanceof VariableSelector) {
            VariableSelector vsel = (VariableSelector) last;
            Object typeObj = boundTypes[lastIx];
            if (typeObj != null) {
               Object annotVal = ModelUtil.getAnnotationValue(typeObj, "sc.obj.HTMLSettings", "returnsHTML");
               return annotVal != null && (Boolean) annotVal;
            }
         }
      }
      return false;
   }

   public ISrcStatement findFromStatement(ISrcStatement st) {
      ISrcStatement res = super.findFromStatement(st);
      if (res != null)
         return res;
      if (expression != null && expression.findFromStatement(st) != null)
         return this;
      // The breakpoint may be set on some expression that has been embedded into one of our arguments.  If so, we are the closest statement to the breakpoint
      if (selectors != null) {
         for (Selector sel:selectors) {
            if (sel.findFromStatement(st) != null)
               return this;
         }
      }
      return null;
   }

   public void addBreakpointNodes(List<ISrcStatement> res, ISrcStatement srcStatement) {
      super.addBreakpointNodes(res, srcStatement);
      if (expression != null) {
         expression.addBreakpointNodes(res, srcStatement);
      }
      // The breakpoint may be set on some expression that has been embedded into one of our arguments.  If so, we are the closest statement to the breakpoint
      if (selectors != null) {
         for (Selector sel:selectors) {
            sel.addGeneratedFromNodes(res, srcStatement);
         }
      }
   }

   /** For Foo.this which refers to an outer class, it requires we generate the 'this' class so we can resolve it. */
   public boolean needsEnclosingClass() {
      if (isThisExpression() && getEnclosingType() != getTypeDeclaration())
         return true;
      return false;
   }

   public boolean setInferredType(Object inferredType, boolean finalType) {
      this.inferredType = inferredType;
      this.inferredFinal = finalType;
      // Re-resolve this now that we have the inferred type
      resolveTypeDeclaration();
      return false;
   }

   public boolean isInferredSet() {
      return inferredType != null || !hasInferredType();
   }

   public boolean isInferredFinal() {
      return inferredFinal;
   }

   // We propagate to arguments in VariableSelectors but not to the root expression and not when a direct child of a PropertyAssignment that's a reverse only binding
   public boolean propagatesInferredType(Expression child) {
      if (child == expression)
         return false;
      // Since child can be any descendant, if it's an argument to a method it has an inferred type
      if (isSelectorArg(child))
         return true;
      // TODO: Not sure what other expression children a selector expression can have but there's a case where we are a child of a PropertyAssignment, reverse-only binding which might get here?
      if (!hasInferredType())
         return false;
      return true;
   }

   private boolean isSelectorArg(Expression child) {
      if (selectors == null)
         return false;
      for (Selector sel:selectors) {
         if (sel instanceof VariableSelector) {
            VariableSelector vsel = (VariableSelector) sel;
            if (vsel.arguments != null && vsel.arguments.contains(child))
               return true;
         }
      }
      return false;
   }

   /* For Selector expressions, styling is done in the Selector sub-classes.
    * Because identifier expressions use a list of strings, we needed to 
    * handle them up one level, but that essentially duplicated a lot of logic
    * in the parselets grammar to make that happen. 
   public void styleNode(IStyleAdapter adapter) {
   }
   */

   public Object getBoundType(int ix) {
      return boundTypes == null || boundTypes.length <= ix ? null : boundTypes[ix];
   }
}
