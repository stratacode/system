/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ILanguageModel;
import sc.lang.ISemanticNode;
import sc.lang.JavaLanguage;
import sc.lang.SemanticNodeList;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.parser.IString;
import sc.parser.PString;
import sc.parser.ParseUtil;
import sc.parser.StringToken;
import sc.type.DynType;
import sc.util.StringUtil;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.WildcardType;
import java.util.*;

/** Java 8 lambda expression.  To run these in JS, and in dynamic java, and to validate the code in general we convert them to anonymous classes -
 * i.e. new InferredType<lambda-params>(...) {
 *    inferredMethodReturn inferredMethod(inferredMethodParams) {
 *       lambdaBody
 *       return lambdaReturnType
 *    }
 * } */
public abstract class BaseLambdaExpression extends Expression {
   /** These are the parameters to the lambda expression - they should match the inferredTypeMethod */
   transient Parameter parameters; // Either a copy of lambdaParams when they are full specified or Parameter types we create to mirror the method that will be generated
   /** This is the "functional interface" (i.e. one method which is unimplemented) we are implementing with this lambda expression. */
   transient Object inferredType = null;
   /** Set to false when we are matching a method with a lambda expression as an argument to the method call.  It is set to true when we finally have the chosen inferredType for the expression */
   transient boolean inferredFinal = false;
   /** The new expression which we generate to implement the lambda expression */
   transient NewExpression newExpr = null;
   transient boolean needsStart = false;
   /** This is the method on the interface - the inferredType - that this lambda expression will supply a new implementation of  */
   transient Object inferredTypeMethod;
   /** This is the implementation we generate of the inferredTypeMethod */
   transient MethodDefinition lambdaMethod;

   abstract Object getLambdaParameters(Object methObj, ITypeParamContext ctx);
   abstract Statement getLambdaBody(Object methObj);

   abstract String getExprType();

   transient TreeMap<String,Object> inferredTypeParams = null;

   void updateInferredTypeMethod(Object methObj) {
      inferredTypeMethod = methObj;
   }

   protected boolean referenceMethodMatches(Object type, Object ifaceMeth, LambdaMatchContext ctx) {
      return true;
   }

   public boolean lambdaParametersMatch(Object type, boolean varArgs, LambdaMatchContext ctx) {
      if (varArgs && ModelUtil.isArray(type)) {
         Object compType = ModelUtil.getArrayComponentType(type);
         if (compType != null)
            type = compType;
      }

      Object ifaceMeth = getInterfaceMethod(type, false);
      if (ifaceMeth == null) {
         // We may not not have an inferredType here to bind yet.  If we fail the parameter match, we'll never assign a method
         // for the match.
         if (ModelUtil.isTypeVariable(type))
            return true;
         return false;
      }

      Object lambdaParams = getLambdaParameters(ifaceMeth, null);

      if (!parametersMatch(lambdaParams, ifaceMeth, getLayeredSystem())) {
         return false;
      }

      if (!referenceMethodMatches(type, ifaceMeth, ctx))
         return false;

      return true;
   }

   Object getInterfaceMethod(Object inferredType, boolean errors) {
      if (!ModelUtil.isInterface(inferredType)) {
         if (errors)
            displayError("Type for lambda expression: " + ModelUtil.getTypeName(inferredType) + " is not an interface for expression: ");
         return null;
      }

      Object[] methods = ModelUtil.getAllMethods(inferredType, null, false, false, false);
      Object ifaceMeth = null;
      if (methods == null) {
         if (errors)
            displayError("Type for lambda expression has no methods: ");
         return null;
      }
      else if (methods.length != 1) {
         for (Object meth:methods) {
            if (ModelUtil.isDefaultMethod(meth))
               continue;
            if (ModelUtil.hasModifier(meth, "private"))
               continue;
            if (ModelUtil.hasModifier(meth, "static"))
               continue;
            if (ifaceMeth != null) {
               // Pick the later method?  Does it matter which we pick here?
               if (ModelUtil.overridesMethod(ifaceMeth, meth))
                  ifaceMeth = meth;
               else {
                  if (ModelUtil.overridesMethodInType(Object.class, ifaceMeth))
                     ifaceMeth = meth;
                  else if (!ModelUtil.overridesMethodInType(Object.class, meth)) {
                     if (errors)
                        displayError("Type for lambda expression must have only one method.  Interface " + ModelUtil.getTypeName(inferredType) + " has both: " + ModelUtil.toDeclarationString(ifaceMeth) + " and " + ModelUtil.toDeclarationString(meth) + " for: ");
                     return null;
                  }
               }
            }
            else
               ifaceMeth = meth;
         }
         if (ifaceMeth == null) {
            if (errors) {
               if (methods.length == 0)
                  displayError("Lambda expression's inferred type: " + inferredType.toString() + " does not have any methods for: ");
               else
                  displayError("Lambda expression's inferred type: " + inferredType.toString() + " does not have suitable methods: " + StringUtil.arrayToString(methods) + " for: ");
            }
         }
      }
      else // TODO need to validate that this is not abstract
         ifaceMeth = methods[0];

      return ifaceMeth;
   }

