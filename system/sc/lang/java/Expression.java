/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.ConstantBinding;
import sc.lang.*;
import sc.lang.js.JSLanguage;
import sc.lang.sc.PropertyAssignment;
import sc.layer.LayeredSystem;
import sc.parser.ParseError;
import sc.parser.ParseUtil;
import sc.type.CTypeUtil;
import sc.type.RTypeUtil;
import sc.type.TypeUtil;
import sc.bind.BindingDirection;
import sc.bind.Bind;
import sc.bind.IBinding;
import sc.util.StringUtil;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public abstract class Expression extends Statement implements IValueNode, ITypedObject {
   transient public BindingDirection bindingDirection;
   transient public Statement bindingStatement;
   transient public boolean nestedBinding;
   transient public Statement replacedByStatement;

   /** Set this to true on an expression to disable it - it won't resolve itself during start */
   transient public boolean inactive;
   //transient public Definition movedToDefinition;

   public static Expression parse(String expressionStr) {
      JavaLanguage lang = JavaLanguage.getJavaLanguage();
      Object result = lang.parseString(expressionStr, lang.optExpression);
      if (result instanceof ParseError) {
         System.err.println("Unable to parse expression string: " + result);
         return null;
      }
      else
         return (Expression) ParseUtil.nodeToSemanticValue(result);
   }

   public static Expression createFromValue(Object literalValue, boolean isInitializer) {
      if (literalValue == null)
         return NullLiteral.create();
      Class cl = literalValue.getClass();
      if (cl.isArray()) {
         if (isInitializer) {
            return ArrayInitializer.create(literalValue);
         }
         else
            return NewExpression.create(TypeUtil.getTypeName(cl, false), new SemanticNodeList<Expression>(0), ArrayInitializer.create(literalValue));
      }
      else {
         return AbstractLiteral.createFromValue(literalValue, isInitializer);
      }
   }

   public long evalLong(Class expectedType, ExecutionContext ctx) {
      Object valObj = eval(expectedType, ctx);
      return ((Number) valObj).longValue();
   }

   public double evalDouble(Class expectedType, ExecutionContext ctx) {
      Object valObj = eval(expectedType, ctx);
      return ((Number) valObj).doubleValue();
   }

   /** Only needed for expressions that are the left hand side of the equals sign */
   public void setAssignment(boolean assign) {
      displayError("Illegal target of assignment: ");
   }

   public boolean needsSetMethod() {
      return false;
   }

   /** Only needed for expressions that are the left hand side of the equals sign */
   public void convertToSetMethod(Expression arg) {
      throw new UnsupportedOperationException();
   }

   public void setValue(Object value, ExecutionContext ctx) {
      throw new UnsupportedOperationException();
   }

   public Class getRuntimeClass() {
      Object td = getTypeDeclaration();
      if (td == null)
         return null;

      return ModelUtil.getCompiledClass(td);
   }

   /** BindingDirection only set if this is a top-level binding */
   public void setBindingInfo(BindingDirection dir, Statement dest, boolean nested) {
      bindingDirection = dir;
      bindingStatement = dest;
      nestedBinding = nested;
      if (bindingStatement instanceof AssignmentExpression) {
         Statement from = ((AssignmentExpression) bindingStatement).getFromStatement();
         if (from != null)
            bindingStatement = from;
      }
   }

   public boolean needsTransform() {
      return bindingStatement != null || super.needsTransform();
   }

   public boolean transform(ILanguageModel.RuntimeType runtime) {
      if (transformed || inactive)
         return false;

      boolean any = false;
      if (bindingStatement != null && getEnclosingType().getDeclarationType() != DeclarationType.INTERFACE) {
         transformBinding(runtime);
         any = true;

         // No need to transform the rest of this - since we were just replaced
         if (replacedByStatement != null) {
         // This may be a more accurate test for what we need here but obviously slower
         //if (parentNode.containsChild(this))

            return true;
         }
      }
      else if (super.transform(runtime))
         any = true;

      return any;
   }

   public Expression applyJSConversion(Object paramType) {
      // The char to int conversion needs an extra method call in Javascript land.  We store characters as strings[1].
      // That let's concatenation and char to String conversion work but we need to do something special to get int to char.
      if (ModelUtil.isInteger(paramType)) {
         Object exprType = getTypeDeclaration();
         if (ModelUtil.isCharacter(exprType)) {
            SemanticNodeList<Expression> cvtArgs = new SemanticNodeList<Expression>();
            cvtArgs.add(this);
            return IdentifierExpression.createMethodCall(cvtArgs, "sc_charToInt");
         }
      }
      return null;
   }

   public boolean producesHtml() {
      return false;
   }

   static class BindDescriptor {
      TypeDeclaration type;
      boolean isStatic;
      Object dstPropType;
      Object dstEnclType;    // Enclosing type of the dst property
      boolean dstIsBindable; // Optimization controling whether or not we use the _prop or the "xxx"
      boolean needsCast = true;
      // The dstProp parameter: the name of the variableDefinition or PropertyAssignment
      String dstProp;
      // Normally bindings are on properties of "this" but in some cases we move a binding to optimize out that
      // class.  In those cases, we have an expression to get to the dst object.  It may be a method call like getX
      // so the "thisObjectArguments" are set in that case.
      String thisObjectPrefix = "this";
      // When we need to add a binding to a parent of the current type, this refers to the parent type's info
      String thisTypePrefix = "";
      // When the dstProp is not directly on the parent type, this defines the parent prefix to this
      SemanticNodeList thisObjectArguments = null;
      // for a = y = v, in some cases we convert to the DynUtil.setAndReturn method.  LHS needs to convert to a single property in that case.
      boolean isSetAndReturnLHS;

      boolean getUseMapper() {
         return type.getLayeredSystem().usePropertyMappers && dstIsBindable && dstEnclType != null && !ModelUtil.isDynamicType(dstEnclType);
      }
   }

   BindDescriptor getBindDescriptor() {
      BindDescriptor bd = new BindDescriptor();
      bd.type = getEnclosingType();

      if (bindingStatement instanceof FieldDefinition) {
         if (!nestedBinding) {
            ISemanticNode parExpr = parentNode;
            // Skip parens and binary expression wrappers
            while (parExpr instanceof ParenExpression || parExpr instanceof BinaryExpression)
               parExpr = ((JavaSemanticNode) parExpr).parentNode;

            if (parExpr instanceof SemanticNodeList && parExpr.getParentNode() instanceof BlockStatement) {
               BlockStatement reverseBindingBlock = (BlockStatement) parExpr.getParentNode();
               parExpr = reverseBindingBlock.fromDefinition;
               bd.needsCast = false;
            }

            if (parExpr instanceof VariableDefinition) {
               VariableDefinition def = (VariableDefinition) parExpr;
               // This flag controls whether or not we use the generated _prop thing.  Bindable(manual=true)
               // this needs to be false.
               bd.dstIsBindable = ModelUtil.isAutomaticBindable(def);
               bd.dstProp = def.variableName;
            }
            else if (parExpr instanceof PropertyAssignment) {
               PropertyAssignment def = (PropertyAssignment) parExpr;
               bd.dstIsBindable = ModelUtil.isAutomaticBindable(def.assignedProperty);
               bd.dstProp = def.propertyName;
            }
            else {
               Statement statement = getEnclosingStatement();
               while (statement instanceof ParenExpression || statement instanceof CastExpression) // Skip parens
                  statement = statement.getEnclosingStatement();
               if (statement instanceof IdentifierExpression) {
                  // TODO: any other cases we need to check here or error conditions?
                  Object statementType = ((IdentifierExpression)statement).boundTypes[0];
                  if (statementType instanceof IMethodDefinition) {
                     IMethodDefinition methDef = (IMethodDefinition) statementType;
                     bd.dstProp = methDef.getPropertyName();
                  }
                  else if (statementType instanceof VariableDefinition) {
                     VariableDefinition varDef = (VariableDefinition) statementType;
                     bd.dstProp = varDef.variableName;
                  }
                  else {
                     System.err.println("*** Unrecognized statement type in binding expression " + toDefinitionString());
                     return null;
                  }
                  bd.dstIsBindable = ModelUtil.isAutomaticBindable(statementType);
               }
               else if (statement instanceof AssignmentExpression) {
                  AssignmentExpression exp = (AssignmentExpression) statement;
                  bd.dstProp = exp.getInitializedProperty();
                  bd.dstIsBindable = exp.isBindableProperty();
               }
               else {
                  System.err.println("*** Invalid statement for binding expression: " + toDefinitionString());
                  return null;
               }
               if (bd.dstProp == null) {
                  System.err.println("*** Invalid binding expression - method does not define a property: " + toDefinitionString());
                  return null;
               }
            }
         }
         else {
            bd.dstIsBindable = false;
            bd.dstProp = null;
         }
         bd.dstPropType = ((FieldDefinition) bindingStatement).type.getTypeDeclaration();
         bd.isStatic = bindingStatement.hasModifier("static");
      }
      else if (bindingStatement instanceof PropertyAssignment) {
         PropertyAssignment pa = (PropertyAssignment) bindingStatement;
         bd.dstProp = pa.propertyName;
         bd.dstPropType = pa.getTypeDeclaration();
         bd.dstIsBindable = ModelUtil.isAutomaticBindable(pa.assignedProperty);
         bd.isStatic = ModelUtil.hasModifier(pa.getPropertyDefinition(), "static");
         if (bindingDirection != null && !bindingDirection.doForward())
            bd.needsCast = false;
         if (bd.type.skippedClassVarName != null)
            bd.thisObjectPrefix = bd.type.skippedClassVarName;

         // If we have a property assignment which bound itself to an outer variable, need to figure out the prefix
         // to apply to "this" to reach the parent "this".
         BodyTypeDeclaration assignType = pa.getEnclosingType();
         BodyTypeDeclaration propOwnerType = assignType;
         Object propType = ModelUtil.getEnclosingType(pa.assignedProperty);

         while (!ModelUtil.isAssignableFrom(propType, propOwnerType)) {
            propOwnerType = propOwnerType.getEnclosingType();
            if (propOwnerType == null) {
               System.err.println("** Can't find type which binds to property");
               break;
            }
         }
         if (propOwnerType != assignType) {
            bd.thisTypePrefix = ModelUtil.getTypeName(propOwnerType);
         }
      }
      else if (bindingStatement instanceof AssignmentExpression) {
         AssignmentExpression ae = (AssignmentExpression) bindingStatement;
         Object fieldDef = ae.assignedProperty;
         if (fieldDef == null) {
            if (ae.lhs instanceof SelectorExpression)
               fieldDef = ((SelectorExpression)ae.lhs).getAssignedProperty();
         }
         bd.dstProp = ModelUtil.getPropertyName(fieldDef);
         // Because this is an assignment, we'll get mapped to a setX method in some cases
         if (fieldDef instanceof Method || fieldDef instanceof IMethodDefinition)
            bd.dstPropType = ModelUtil.getSetMethodPropertyType(fieldDef);
         else
            bd.dstPropType = ModelUtil.getVariableTypeDeclaration(fieldDef);
         bd.dstIsBindable = ModelUtil.isAutomaticBindable(fieldDef);
         bd.dstEnclType = ModelUtil.getEnclosingType(fieldDef);
         bd.isStatic = ModelUtil.hasModifier(fieldDef, "static");
         if (bindingDirection != null && !bindingDirection.doForward())
            bd.needsCast = false;

         /**
          * Because of the optimization where we can move a property assignment as a regular assignment expression
          * we can end up with a non-trivial identifier expression here: either foo.property = bind(...)
          * or getFoo().property = bind(...).  Hopefully no more complex cases make it here.  In this case,
          * the this prefix needs to be fixed to have that extra property indirection.
          */
         if (ae.lhs instanceof IdentifierExpression) {
            IdentifierExpression ie = (IdentifierExpression) ae.lhs;
            int ieSize = ie.identifiers.size();
            if (ieSize == 2) {
               bd.thisObjectPrefix = ie.identifiers.get(0).toString();
            }
            // Verified that at least one of these cases works
            //else if (ieSize != 1)
            //   System.err.println("*** Can't handle complex identifier expression for assignment: " + ie.toDefinitionString());
         }
         if (ae.lhs instanceof SelectorExpression) {
            SelectorExpression sel = (SelectorExpression) ae.lhs;
            if (sel.selectors.size() == 1 && sel.expression instanceof IdentifierExpression) {
               IdentifierExpression prefixExpr = (IdentifierExpression) sel.expression;
               if (prefixExpr.identifiers.size() == 1) {
                  bd.thisObjectPrefix = prefixExpr.identifiers.get(0).toString();
                  if (prefixExpr.arguments != null) {
                     bd.thisObjectArguments = (SemanticNodeList) prefixExpr.arguments.deepCopy(ISemanticNode.CopyNormal, null);
                  }
               }
            }
            else {
               System.err.println("*** Can't handle complex selector expression for binding: " + sel.toDefinitionString());
            }
         }
      }
      // Assignment expression, lhs will not propagate the bindingStatement.  Just need the isStatic flag used in
      // transformBindingArgs of IdentifierExpression
      else if (bindingStatement != null)
         throw new UnsupportedOperationException();

      return bd;
   }

   public Object evalBinding(Class expectedType, ExecutionContext ctx) {
      String bindingType = getBindingTypeName();

      assert bindingStatement != null && bindingType != null;

      List<Object> bindArgs = new ArrayList<Object>(5);
      BindDescriptor bd = getBindDescriptor();

      if (!nestedBinding) {  // Skip if its a hierarchical binding - don't need the dst - the binding will set our bindingParent
         if (bd.isStatic)
            bindArgs.add(bd.type.getRuntimeType());
         else
            bindArgs.add(ctx.getCurrentObject());

         // If the dst is bindable, take a shortcut and refer to its static "prop" mapping.  Otherwise, just look it up.
         bindArgs.add(bd.dstProp);
      }

      int initSize = bindArgs.size();
      evalBindingArgs(bindArgs, bd.isStatic, expectedType, ctx);

      // TODO: return value for evalBindingArgs for errors.
      if (bindArgs.size() == initSize) {
         if (nestedBinding)
            return new ConstantBinding(null);
         return null;
      }

      if (!nestedBinding) {
         bindArgs.add(bindingDirection);
      }
      return RTypeUtil.invokeMethod(Bind.class, null, bindingType, bindArgs.toArray());
   }

   public Object initBinding(Class expectedType, ExecutionContext ctx) {
      Object val = evalBinding(expectedType, ctx);
      /*
      if (nestedBinding) {
         IBinding b = (IBinding) val;
         return b.initializeBinding();
      }
      */
      return val;
   }

   public void evalBindingArgs(List<Object> bindArgs, boolean isStatic, Class expectedType, ExecutionContext ctx) {
      throw new UnsupportedOperationException();
   }

   /** Subclasses override this to return the class name to use for the binding expression */
   public String getBindingTypeName() {
      System.err.println("*** Data binding illegal on expression: " + toDefinitionString());
      return null;
   }

   /**
    * From "a.b.c" to:
    *    Bind.bind(<enclosingObject or class>, boundFieldName,
    */
   public void transformBinding(ILanguageModel.RuntimeType runtime) {
      // srcObj: idTypes[0] = variableName: find enclosing type of that variable:
      //    if static, the enclosing class otherwise the type of the instance.
      //    if it is the class, we can just use TypeName.class here.
      //    if it is an instance variable:
      //       - if it is this type, use "this".
      //       - otherwise for a variable of an enclosing type use TypeName.this
      //    if it is an object reference:
      //       - create an identifier expression for the object (transform
      //    boundProps - array initializer for: all properties (variableName) or second anb subsequent for objects

      String bindingType = getBindingTypeName();
      if (bindingType == null)
         return;
      IdentifierExpression bind = IdentifierExpression.create("sc", "bind", "Bind", bindingType);
      SemanticNodeList<Expression> bindArgs = new SemanticNodeList<Expression>(5);

      BindDescriptor bd = getBindDescriptor();

      if (!nestedBinding) {  // Skip if its a hierarchical binding - don't need the dst - the binding will set our bindingParent

         if (bd.isStatic)
            bindArgs.add(ClassValueExpression.create(bd.type.typeName));
         else
            bindArgs.add(IdentifierExpression.createMethodCall(bd.thisObjectArguments, CTypeUtil.prefixPath(bd.thisTypePrefix, bd.thisObjectPrefix)));

         // Turn this into a static class reference where possible
         String nonThisPrefix = bd.thisObjectPrefix.equals("this") ? null : (bd.dstEnclType != null ? ModelUtil.getCompiledClassName(ModelUtil.getRootType(bd.dstEnclType)) : bd.thisObjectPrefix);

         // If the dst is bindable and not dynamic, take a shortcut and refer to its static "prop" mapping.  Otherwise, just look it up.
         // Make sure to use the "thisObjectPrefix" if this assignment has been moved.
         // If there is a prefix, we'll use that prefix to find the static variable.  It is tricky to find the type that hosted
         // this binding because of the case where we move the property assignment when compressing out the class for a simple object tag
         // If dstEnclType != null, we need to turn the reference into the weird _innerType_prop thing done in getPropertyName.  Not
         // yet coded.
         bindArgs.add(bd.getUseMapper() ?
               IdentifierExpression.createMethodCall(null, nonThisPrefix,
                     TransformUtil.getPropertyMappingName(nonThisPrefix == null ? bd.type : (TypeDeclaration) bd.dstEnclType, CTypeUtil.decapitalizePropertyName(bd.dstProp), true)) :
               StringLiteral.create(bd.dstProp));
      }

      // Snag the parent in case the transformBindingArgs moves around "this" as part of the value.
      ISemanticNode origParent = parentNode;

      transformBindingArgs(bindArgs, bd);

      Expression bindingExpr;
      if (!nestedBinding) {
         bindArgs.add(IdentifierExpression.create("sc","bind","BindingDirection", bindingDirection.toString()));

         bind.setProperty("arguments", bindArgs);

         if (bd.needsCast) {
            if (ModelUtil.isPrimitive(bd.dstPropType)) {
               String numberMethod = ModelUtil.getNumberPrefixFromType(bd.dstPropType) + "Value";
               SemanticNodeList<Expression> args = new SemanticNodeList<Expression>(1);
               args.add(bind);
               bindingExpr = IdentifierExpression.create("sc", "dyn", "DynUtil", numberMethod);
               bindingExpr.setProperty("arguments", args);
            }
            else if (ModelUtil.isANumber(bd.dstPropType)) {
               // ((Number) bind).int/long/float/double value - not doing this... I guess it did not support null?  In any case,
               // getNumberPrefixFromType does not work for java.lang.Number itself so be careful if we need this again.
               //String numberMethod = ModelUtil.getNumberPrefixFromType(bd.dstPropType) + "Value";
               CastExpression castExpr = CastExpression.create(ModelUtil.getTypeName(bd.dstPropType), bind);
               bindingExpr = castExpr;
            }
            else {
               // Note: for primitives createObjectType will return a Class but we already ruled those out above
               CastExpression castExpr = CastExpression.create((JavaType) JavaType.createObjectType(bd.dstPropType), bind);
               bindingExpr = castExpr;
            }
         }
         else {
            bindingExpr = bind;
         }
      }
      else {
         bind.setProperty("arguments", bindArgs);
         bindingExpr = bind;
      }
      if (origParent.replaceChild(this, bindingExpr) == -1)
         System.err.println("Unable to find bindingExpr in parent for replace");
      replacedByStatement = bindingExpr;
      bindingExpr.fromStatement = this;
      bindingExpr.transform(runtime);
   }

   /**
    * When we are transforming a nested expression, this method takes the set of chained expressions
    * and produces an expression to use as the IBinding[] boundParams argument to create the binding.
    */
   public Expression createBindingParameters(boolean includesProps, Expression... bindingParams) {
      LayeredSystem sys = getLayeredSystem();
      // IBinding: new IBinding[] { <bindingParams> }
      NewExpression paramBindings = new NewExpression();
      // Need to use Object to represent either IBeanMappoer or String when usePropertyMappers is false
      paramBindings.typeIdentifier = sys.usePropertyMappers || !includesProps ? "sc.bind.IBinding" : "Object";
      paramBindings.setProperty("arrayDimensions", new SemanticNodeList(0));
      ArrayInitializer paramInit = new ArrayInitializer();
      SemanticNodeList<Expression> initBindings = new SemanticNodeList<Expression>(bindingParams.length);
      for (Expression param:bindingParams)
         initBindings.add(param);

      paramInit.setProperty("initializers", initBindings);
      paramBindings.setProperty("arrayInitializer", paramInit);
      return paramBindings;
   }

   public IBinding[] evalBindingParameters(Class expectedType, ExecutionContext ctx, Expression... bindingParams) {
      IBinding[] result = new IBinding[bindingParams.length];
      for (int i = 0; i < bindingParams.length; i++)
         result[i] = (IBinding) bindingParams[i].evalBinding(expectedType, ctx);
      return result;
   }

   public IBinding[] evalBindingParametersWithThis(Object thisObj, Class expectedType, ExecutionContext ctx, Expression... bindingParams) {
      IBinding[] result = new IBinding[bindingParams.length+1];
      result[0] = Bind.constantP(thisObj);
      for (int i = 0; i < bindingParams.length; i++)
         result[i+1] = (IBinding) bindingParams[i].evalBinding(expectedType, ctx);
      return result;
   }


   public void transformBindingArgs(SemanticNodeList<Expression> bindArgs, BindDescriptor bd) {
      throw new UnsupportedOperationException();
   }

   public boolean isConstant() {
      return false;
   }

   /** This is like isConstant but does not "start" anything so you can use it during initialization */
   public boolean isDeclaredConstant() {
      return isConstant();
   }

   Expression createGetPropertyMappingCall(Object type, String identifier) {
      type = ModelUtil.getVariableTypeDeclaration(type);
      // Need the dynamic type of the actual runtime type declaration - but cannot use getCompiledClass at this stage
      boolean dynamicType = ModelUtil.isDynamicType(ModelUtil.getRuntimeTypeDeclaration(type));
      Object prop = ModelUtil.definesMember(type, identifier, MemberType.PropertyGetObj, null, null, false, true);
      if (prop == null) {
         System.err.println("*** Can't resolve property for get property mapping: " + identifier);
         //prop = ModelUtil.definesMember(type, identifier, MemberType.PropertyGetSet, null, null);
      }
      else if (ModelUtil.hasModifier(prop, "static")) {
         type = ModelUtil.getEnclosingType(prop);
      }
      IdentifierExpression propExpr;
      SemanticNodeList args = new SemanticNodeList(4);
      LayeredSystem sys = getLayeredSystem();
      // When converting to Javascript or other languages, the runtime might not benefit from PropertyMappers
      if (!getLayeredSystem().usePropertyMappers) {
         return StringLiteral.create(identifier);
      }
      // We cannot use reflection to access the type and the type itself cannot hold the static type components.  It might be a JDK class or something
      // for which we do not have source in the layered project.
      else if (sys.needsExtDynType(type)) {
         propExpr = IdentifierExpression.create(sys.buildInfo.getExternalDynTypeName(ModelUtil.getTypeName(type)), "resolvePropertyMapping");
      }
      // This is the case where we cannot use reflection on the type but we can use a static type component, because we are generating the type itself.
      else if (ModelUtil.needsDynType(type)) {
         TypeDeclaration td = (TypeDeclaration) ModelUtil.getRuntimeTypeDeclaration(type);
         TypeDeclaration outerType = td.getRootType();
         if (outerType == null)
            outerType = td;
         propExpr = IdentifierExpression.create(outerType.getFullTypeName(), "resolvePropertyMapping" + td.getDynamicStubParameters().getRuntimeInnerName());
      }
      else {
         // TODO: should we be using ModelUtil.resolveAnnotationReference here for the isConstant test?  Otherwise, we are not guaranteed
         // to get annotation layers, e.g. java.awt.Dimension to properly determine the @Constant annotation.
         String resolveName = ModelUtil.isArrayLength(prop) ?  "getArrayLengthPropertyMapping" : ModelUtil.isConstant(prop) ? "getConstantPropertyMapping" : "resolvePropertyMapping";
         propExpr = IdentifierExpression.create("sc", "dyn" , "DynUtil", resolveName);
         if (dynamicType)
            args.add(StringLiteral.create(ModelUtil.getRuntimeTypeName(type)));
         else {
            args.add(ClassValueExpression.create(ModelUtil.getCompiledClassName(type)));
         }
      }
      args.add(StringLiteral.create(identifier));
      propExpr.setProperty("arguments", args);
      return propExpr;
   }


   Expression createBindingArray(SemanticNodeList<Expression> elements, boolean includesProps) {
      LayeredSystem sys = getLayeredSystem();
      // IBinding: new IBinding[] { <arguments }
      NewExpression boundExpr = new NewExpression();
      ArrayInitializer boundProps = new ArrayInitializer();
      // When usePropertyMappers is false and this is potentially a property, it could be a String, not an IBeanMappoer so we need to use Object
      boundExpr.typeIdentifier = sys.usePropertyMappers || !includesProps ? "sc.bind.IBinding" : "Object";
      boundExpr.setProperty("arrayDimensions", new SemanticNodeList(0));
      // Move the method elements underneath this element.  Elements will get transformed into bind
      // calls when we transform the parent expression
      boundProps.setProperty("initializers", elements);
      boundExpr.setProperty("arrayInitializer", boundProps);
      return boundExpr;
   }

   IBinding[] evalBindingArray(Class elementType, SemanticNodeList<Expression> elements, ExecutionContext ctx) {
      int sz = elements.size();
      IBinding[] res = new IBinding[sz];
      for (int i = 0; i < sz; i++) {
         // Nested calls aways return IBinding right?
         res[i] = (IBinding) elements.get(i).evalBinding(elementType, ctx);
      }
      return res;
   }

   IdentifierExpression createGetMethodExpression(Expression methodClass,
                                                  Object typeObj, String methName, Object methObj, SemanticNodeList<Expression> arguments, boolean isRemote) {
      String typeName;
      String tailName;
      boolean needsClass;
      LayeredSystem sys = getLayeredSystem();
      if (sys.needsExtDynType(typeObj)) {
         typeName = ModelUtil.getTypeName(typeObj);
         tailName = "";
         needsClass = false;
      }
      // For compiled in types, we need to use the resolveMethod generated to ensure that DynType object gets created and registered before we try to access the method mapper from the DynType.
      else if (ModelUtil.needsDynType(typeObj)) {
         typeObj = ModelUtil.getRuntimeTypeDeclaration(typeObj);

         TypeDeclaration td = (TypeDeclaration) typeObj;

         TypeDeclaration outerType = td.getRootType();
         if (outerType == null)
            outerType = td;
         typeName = outerType.getFullTypeName();
         tailName = td.getDynamicStubParameters().getRuntimeInnerName();
         needsClass = false;
      }
      else {
         typeName = "sc.dyn.DynUtil";
         tailName = "";
         needsClass = true;
      }
      /** Check for the @Remote annotation - it can turn remoting on for this runtime even if the method is local. */
      if (!isRemote && isRemoteMethod(sys, methObj))
         isRemote = true;
      String remote = isRemote ? "Remote" : "";
      // resolveMethod, resolveStaticMethod, resolveRemoteMethod, etc are formed here
      String resolveName = ModelUtil.hasModifier(methObj, "static") ? ".resolve" + remote + "StaticMethod" : ".resolve" + remote + "Method";
      IdentifierExpression getMethod = IdentifierExpression.create(typeName + resolveName + tailName);
      SemanticNodeList<Expression> getMethodArgs = new SemanticNodeList<Expression>();
      if (needsClass)
         getMethodArgs.add(methodClass);
      if (sys.runtimeProcessor != null)
         methName = sys.runtimeProcessor.replaceMethodName(sys, methObj, methName);
      getMethodArgs.add(StringLiteral.create(methName));
      getMethodArgs.add(StringLiteral.create(ModelUtil.getTypeSignature(methObj)));
      getMethod.setProperty("arguments",getMethodArgs);
      return getMethod;
   }

   boolean isRemoteMethod(LayeredSystem sys, Object methObj) {
      Object remoteAnnot = ModelUtil.getAnnotation(methObj, "sc.obj.Remote");
      if (remoteAnnot != null) {
         String remoteRts = (String) ModelUtil.getAnnotationValue(remoteAnnot, "remoteRuntimes");
         String localRts = (String) ModelUtil.getAnnotationValue(remoteAnnot, "localRuntimes");
         boolean remote = true;
         String runtimeName = sys.getRuntimeName();
         boolean alreadyMatched = false;
         if (!StringUtil.isEmpty(remoteRts)) {
            remote = false;
            String[] remoteArr = StringUtil.split(remoteRts, ',');
            for (int i = 0; i < remoteArr.length; i++) {
               if (remoteArr[i].equals(runtimeName)) {
                  alreadyMatched = true;
                  remote = true;
                  break;
               }
            }
         }
         if (!StringUtil.isEmpty(localRts)) {
            String[] remoteArr = StringUtil.split(remoteRts, ',');
            for (int i = 0; i < remoteArr.length; i++) {
               if (remoteArr[i].equals(runtimeName)) {
                  if (alreadyMatched)
                     System.out.println("Warning: method " + methObj + " has conflicting definitions in remoteRuntime and localRuntime for: " + runtimeName + " - ignoring @Remote definition");
                  remote = false;
                  break;
               }
            }
         }
         return remote;
      }
      else
         return false;
   }

   Expression createChildMethodBinding(Object typeObj, String methodName, Object methObj, SemanticNodeList<Expression> arguments, boolean isRemote) {
      LayeredSystem sys = getLayeredSystem();
      if (sys.runtimeProcessor != null)
         methodName = sys.runtimeProcessor.replaceMethodName(sys, methObj, methodName);
      IdentifierExpression methBindExpr = IdentifierExpression.create("sc", "bind", "Bind", "methodP");
      SemanticNodeList<Expression> methBindArgs = new SemanticNodeList<Expression>(2);
      Expression methodClassExpr;
      if (ModelUtil.isDynamicType(typeObj))
         methodClassExpr = StringLiteral.create(ModelUtil.getRuntimeTypeName(typeObj));
      else
         methodClassExpr = ClassValueExpression.create(ModelUtil.getRuntimeTypeName(typeObj));
      IdentifierExpression getMethod = createGetMethodExpression(methodClassExpr, typeObj, methodName, methObj, arguments, isRemote);
      methBindArgs.add(getMethod);
      methBindArgs.add(createBindingArray(arguments, false));
      methBindExpr.setProperty("arguments", methBindArgs);
      return methBindExpr;
   }

   public boolean isReferenceInitializer() {
      return true;
   }

   public boolean isSimpleReference() {
      return false;
   }

   public void changeExpressionsThis(TypeDeclaration thisType, TypeDeclaration outerType, String varName) {
   }

   protected boolean inNamedPropertyMethod(String identifier) {
      MethodDefinition currentMethod;

      if ((currentMethod = getCurrentMethod()) != null &&
              currentMethod.propertyName != null && currentMethod.propertyName.equals(identifier)) {
         // If we are in a getX method and there's a field or object named "x" available, skip the getX conversion
         // One case where the field is not there is if we have converted an object tag to a getX method - without a field
         // When we are in the transformed model, need to check the transformed type to find a field we may have generated.
         TypeDeclaration enclType = getEnclosingType();
         Object member;
         if ((member = currentMethod.findMember(identifier, MemberType.FieldOrObjectTypeSet, this, getEnclosingType(), enclType != null && enclType.isTransformedType() ? new TypeContext(true) : null, true)) != null) {
            // There's a weird case here.  When the object tag overrides an existing property (e.g. FormView.ClassView.border which overrides the get/setBorder methods, we may resolve the object in the hidden body
            // and then wrongly assume we can refer to the field defined by that object in the getter.  Instead, we need to use the getBorder method.
            if (member instanceof BodyTypeDeclaration && ((BodyTypeDeclaration) member).objectSetProperty)
               return false;
            return true;
         }
         else {
            return false;
         }
      }

      return false;
   }

   protected boolean inPropertyMethodForDef(Object def) {
      MethodDefinition currentMethod;

      if (ModelUtil.isProperty(def)) {
         String pName = ModelUtil.getPropertyName(def);
         if ((currentMethod = getCurrentMethod()) != null &&
              currentMethod.propertyName != null && currentMethod.propertyName.equals(pName)) {
            Object fieldType = ModelUtil.getPropertyType(def);
            Object methType = ModelUtil.getPropertyType(currentMethod);
            // An indexed setter
            Object[] pTypes = currentMethod.getParameterTypes();
            if (pTypes != null && pTypes.length == 2) {
               if (ModelUtil.isArray(fieldType)) {
                  Object pType = ModelUtil.getArrayComponentType(fieldType);
                  if (pType != null && ModelUtil.sameTypes(pType, methType))
                     return true;
               }
               return false;
            }
            else if (!ModelUtil.sameTypes(fieldType, methType))
               return false;
            return true;
         }
         return false;
      }

      return false;
   }

   /** Returns true this expression is defined in a method which makes up a getX method for an object of the type being newed.
    * Because we do not generate classes for simple inner types, the newType passed has to be the runtime class used in the
    * new expression for this objects type.
    */
   protected boolean inObjectGetMethod(Object newType) {
      MethodDefinition currentMethod;

      if ((currentMethod = getCurrentMethod()) != null &&
              currentMethod.propertyName != null) {
         Object objectType = currentMethod.getReturnType();
         if (objectType != null)
            return ModelUtil.sameTypes(objectType, newType);
      }
      return false;
   }

   public boolean isReferenceValueObject() {
      return true;
   }

   public String getAbsoluteGenericTypeName(Object resultType, boolean includeDims) {
      return getGenericTypeName(resultType, includeDims);
   }

   public String getGenericTypeName(Object resultType, boolean includeDims) {
      Object type = getGenericType();
      return ModelUtil.getTypeName(type, includeDims, true);
   }

   public Object getGenericType() {
      return getTypeDeclaration();
   }

   // TODO: any other cases where Class<E> can be converted to a class type?
   public Object getGenericArgumentType() {
      return getGenericType();
   }


   /** To differentiate between x++; and foo(bar(), x++);  */
   public boolean canInsertStatementBefore(Expression from) {
      if (from == this)
         return super.canInsertStatementBefore(from);
      return false;
   }

   /** Only implemented for subclasses that can return needsSetMethod=true.  For "a.b.c" returns the type of "a.b" */
   public Object getParentReferenceType() {
      throw new UnsupportedOperationException();
   }

   /** Only implemented for subclasses that can return needsSetMethod=true.  For "a.b.c" returns "c" */
   public String getReferencePropertyName() {
      throw new UnsupportedOperationException();
   }

   /** Only implemented for subclasses that can return needsSetMethod=true.  For "a.b.c" returns an expr for "a.b" */
   public Expression getParentReferenceTypeExpression() {
      throw new UnsupportedOperationException();
   }


   public String toString() {
      /*
      if (debugDisablePrettyToString || !started)
         return toModelString();
      else
         return toLocationString(JavaLanguage.INSTANCE.optExpression, true, true, true);
      */
      return toSafeLanguageString();
   }

   // Expressions do not have names really so just display the toString value in place of the name.
   public String toDefinitionString() {
      return toLanguageString();
   }

   public ExecResult exec(ExecutionContext ctx) {
      eval(null, ctx);
      return ExecResult.Next;
   }


  public boolean getLHSAssignmentTyped() {
     return false;
  }

  public String toLanguageString() {
     return toLanguageString(JavaLanguage.getJavaLanguage().expression);
  }

  public abstract boolean isStaticTarget();

   /*
   public Object findMember(String name, EnumSet<MemberType> mtype, Object fromChild, Object refType, TypeContext ctx) {
      if (movedToDefinition != null)
         return movedToDefinition.findMember(name, mtype, fromChild, refType, ctx);
      return super.findMember(name, mtype, fromChild, refType, ctx);
   }
   */

   public void addInitStatements(List<Statement> res, InitStatementsMode mode) {
      if (mode != InitStatementsMode.PreInit)
         res.add(this);
   }

   public String getUserVisibleName() {
      return "<" + getClass().getName() + ">";
   }

   public boolean canMakeBindable() {
      return true;
   }

   /** Called when we are moving an expression from the left to the right hand side. */
   public void changeToRHS() {

   }

   /** Does this expression need a paren wrapper to be combined with an artithmetic operation */
   public boolean needsParenWrapper() {
      return false;
   }

   // TODO: any case where we embed glue state,ents inside of an expression to define a new tag - this needs to collect the sub-elements so we define all of the sub-tag-objects we need
   public void addChildBodyStatements(List<Object> res) {
   }

   public Expression deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      Expression res = (Expression) super.deepCopy(options, oldNewMap);

      if ((options & CopyInitLevels) != 0) {
         res.bindingDirection = bindingDirection;
         res.bindingStatement = bindingStatement;
         res.nestedBinding = nestedBinding;
         res.replacedByStatement = replacedByStatement;
      }
      return res;
   }

   public String toGenerateString() {
      throw new UnsupportedOperationException();
   }

   public String toSafeLanguageString() {
      if (parseNode == null || parseNodeInvalid)
         return toGenerateString();
      return toLanguageString();
   }

   public CharSequence formatExprToJS() {
      // Using blockStatements here to include VariableStatement.  Since it is a choice of items and an array it expects an array value so just wrap it here.
      String res = toLanguageString(JSLanguage.getJSLanguage().expression);
      if (res.contains("Generation error"))
         System.out.println("*** Generation error for statmeent: " + this);
      return res;
   }

   public ISrcStatement findFromStatement(ISrcStatement st) {
      ISrcStatement res = super.findFromStatement(st);
      if (res != null)
         return res;
      if (bindingStatement != null) {
         if (bindingStatement.findFromStatement(st) != null)
            return this;
      }
      return null;
   }

   public void setInferredType(Object type) {

   }
}
