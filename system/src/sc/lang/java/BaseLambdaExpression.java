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

/** Java 8 lambda expression.  To run these in JS we need to convert them to anonymous classes */
public abstract class BaseLambdaExpression extends Expression {
   /** These are the parameters to the lambda expression - they should match the inferredTypeMethod */
   transient Parameter parameters; // Either a copy of lambdaParams when they are full specified or Parameter types we create to mirror the method that will be generated
   /** This is the "functional interface" (i.e. one method which is unimplemented) we are implementing with this lambda expression. */
   transient Object inferredType = null;
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

   void updateInferredTypeMethod(Object methObj) {
      inferredTypeMethod = methObj;
   }

   public boolean lambdaParametersMatch(Object type) {
      Object ifaceMeth = getInterfaceMethod(type, false);
      if (ifaceMeth == null)
         return false;

      Object lambdaParams = getLambdaParameters(ifaceMeth, null);

      if (!parametersMatch(lambdaParams, ifaceMeth, getLayeredSystem())) {
         return false;
      }
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

   void initNewExpression() {
      if (inferredType == null) {
         // TODO: will this happen?
         displayError("No inferredType for lambda expression: ");
         return;
      }


      if (newExpr != null)
         System.err.println("*** reinitializing new expression!");

      Object ifaceMeth = getInterfaceMethod(inferredType, true);
      if (ifaceMeth == null) {
         System.err.println("*** No interface method for lambda expressoin: ");
         return;
      }

      updateInferredTypeMethod(ifaceMeth);

      if (ModelUtil.isParameterizedMethod(ifaceMeth) && ModelUtil.hasTypeParameters(inferredType) && !(ifaceMeth instanceof ParamTypedMethod)) {
         ifaceMeth = new ParamTypedMethod(ifaceMeth, (ITypeParamContext) inferredType, getEnclosingType(), null, null);
      }
      Object ifaceMethReturnType = ModelUtil.getReturnType(ifaceMeth, false);
      // If we can tell the return type of the lambda expression now, define any type parameters we learn from that type.
      Object newMethReturnType = getNewMethodReturnType();
      if (newMethReturnType != null) {
         // We get this again below after it's a param-typed method
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

      newMeth.setProperty("parameters", parameters = createLambdaParams(lambdaParams, ifaceMeth, typeCtx, enclType));
      newMeth.setProperty("type", JavaType.createFromParamType(ModelUtil.getParameterizedReturnType(ifaceMeth, null, true), typeCtx, enclType));
      lambdaMethod = newMeth;

      // Doing this after we have defined the parameters here so we can resolve the references
      if (!parametersMatch(lambdaParams, ifaceMeth, getLayeredSystem())) {
         displayError("Mismatch between lambda method: " + ModelUtil.getMethodName(ifaceMeth) + " and " + getExprType() + " parameters: " + getParamString(lambdaParams) + " and: " + ModelUtil.toDeclarationString(ifaceMeth));
         boolean x = parametersMatch(lambdaParams, ifaceMeth, getLayeredSystem());
      }

      // Always public since they are in an interface
      SemanticNodeList<Object> mods = new SemanticNodeList<Object>();
      mods.add("public");
      newMeth.setProperty("modifiers", mods);

      Statement lambdaBody = getLambdaBody(ifaceMeth);

      BlockStatement methodBody;
      if (lambdaBody instanceof BlockStatement) {
         methodBody = (BlockStatement) lambdaBody.deepCopy(ISemanticNode.CopyNormal, null);
      }
      else if (lambdaBody instanceof Expression) {
         methodBody = new BlockStatement();
         if (ModelUtil.typeIsVoid(ModelUtil.getReturnType(ifaceMeth, true))) {
            // The method is void and so does not return anything.  Just use <expr>
            methodBody.addStatementAt(0, ((Expression) lambdaBody).deepCopy(ISemanticNode.CopyNormal, null));
         }
         else{
            // Do the return <expr> ;
            methodBody.addStatementAt(0, ReturnStatement.create(((Expression) lambdaBody).deepCopy(ISemanticNode.CopyNormal, null)));
         }
      }
      else {
         displayError("Invalid method body type: ");
         return;
      }
      newMeth.setProperty("body", methodBody);

      classBody.add(newMeth);
      newExpr.setProperty("classBody", classBody);
      newExpr.parentNode = parentNode;

      // In the most general case, the type parameters for type of this expression are determined from the inferred return
      // type of the method.  That means searching through all return's and building up the common superclass.
      // We may have already done this earlier - if the return type is known earlier.  If those type parameters are used in the parameters to the method,
      // we can't start the method with those types defined properly to get the return type.
      if (ModelUtil.isParameterizedType(ifaceMethReturnType) && inferredType instanceof ParamTypeDeclaration && ((ParamTypeDeclaration) inferredType).hasUnboundParameters()) {
         // Start this now so we can get it's inferred return type
         ParseUtil.initAndStartComponent(newMeth);
         newMethReturnType = newMeth.getInferredReturnType();
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
      newMeth.setProperty("type", JavaType.createFromParamType(paramReturnType, typeCtx, enclType));
      if (ModelUtil.hasTypeParameters(inferredType)) {
         SemanticNodeList<JavaType> typeParams = new SemanticNodeList<JavaType>();
         int numParams = ModelUtil.getNumTypeParameters(inferredType);
         for (int i = 0; i < numParams; i++) {
            Object typeParam = ModelUtil.getTypeParameter(inferredType, i);
            if (typeParam instanceof JavaType)
               typeParams.add((JavaType) ((JavaType) typeParam).deepCopy(ISemanticNode.CopyNormal, null));
            else if (typeParam instanceof WildcardType || typeParam instanceof ParameterizedType || typeParam instanceof ExtendsType.LowerBoundsTypeDeclaration) {
               typeParams.add((JavaType.createFromParamType(typeParam, inferredType instanceof ITypeParamContext ? (ITypeParamContext) inferredType : null, null)));
            }
            else
               typeParams.add(ClassType.create(ModelUtil.getTypeName(typeParam)));
         }
         newExpr.setProperty("typeArguments", typeParams);
      }

      propagateInferredType(inferredType, paramReturnType);
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
            // TODO: need to map type parameters from one type to the other...
            //System.out.println("*** Unsupported type parameters case");
            // We know that typeParamName is a type parameter for methType, but we need to convert to the type parameter for methType
            Object typeParamRes = ModelUtil.resolveTypeParameter(methType, paramType, ifaceMethType);
            typeParamName = ModelUtil.getTypeParameterName(typeParamRes);
         }
         paramType.setTypeParameter(typeParamName, newMethType);
      } else if (ifaceMethType instanceof ExtendsType) {
         System.out.println("*** Unknown extends reference");
      } else if (ModelUtil.isParameterizedType(ifaceMethType))
         System.out.println("*** Unknown parameterized type"); // TODO: do we need to do extraction and setting of type parameters here
   }

   private void updateReturnTypeParameters(Object ifaceMeth, Object ifaceMethReturnType, Object newMethReturnType) {
      addTypeParameterMapping(ifaceMeth, ifaceMethReturnType, newMethReturnType);
   }

   // Overridden in MethodReference
   void updateMethodTypeParameters(Object ifaceMeth) {
   }

   protected void propagateInferredType(Object type, Object methReturnType) {
      if (newExpr != null)
         newExpr.setInferredType(inferredType);
   }

   public Object getNewMethodReturnType() {
      return null;
   }

   private static boolean parametersMatch(Object lambdaParams, Object meth, LayeredSystem sys) {
      Object[] params = ModelUtil.getGenericParameterTypes(meth, true);
      // One parameter special case for lambda's
      if (PString.isString(lambdaParams)) {
         return params.length == 1;
      }
      // Lambda sends in the list of names - the length only needs to match
      else if (lambdaParams instanceof SemanticNodeList) {
         return ((SemanticNodeList) lambdaParams).size() == (params == null ? 0 : params.length);
      }
      else if (lambdaParams instanceof Parameter) {
         Object[] paramTypes = ((Parameter) lambdaParams).getParameterTypes();
         return ModelUtil.parametersMatch(paramTypes, params, false, sys); // TODO: should we allow unbound type parameters to match here
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
         initNewExpression();
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
         displayError("Unrecognized use of lambda expression: ");
      if (needsStart) {
         needsStart = false;
         super.start();
      }
      super.validate();
   }

   private static Parameter createLambdaParams(Object params, Object meth, ITypeParamContext ctx, ITypeDeclaration definedInType) {
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
            if (ModelUtil.isTypeVariable(resMethTypes[i]))
               resMethTypes[i] = ModelUtil.getTypeParameterDefault(resMethTypes[i]);
         }
      }
      return Parameter.create(resMethTypes, names.toArray(new String[names.size()]), ctx, definedInType);
   }