   void initNewExpression(boolean inferredFinal) {
      if (inferredType == null) {
         // TODO: will this happen?
         displayError("No inferredType for lambda expression: ");
         return;
      }

      if (newExpr != null)
         System.err.println("*** reinitializing new expression!");

      Object ifaceMeth = getInterfaceMethod(inferredType, inferredFinal);
      if (ifaceMeth == null) {
         if (inferredFinal)
            displayError("No interface method for lambda expression using inferred type: " + ModelUtil.getTypeName(inferredType) + " for: ");
         return;
      }

      updateInferredTypeMethod(ifaceMeth);

      LayeredSystem sys = getLayeredSystem();
      if (ModelUtil.isParameterizedMethod(ifaceMeth) && ModelUtil.hasTypeParameters(inferredType) && !(ifaceMeth instanceof ParamTypedMethod)) {
         ifaceMeth = new ParamTypedMethod(sys, ifaceMeth, (ITypeParamContext) inferredType, getEnclosingType(), null, null, null);
      }
      Object ifaceMethReturnType = ModelUtil.getReturnType(ifaceMeth, false);
      // If we can tell the return type of the lambda expression now, define any type parameters we learn from that type.
      Object newMethReturnType = getNewMethodReturnType();
      if (newMethReturnType != null) {
         updateReturnTypeParameters(ifaceMeth, ifaceMethReturnType, newMethReturnType);
      }
      // For method references, there's a mapping between the referenceMethod and the ifaceMeth so we can use those
      // parameter types to define type parameters for the inferredType.  Then use those to accurately determine the
      // parameter types.
      updateMethodTypeParameters(ifaceMeth);

      ITypeParamContext typeCtx = inferredType instanceof ITypeParamContext ? (ITypeParamContext) inferredType : null;

      Object lambdaParams = getLambdaParameters(ifaceMeth, typeCtx);

      newExpr = new NewExpression();
      newExpr.lambdaExpression = true;
      newExpr.setProperty("arguments", new SemanticNodeList());
      newExpr.setProperty("typeIdentifier", ModelUtil.getTypeName(inferredType));
      SemanticNodeList<Statement> classBody = new SemanticNodeList<Statement>();

      MethodDefinition newMeth = new MethodDefinition();
      newMeth.name = ModelUtil.getMethodName(ifaceMeth);

      ITypeDeclaration enclType = getEnclosingType();

      Parameter params = createLambdaParams(sys, lambdaParams, ifaceMeth, typeCtx, enclType);
      // The number of lambda parameters does not match
      if (params == INVALID_LAMBDA_PARAMS) {
         newExpr = null;
         return;
      }
      newMeth.setProperty("parameters", parameters = params);
      // resolve was true but it could lead to us binding to java.lang.Object too soon in some cases
      Object lambdaMethodReturnType = ModelUtil.getParameterizedReturnType(ifaceMeth, null, false);
      newMeth.setProperty("type", JavaType.createFromParamType(sys, lambdaMethodReturnType, typeCtx, enclType));
      lambdaMethod = newMeth;

      // Doing this after we have defined the parameters here so we can resolve the references
      if (!parametersMatch(lambdaParams, ifaceMeth, sys)) {
         if (!inferredFinal)
            return;
         displayError("Mismatch between lambda method: " + ModelUtil.getMethodName(ifaceMeth) + " and " + getExprType() + " parameters: " + getParamString(lambdaParams) + " and: " + ModelUtil.toDeclarationString(ifaceMeth));
         boolean x = parametersMatch(lambdaParams, ifaceMeth, sys);
      }

      // Always public since they are in an interface
      SemanticNodeList<Object> mods = new SemanticNodeList<Object>();
      mods.add("public");
      newMeth.setProperty("modifiers", mods);

      Statement lambdaBody = getLambdaBody(ifaceMeth);

      BlockStatement methodBody;
      ReturnStatement returnBodyExpr = null;
      Expression bodyExpr = null;

      if (lambdaBody instanceof BlockStatement) {
         methodBody = (BlockStatement) lambdaBody.deepCopy(ISemanticNode.CopyNormal, null);
      }
      else if (lambdaBody instanceof Expression) {
         methodBody = new BlockStatement();
         if (ModelUtil.typeIsVoid(ModelUtil.getReturnType(ifaceMeth, true))) {
            // The method is void and so does not return anything.  Just use <expr>
            methodBody.addStatementAt(0, ((Expression) lambdaBody).deepCopy(ISemanticNode.CopyNormal, null));
         }
         else {
            bodyExpr = (Expression) lambdaBody.deepCopy(ISemanticNode.CopyNormal, null);
            returnBodyExpr = ReturnStatement.create(bodyExpr);
            // Do the return <expr> ;
            methodBody.addStatementAt(0, returnBodyExpr);
         }
      }
      else {
         displayTypeError("Invalid method body type: ");
         return;
      }
      newMeth.setProperty("body", methodBody);

      classBody.add(newMeth);
      newExpr.setProperty("classBody", classBody);
      newExpr.parentNode = parentNode;

      // Weird case here - it's minBy(t -> t, naturalOrder()) in Agg.java of jool.  First param is a Function<T, R> - we know 'R' from the naturalOrder() return type
      // but have no way to know T except that t -> t - basically could allow us to change the type of 't' to R by back-propagating the type.
      // Should we go back and rewrite the type of the parameter 't'?   Based on IntelliJ's lack of knowledge of the type of 't' expedience of the fix, I'm inserting a cast
      if (bodyExpr != null && bodyExpr instanceof IdentifierExpression) {
         IdentifierExpression ibodyExpr = (IdentifierExpression) bodyExpr;
         if (ibodyExpr.identifiers != null && ibodyExpr.identifiers.size() == 1 && ibodyExpr.arguments == null) {
            Object bodyExprType = bodyExpr.getGenericType();
            if (bodyExprType != null && !ModelUtil.isAssignableFrom(lambdaMethodReturnType, bodyExprType))
               returnBodyExpr.setProperty("expression", CastExpression.create(
                           JavaType.createFromParamType(sys, lambdaMethodReturnType, typeCtx, null),
                           returnBodyExpr.expression));
         }
      }

      // In the most general case, the type parameters for type of this expression are determined from the inferred return
      // type of the method.  That means searching through all return's and building up the common superclass.
      // We may have already done this earlier - if the return type is known earlier.  If those type parameters are used in the parameters to the method,
      // we can't start the method with those types defined properly to get the return type.
      if (ModelUtil.isParameterizedType(ifaceMethReturnType) && inferredType instanceof ParamTypeDeclaration && ((ParamTypeDeclaration) inferredType).hasUnboundParameters()) {
         // Start this now so we can get it's inferred return type
         ParseUtil.initAndStartComponent(newMeth);
         newMethReturnType = newMeth.getInferredReturnType(false);
         if (newMethReturnType == MethodDefinition.UNRESOLVED_INFERRED_TYPE)
            newMethReturnType = null;
         if (newMethReturnType != null) {
            updateReturnTypeParameters(ifaceMeth, ifaceMethReturnType, newMethReturnType);
         }
      }

      // Setting these after we've added any type parameters whose type is inferred form the method's return type
      Object paramReturnType = ModelUtil.getParameterizedReturnType(ifaceMeth, null, false);
      if (ModelUtil.isTypeVariable(paramReturnType)) {
         if (typeCtx != null) {
            paramReturnType = typeCtx.getTypeForVariable(paramReturnType, true);
         }
         if (ModelUtil.isTypeVariable(paramReturnType))
            paramReturnType = ModelUtil.getTypeParameterDefault(paramReturnType);
      }
      else if (paramReturnType instanceof ExtendsType.LowerBoundsTypeDeclaration)
         paramReturnType = ((ExtendsType.LowerBoundsTypeDeclaration) paramReturnType).getBaseType();
      newMeth.setProperty("type", JavaType.createFromParamType(sys, paramReturnType, typeCtx, enclType));
      if (ModelUtil.hasTypeParameters(inferredType)) {
         SemanticNodeList<JavaType> typeParams = new SemanticNodeList<JavaType>();
         int numParams = ModelUtil.getNumTypeParameters(inferredType);
         for (int i = 0; i < numParams; i++) {
            Object typeParam = ModelUtil.getTypeParameter(inferredType, i);
            if (typeParam instanceof JavaType)
               typeParams.add((JavaType) ((JavaType) typeParam).deepCopy(ISemanticNode.CopyNormal, null));
            else if (typeParam instanceof WildcardType || typeParam instanceof ParameterizedType || typeParam instanceof ExtendsType.LowerBoundsTypeDeclaration || ModelUtil.isTypeVariable(typeParam) || typeParam instanceof ITypeDeclaration || typeParam instanceof Class) {
               typeParams.add((JavaType.createFromParamType(sys, typeParam, typeCtx, null)));
            }
            else
               typeParams.add(ClassType.create(ModelUtil.getTypeName(typeParam)));
         }
         newExpr.setProperty("typeArguments", typeParams);
      }

      propagateInferredType(inferredType, paramReturnType, inferredFinal);
   }

   void addTypeParameterMapping(Object ifaceMeth, Object ifaceMethType, Object newMethType) {
      if (!(inferredType instanceof ParamTypeDeclaration)) {
         return;
      }
      ParamTypeDeclaration paramType = (ParamTypeDeclaration) inferredType;
      if (ModelUtil.isTypeVariable(ifaceMethType)) {
         String typeParamName = ModelUtil.getTypeParameterName(ifaceMethType);

         // The owner type of ifaceMeth may be a subclass of paramType and so we need to map the parameters.

         Object methType = ModelUtil.getEnclosingType(ifaceMeth);

         if (!ModelUtil.sameTypes(paramType, methType)) {
            // We know that typeParamName is a type parameter for methType, but we need to convert to the type parameter for paramType
            Object typeParamRes = ModelUtil.resolveTypeParameter(methType, paramType, ifaceMethType);
            typeParamName = ModelUtil.getTypeParameterName(typeParamRes);
         }

         if (inferredTypeParams == null)
            inferredTypeParams = new TreeMap<String,Object>();
         Object oldVal = inferredTypeParams.get(typeParamName);
         if (oldVal != null)
            newMethType = ModelUtil.refineType(getLayeredSystem(), getEnclosingType(), oldVal, newMethType);
         inferredTypeParams.put(typeParamName, newMethType);

         updateInferredType(paramType);
      } else if (ifaceMethType instanceof ExtendsType) {
         System.out.println("*** Unknown extends reference");
      } //else if (ModelUtil.isParameterizedType(ifaceMethType))
       //  System.out.println("*** Unknown parameterized type"); // TODO: do we need to do extraction and setting of type parameters here
   }