   @Override
   public boolean isStaticTarget() {
      // TODO: is this right?
      return false;
   }

   public Object getTypeDeclaration() {
      if (inferredType == null)
         return new LambdaInferredType(this);
      if (newExpr == null)
         initNewExpression();
      if (newExpr == null)
         return new LambdaInferredType(this);
      return inferredType;
   }

   public Object eval(Class expectedType, ExecutionContext ctx) {
      if (newExpr == null)
         initNewExpression();
      if (newExpr != null)
         return newExpr.eval(expectedType, ctx);
      throw new IllegalArgumentException("eval of undefined lambda expression");
   }

   public boolean transform(ILanguageModel.RuntimeType runtime) {
      if (getLayeredSystem().getNeedsAnonymousConversion() || bindingStatement != null) {
         if (newExpr == null)
             initNewExpression();
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

   public void setInferredType(Object parentType) {
      // Convert between the ParameterizedType and ParamTypeDeclaration
      if (parentType instanceof ParameterizedType)
         parentType = ModelUtil.getTypeDeclFromType(parentType, parentType, false, getLayeredSystem(), false, getEnclosingType());
      inferredType = parentType;
      if (needsStart) {
         needsStart = false;
         if (newExpr == null)
            initNewExpression();
         super.start();
      }
   }

   public BaseLambdaExpression deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      // TODO: should we copy the inferred type here?
      return (BaseLambdaExpression) super.deepCopy(options, oldNewMap);
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
      public Object definesMethod(String name, List<?> parametersOrExpressions, ITypeParamContext ctx, Object refType, boolean isTransformed, boolean staticOnly, Object inferredType) {
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
}