   private void updateInferredType(Object inferredType) {
      if (inferredTypeParams != null && inferredType instanceof ParamTypeDeclaration) {
         ParamTypeDeclaration paramType = (ParamTypeDeclaration) inferredType;

         for (Map.Entry<String,Object> ent:inferredTypeParams.entrySet()) {
            paramType.setTypeParameter(ent.getKey(), ent.getValue());
         }
      }
   }

   private void updateReturnTypeParameters(Object ifaceMeth, Object ifaceMethReturnType, Object newMethReturnType) {
      addTypeParameterMapping(ifaceMeth, ifaceMethReturnType, newMethReturnType);
   }

   // Overridden in MethodReference and LambdaExpression to add any type parameter mappings we get from the type
   // information in the method's parameter types.
   abstract void updateMethodTypeParameters(Object ifaceMeth);

   protected void propagateInferredType(Object type, Object methReturnType, boolean inferredfinal) {
      if (newExpr != null)
         newExpr.setInferredType(inferredType, inferredFinal);
   }

   public Object getNewMethodReturnType() {
      return null;
   }

   private static boolean parametersMatch(Object lambdaParams, Object meth, LayeredSystem sys) {
      Object[] params = ModelUtil.getGenericParameterTypes(meth, true);
      // One parameter special case for lambda's
      if (PString.isString(lambdaParams)) {
         return params != null && params.length == 1;
      }
      // Lambda sends in the list of names - the length only needs to match
      else if (lambdaParams instanceof SemanticNodeList) {
         return ((SemanticNodeList) lambdaParams).size() == (params == null ? 0 : params.length);
      }
      else if (lambdaParams instanceof Parameter) {
         Object[] paramTypes = ((Parameter) lambdaParams).getParameterTypes();
         // Need to pass true here for SeqTest - Seq.unfold(0, (Integer i -> ...)   The lamda param there needs to match even though we do not have
         // a parameter type.  Or should we be binding "U" because of the Integer i somehow?
         return ModelUtil.parametersMatch(paramTypes, params, true, sys);
      }
      // () turns into null?
      else if (lambdaParams == null) {
         return params == null || params.length == 0;
      }
      else {
         throw new UnsupportedOperationException("Lambda expression has invalid parameter type");
      }
   }

   String getParamString(Object lambdaParams) {
      return (lambdaParams == null ? "()" : (lambdaParams instanceof IString ? lambdaParams :
              ParseUtil.toLanguageString(JavaLanguage.getJavaLanguage().lambdaParameters, (ISemanticNode) lambdaParams)).toString());
   }

   public MethodDefinition getLambdaMethod() {
      if (lambdaMethod == null)
         initNewExpression(false);
      return lambdaMethod;
   }

   public void start() {
      if (inferredType != null)
         super.start();
      else
         needsStart = true;
   }

   public void validate() {
      if (inferredType == null)
         displayTypeError("No inferredType for lambda expression: ");
      if (needsStart) {
         needsStart = false;
         if (!isStarted())
            super.start();
         else
            startLambdaBody();
      }
      super.validate();
   }

   private final static Parameter INVALID_LAMBDA_PARAMS = new Parameter();

   private static Parameter createLambdaParams(LayeredSystem sys, Object params, Object meth, ITypeParamContext ctx, ITypeDeclaration definedInType) {
      ArrayList<String> names = new ArrayList<String>();

      if (PString.isString(params)) {
         names.add(params.toString());
      }
      else if (params instanceof SemanticNodeList) {
         SemanticNodeList paramNameArray = (SemanticNodeList) params;
         for (Object paramName:paramNameArray)
            names.add(paramName.toString());
      }
      else if (params instanceof Parameter) {
         return (Parameter) ((Parameter) params).deepCopy(ISemanticNode.CopyNormal, null);
      }
      // Empty parameters
      else if (params == null) {
         return null;
      }
      else {
         throw new UnsupportedOperationException();
      }

      Object[] methTypes = ModelUtil.getGenericParameterTypes(meth, true);
      Object[] resMethTypes = methTypes == null ? null : new Object[methTypes.length];
      if (methTypes != null) {
         for (int i = 0; i < methTypes.length; i++) {
            resMethTypes[i] = methTypes[i];
            if (resMethTypes[i] instanceof ExtendsType.LowerBoundsTypeDeclaration)
               resMethTypes[i] = ((ExtendsType.LowerBoundsTypeDeclaration) resMethTypes[i]).getBaseType();
            //if (ModelUtil.isTypeVariable(resMethTypes[i])) {
            //   resMethTypes[i] = ModelUtil.getTypeParameterDefault(resMethTypes[i]);
            //}
         }
      }
      int resMethLen = resMethTypes == null ? 0 : resMethTypes.length;
      if (resMethLen != names.size())
         return INVALID_LAMBDA_PARAMS;
      return Parameter.create(sys, resMethTypes, names.toArray(new String[names.size()]), ctx, definedInType);
   }

   @Override
   public boolean isStaticTarget() {
      // TODO: is this right?
      return false;
   }

   public Object getTypeDeclaration() {
      if (inferredType == null)
         return new LambdaInferredType(this);
      if (newExpr == null) {
         JavaModel model = null;
         boolean oldTypeErrors = false;
         // if we are initializing this new expression as part of a "non-final inferred type", need to ignore any errors
         // since this is just happening to validate if this lambda expression matches the method and the inferred type supplied.
         if (!inferredFinal) {
            model = getJavaModel();
            oldTypeErrors = model.disableTypeErrors;
            model.setDisableTypeErrors(true);
         }
         try {
            initNewExpression(false);
         }
         finally {
            if (model != null)
               model.setDisableTypeErrors(oldTypeErrors);
         }
      }
      // This lambda expression is just not valid
      if (newExpr == null)
         return new LambdaInvalidType(this);
      return inferredType;
   }

   public Object eval(Class expectedType, ExecutionContext ctx) {
      if (newExpr == null)
         initNewExpression(false);
      if (newExpr != null)
         return newExpr.eval(expectedType, ctx);
      throw new IllegalArgumentException("eval of undefined lambda expression");
   }

   public boolean transform(ILanguageModel.RuntimeType runtime) {
      if (getLayeredSystem().getNeedsAnonymousConversion() || bindingStatement != null) {
         if (newExpr == null)
             initNewExpression(false);
         parentNode.replaceChild(this, newExpr);
         newExpr.transform(runtime);
         return true;
      }
      return super.transform(runtime);
   }

   public Statement transformToJS() {
      if (newExpr != null)
         return newExpr;
      // Not reached
      throw new UnsupportedOperationException("Invalid transform of lambda expression");
   }

   public void transformBinding(ILanguageModel.RuntimeType runtime) {
      if (newExpr == null)
         throw new UnsupportedOperationException("lambda expression in binding mishap!");
      newExpr.transformBinding(runtime);
   }

   public boolean setInferredType(Object parentType, boolean finalType) {
      // Convert between the ParameterizedType and ParamTypeDeclaration
      if (parentType instanceof ParameterizedType)
         parentType = ModelUtil.getTypeDeclFromType(parentType, parentType, false, getLayeredSystem(), false, getEnclosingType());

      if (inferredType != null) {
         if (ModelUtil.isAssignableFrom(parentType, inferredType)) {
            // If these are exactly the same, no need to do anything except update the type parameters in the new parentType
            if (ModelUtil.isAssignableFrom(inferredType, parentType)) {
               updateInferredType(parentType);
               // The last time we set newExpr it was during a parametersMatch - not final so reset the new expr so we propagate
               // the final inferred type
               if (!inferredFinal && finalType) {
                  needsStart = true;
                  newExpr = null;
               }
            }
            else {
               needsStart = true;
               newExpr = null;
            }
         }
         else {
            // Need to set the type parameters we set while building the new expression if we are not going to rebuild it
            //updateInferredType(parentType);
            needsStart = true;
            newExpr = null;
         }
      }
      inferredType = parentType;
      inferredFinal = finalType;
      if (needsStart) {
         needsStart = false;
         if (newExpr == null) {
            JavaModel thisModel = getJavaModel();
            boolean oldDisableTypeErrors = thisModel.disableTypeErrors;
            try {
               if (!inferredFinal)
                  thisModel.setDisableTypeErrors(true);
               initNewExpression(inferredFinal);
            }
            finally {
               thisModel.setDisableTypeErrors(oldDisableTypeErrors);
            }
         }
         if (inferredFinal) {
            if (!isStarted())
               super.start();
            else
               startLambdaBody();
         }
      }

      // Did we modify type parameters in the inferredType?
      return inferredTypeParams != null && inferredTypeParams.size() > 0;
   }

   public void clearInferredType() {
      if (inferredFinal)
         return;
      inferredType = null;
      needsStart = true;
      newExpr = null;
      lambdaMethod = null;
      parameters = null;
      inferredTypeParams = null;
   }

   class LambdaMatchContext {
      // The return type of the method in the interface we are implementing
      Object ifaceReturnType;
      boolean ifaceMethIsVoid;
      // The return type of the lambdaBody
      Object inferredReturnType;
      boolean lambdaBodyIsVoid;
   }

   public Object pickMoreSpecificMethod(Object meth1, Object argType1, boolean varArg1, Object meth2, Object argType2, boolean varArg2) {
      boolean c2Interface = ModelUtil.isInterface(argType2) || (varArg2 && ModelUtil.isInterface(ModelUtil.getArrayComponentType(argType2)));
      LambdaMatchContext c2Ctx = new LambdaMatchContext();
      if (c2Interface && !lambdaParametersMatch(argType2, varArg2, c2Ctx))
         c2Interface = false;
      boolean c1Interface = ModelUtil.isInterface(argType1) || (varArg1 && ModelUtil.isInterface(ModelUtil.getArrayComponentType(argType1)));
      LambdaMatchContext c1Ctx = new LambdaMatchContext();
      if (c1Interface && !lambdaParametersMatch(argType1, varArg1, c1Ctx))
         c1Interface = false;
      if (!c1Interface) {
         if (c2Interface)
            return meth2;
      }
      else if (!c2Interface)
         return meth1;
      // The case here is binding run(Runnable) versus V run(Supplier<V>) with a method returns a value, when we are calling with () -> methReturnsBoolean()
      // It seems like the fact that one of the run methods has a return value here pushes the choice towards V run(Supplier<V>)
      boolean c1ReturnMismatch = c1Ctx.ifaceMethIsVoid && !c1Ctx.lambdaBodyIsVoid;
      boolean c2ReturnMismatch = c2Ctx.ifaceMethIsVoid && !c2Ctx.lambdaBodyIsVoid;
      if (c1ReturnMismatch && !c2ReturnMismatch)
         return meth2;
      if (c2ReturnMismatch && !c1ReturnMismatch)
         return meth1;
      return null;
   }

   public BaseLambdaExpression deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      // TODO: should we copy the inferred type here?
      return (BaseLambdaExpression) super.deepCopy(options, oldNewMap);
   }

   void startLambdaBody() {
      if (newExpr != null)
         ParseUtil.realInitAndStartComponent(newExpr);
   }

   public static class LambdaInferredType implements ITypeDeclaration {
      BaseLambdaExpression rootExpr;

      public LambdaInferredType(BaseLambdaExpression baseLambda) {
         rootExpr = baseLambda;
      }

      @Override
      public boolean isAssignableFrom(ITypeDeclaration other, boolean assignmentSemantics) {
         return false;
      }

      @Override
      public boolean isAssignableTo(ITypeDeclaration other) {
         return false;
      }

      @Override
      public boolean isAssignableFromClass(Class other) {
         return false;
      }

      @Override
      public String getTypeName() {
         return "Object";
      }

      @Override
      public String getFullTypeName(boolean includeDims, boolean includeTypeParams) {
         return "java.lang.Object";
      }

      @Override
      public String getFullTypeName() {
         return "java.lang.Object";
      }

      @Override
      public String getFullBaseTypeName() {
         return "java.lang.Object";
      }

      @Override
      public String getInnerTypeName() {
         return null;
      }

      @Override
      public Class getCompiledClass() {
         return null;
      }

      @Override
      public String getCompiledClassName() {
         return null;
      }

      @Override
      public String getCompiledTypeName() {
         return null;
      }

      @Override
      public Object getRuntimeType() {
         return null;
      }

      @Override
      public boolean isDynamicType() {
         return false;
      }

      @Override
      public boolean isDynamicStub(boolean includeExtends) {
         return false;
      }

      @Override
      public Object definesMethod(String name, List<?> parametersOrExpressions, ITypeParamContext ctx, Object refType, boolean isTransformed, boolean staticOnly, Object inferredType, List<JavaType> methodTypeArgs) {
         return null;
      }

      @Override
      public Object declaresConstructor(List<?> parametersOrExpressions, ITypeParamContext ctx) {
         return null;
      }

      @Override
      public Object definesConstructor(List<?> parametersOrExpressions, ITypeParamContext ctx, boolean isTransformed) {
         return null;
      }

      @Override
      public Object definesMember(String name, EnumSet<MemberType> type, Object refType, TypeContext ctx) {
         return null;
      }

      @Override
      public Object definesMember(String name, EnumSet<MemberType> type, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
         return null;
      }

      @Override
      public Object getInnerType(String name, TypeContext ctx) {
         return null;
      }

      @Override
      public boolean implementsType(String otherTypeName, boolean assignment, boolean allowUnbound) {
         // At this stage, we do not know if we implement the type.  Returning true, because we need to match essentially any type during
         // the type checking process, until the inferred type is set and we can really determine our type.  We might need to push some
         // operations to the 'validate' stage where this inferredType will definiteily have been set if they depend on an accurate result here.
         return true;
      }

      @Override
      public Object getInheritedAnnotation(String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve) {
         return null;
      }

      @Override
      public ArrayList<Object> getAllInheritedAnnotations(String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve) {
         return null;
      }

      @Override
      public Object getDerivedTypeDeclaration() {
         return null;
      }

      @Override
      public Object getExtendsTypeDeclaration() {
         return null;
      }

      @Override
      public Object getExtendsType() {
         return null;
      }

      @Override
      public List<?> getImplementsTypes() {
         return null;
      }

      @Override
      public List<Object> getAllMethods(String modifier, boolean hasModifier, boolean isDyn, boolean overridesComp) {
         return null;
      }

      @Override
      public List<Object> getMethods(String methodName, String modifier, boolean includeExtends) {
         return null;
      }

      @Override
      public Object getConstructorFromSignature(String sig) {
         return null;
      }

      @Override
      public Object getMethodFromSignature(String methodName, String signature, boolean resolveLayer) {
         return null;
      }

      @Override
      public List<Object> getAllProperties(String modifier, boolean includeAssigns) {
         return null;
      }

      @Override
      public List<Object> getAllFields(String modifier, boolean hasModifier, boolean dynamicOnly, boolean includeObjs, boolean includeAssigns, boolean includeModified) {
         return null;
      }

      @Override
      public List<Object> getAllInnerTypes(String modifier, boolean thisClassOnly) {
         return null;
      }

      @Override
      public DeclarationType getDeclarationType() {
         return null;
      }

      @Override
      public Object getClass(String className, boolean useImports) {
         return null;
      }

      @Override
      public Object findTypeDeclaration(String typeName, boolean addExternalReference) {
         return null;
      }

      @Override
      public JavaModel getJavaModel() {
         return rootExpr.getJavaModel();
      }

      @Override
      public boolean isLayerType() {
         return false;
      }

      @Override
      public Layer getLayer() {
         return rootExpr.getJavaModel().getLayer();
      }

      @Override
      public LayeredSystem getLayeredSystem() {
         return rootExpr.getLayeredSystem();
      }

      @Override
      public List<?> getClassTypeParameters() {
         return null;
      }

      @Override
      public Object[] getConstructors(Object refType) {
         return new Object[0];
      }

      @Override
      public boolean isComponentType() {
         return false;
      }

      @Override
      public DynType getPropertyCache() {
         return null;
      }

      @Override
      public boolean isEnumeratedType() {
         return false;
      }

      @Override
      public Object getEnumConstant(String nextName) {
         return null;
      }

      @Override
      public boolean isCompiledProperty(String name, boolean fieldMode, boolean interfaceMode) {
         return false;
      }

      @Override
      public List<JavaType> getCompiledTypeArgs(List<JavaType> typeArgs) {
         return null;
      }

      @Override
      public boolean needsOwnClass(boolean checkComponents) {
         return false;
      }

      @Override
      public boolean isDynamicNew() {
         return false;
      }

      @Override
      public void initDynStatements(Object inst, ExecutionContext ctx, TypeDeclaration.InitStatementMode mode) {

      }

      @Override
      public void clearDynFields(Object inst, ExecutionContext ctx) {

      }

      @Override
      public Object[] getImplementsTypeDeclarations() {
         return new Object[0];
      }

      @Override
      public Object[] getAllImplementsTypeDeclarations() {
         return new Object[0];
      }

      @Override
      public boolean isRealType() {
         return false;
      }

      @Override
      public void staticInit() {

      }

      @Override
      public boolean isTransformedType() {
         return false;
      }

      @Override
      public Object getArrayComponentType() {
         return null;
      }
   }

   /**
    * Used when we try to get the type for a lambda expression that is not valid for the inferred type.  This will happen during
    * method matching (inferredFinal = false) when we try out an inferredType to see if the method can match.
    * */
   public static class LambdaInvalidType implements ITypeDeclaration {
      BaseLambdaExpression rootExpr;

      public LambdaInvalidType(BaseLambdaExpression baseLambda) {
         rootExpr = baseLambda;
      }

      @Override
      public boolean isAssignableFrom(ITypeDeclaration other, boolean assignmentSemantics) {
         return false;
      }

      @Override
      public boolean isAssignableTo(ITypeDeclaration other) {
         return false;
      }

      @Override
      public boolean isAssignableFromClass(Class other) {
         return false;
      }

      @Override
      public String getTypeName() {
         return "Object";
      }

      @Override
      public String getFullTypeName(boolean includeDims, boolean includeTypeParams) {
         return "java.lang.Object";
      }

      @Override
      public String getFullTypeName() {
         return "java.lang.Object";
      }

      @Override
      public String getFullBaseTypeName() {
         return "java.lang.Object";
      }

      @Override
      public String getInnerTypeName() {
         return null;
      }

      @Override
      public Class getCompiledClass() {
         return null;
      }

      @Override
      public String getCompiledClassName() {
         return null;
      }

      @Override
      public String getCompiledTypeName() {
         return null;
      }

      @Override
      public Object getRuntimeType() {
         return null;
      }

      @Override
      public boolean isDynamicType() {
         return false;
      }

      @Override
      public boolean isDynamicStub(boolean includeExtends) {
         return false;
      }

      @Override
      public Object definesMethod(String name, List<?> parametersOrExpressions, ITypeParamContext ctx, Object refType, boolean isTransformed, boolean staticOnly, Object inferredType, List<JavaType> methodTypeArgs) {
         return null;
      }

      @Override
      public Object declaresConstructor(List<?> parametersOrExpressions, ITypeParamContext ctx) {
         return null;
      }

      @Override
      public Object definesConstructor(List<?> parametersOrExpressions, ITypeParamContext ctx, boolean isTransformed) {
         return null;
      }

      @Override
      public Object definesMember(String name, EnumSet<MemberType> type, Object refType, TypeContext ctx) {
         return null;
      }

      @Override
      public Object definesMember(String name, EnumSet<MemberType> type, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
         return null;
      }

      @Override
      public Object getInnerType(String name, TypeContext ctx) {
         return null;
      }

      @Override
      public boolean implementsType(String otherTypeName, boolean assignment, boolean allowUnbound) {
         // At this stage, we do not know if we implement the type.  Returning true, because we need to match essentially any type during
         // the type checking process, until the inferred type is set and we can really determine our type.  We might need to push some
         // operations to the 'validate' stage where this inferredType will definiteily have been set if they depend on an accurate result here.
         return true;
      }

      @Override
      public Object getInheritedAnnotation(String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve) {
         return null;
      }

      @Override
      public ArrayList<Object> getAllInheritedAnnotations(String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve) {
         return null;
      }

      @Override
      public Object getDerivedTypeDeclaration() {
         return null;
      }

      @Override
      public Object getExtendsTypeDeclaration() {
         return null;
      }

      @Override
      public Object getExtendsType() {
         return null;
      }

      @Override
      public List<?> getImplementsTypes() {
         return null;
      }

      @Override
      public List<Object> getAllMethods(String modifier, boolean hasModifier, boolean isDyn, boolean overridesComp) {
         return null;
      }

      @Override
      public List<Object> getMethods(String methodName, String modifier, boolean includeExtends) {
         return null;
      }

      @Override
      public Object getConstructorFromSignature(String sig) {
         return null;
      }

      @Override
      public Object getMethodFromSignature(String methodName, String signature, boolean resolveLayer) {
         return null;
      }

      @Override
      public List<Object> getAllProperties(String modifier, boolean includeAssigns) {
         return null;
      }

      @Override
      public List<Object> getAllFields(String modifier, boolean hasModifier, boolean dynamicOnly, boolean includeObjs, boolean includeAssigns, boolean includeModified) {
         return null;
      }

      @Override
      public List<Object> getAllInnerTypes(String modifier, boolean thisClassOnly) {
         return null;
      }

      @Override
      public DeclarationType getDeclarationType() {
         return null;
      }

      @Override
      public Object getClass(String className, boolean useImports) {
         return null;
      }

      @Override
      public Object findTypeDeclaration(String typeName, boolean addExternalReference) {
         return null;
      }

      @Override
      public JavaModel getJavaModel() {
         return rootExpr.getJavaModel();
      }

      @Override
      public boolean isLayerType() {
         return false;
      }

      @Override
      public Layer getLayer() {
         return rootExpr.getJavaModel().getLayer();
      }

      @Override
      public LayeredSystem getLayeredSystem() {
         return rootExpr.getLayeredSystem();
      }

      @Override
      public List<?> getClassTypeParameters() {
         return null;
      }

      @Override
      public Object[] getConstructors(Object refType) {
         return new Object[0];
      }

      @Override
      public boolean isComponentType() {
         return false;
      }

      @Override
      public DynType getPropertyCache() {
         return null;
      }

      @Override
      public boolean isEnumeratedType() {
         return false;
      }

      @Override
      public Object getEnumConstant(String nextName) {
         return null;
      }

      @Override
      public boolean isCompiledProperty(String name, boolean fieldMode, boolean interfaceMode) {
         return false;
      }

      @Override
      public List<JavaType> getCompiledTypeArgs(List<JavaType> typeArgs) {
         return null;
      }

      @Override
      public boolean needsOwnClass(boolean checkComponents) {
         return false;
      }

      @Override
      public boolean isDynamicNew() {
         return false;
      }

      @Override
      public void initDynStatements(Object inst, ExecutionContext ctx, TypeDeclaration.InitStatementMode mode) {

      }

      @Override
      public void clearDynFields(Object inst, ExecutionContext ctx) {

      }

      @Override
      public Object[] getImplementsTypeDeclarations() {
         return new Object[0];
      }

      @Override
      public Object[] getAllImplementsTypeDeclarations() {
         return new Object[0];
      }

      @Override
      public boolean isRealType() {
         return false;
      }

      @Override
      public void staticInit() {

      }

      @Override
      public boolean isTransformedType() {
         return false;
      }

      @Override
      public Object getArrayComponentType() {
         return null;
      }

      public String toString() {
         return "<invalid-lambda-expression>";
      }
   }
}
