/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.BindingDirection;
import sc.bind.Bind;
import sc.bind.IBinding;
import sc.lang.ILanguageModel;
import sc.lang.ISrcStatement;
import sc.lang.SCLanguage;
import sc.lang.SemanticNodeList;
import sc.lang.js.JSUtil;
import sc.layer.LayeredSystem;
import sc.parser.*;
import sc.type.CTypeUtil;
import sc.type.PTypeUtil;
import sc.type.RTypeUtil;
import sc.type.Type;
import sc.util.StringUtil;

import java.lang.reflect.Array;
import java.util.*;

public class NewExpression extends IdentifierExpression {
   public static String ANON_TYPE_PREFIX = "__Anon";
   public SemanticNodeList<Expression> arrayDimensions;
   public ArrayInitializer arrayInitializer;
   public String typeIdentifier;
   public SemanticNodeList<JavaType> typeArguments;
   public SemanticNodeList<Statement> classBody;

   public transient Object boundType;
   public transient String classPropertyName;
   public transient String boundTypeName;
   /** For anonymous types, we may create a real top-level type to represent it in the runtime model. */
   public transient AnonClassDeclaration anonType;
   /** This is a clone of the anonType which gets transformed.  It is only available in the transformed model */
   public transient AnonClassDeclaration anonTypeTransformed;
   public transient Object constructor;
   public transient boolean isStaticContext;
   /** True if this new expression is part of a lambda expression.  If so, it does not create a real type in the type system */
   public transient boolean lambdaExpression = false;

   private boolean anonTypeInited = false;

   private int anonId = -1;

   public static NewExpression create(String identifier, SemanticNodeList<Expression> args) {
      NewExpression newExpr = new NewExpression();
      // this is not used... oops
      //newExpr.setIdentifiersFromArgs(identifier);
      newExpr.typeIdentifier = identifier;
      newExpr.setProperty("arguments", args);
      return newExpr;
   }

   public static NewExpression create(String identifier, SemanticNodeList<Expression> arrayDimensions, ArrayInitializer init) {
      NewExpression newExpr = new NewExpression();
      // this is not used... oops
      //newExpr.setIdentifiersFromArgs(identifier);
      newExpr.typeIdentifier = identifier;
      if (arrayDimensions != null && arrayDimensions.size() == 0)
         arrayDimensions.add(null); // To be consistent with the parsed version which stores a null here
      newExpr.setProperty("arrayDimensions", arrayDimensions);
      newExpr.setProperty("arrayInitializer", init);
      return newExpr;
   }

   public void init() {
      if (initialized) return;

      super.init();
   }

   /* TODO: remove this method Previously we needed a first-pass to just initialize the type and the id without copying over the members.
    * We would do this step during 'init' and then during 'start' we'd finish creating the type.  Because we init
    * all body members in a layer before we've assembled the layers into one, we can't allocate the anonIds until
    * the start phase.  So this method can probably be removed.
    */
   private void initAnonId() {
      LayeredSystem sys = getLayeredSystem();

      // First assign the anonId before we get into things so that if any children start creating anonymous types which
      // ends up copying around NewExpressions, we have a consistent anonId for the original and the copy.
      // NOTE: Now that we call initAnonymousType in start() no matter what, we are not doing the NeedsAnonymousConversion check
      // Why are we initializing the anonymous type if we are not transforming them to JS?
      if (anonId == -1 && classBody != null && sys != null /* && sys.getNeedsAnonymousConversion()*/) {
         BodyTypeDeclaration enclType = getEnclosingType();
         if (enclType != null) {
            // Need to create this up front so we can return it in getEnclosingType for any children of the class body
            // They will get added later in initAnonymousType
            // Though note that also this class overrides the findX methods to look in the body of the new expr so
            // maybe we do not need to do this afterall?   Right now it's a mix of both approaches.
            anonType = new AnonClassDeclaration();
            anonId = enclType.allocateAnonId();
            if (enclType.typeName.equals("EditorModel"))
               System.out.println("*** init setting anonId for: " + typeIdentifier + ": " + anonId);
            anonType.typeName = ANON_TYPE_PREFIX + anonId;
            anonType.operator = "class";
            anonType.newExpr = this;

            enclType.addToHiddenBody(anonType);
         }
      }
   }

   private transient boolean starting = false;

   public void start() {
      if (started || inactive) return;

      starting = true;

      JavaModel model = getJavaModel();
      if (model == null || typeIdentifier == null) {
         super.start();
         return;
      }

      // Need to define our type before our body so it can be used by statements in the body
      boundType = findType(typeIdentifier);
      if (boundType == null) {
         boundType = model.findTypeDeclaration(typeIdentifier, true);
         if (boundType == null) {
            displayTypeError("No type: ", typeIdentifier, " for: ");
         }
      }
      if (boundTypeName != null && boundType != null)
         boundTypeName = ModelUtil.getTypeName(boundType);

      if (classBody != null) {
         initAnonymousType();
      }

      super.start();

      if (boundType != null && arguments != null) {
         constructor = parameterizeMethod(this, ModelUtil.declaresConstructor(getLayeredSystem(), boundType, arguments, null), null, inferredType, arguments, getMethodTypeArguments());
         propagateInferredTypes();
      }

      if (bindingDirection != null && bindingDirection.doForward() && bindingDirection.doReverse())
         displayError("Bi-directional bindings (the :=: operator) not supported for new expressions: ");
      classPropertyName = CTypeUtil.decapitalizePropertyName(CTypeUtil.getClassName(typeIdentifier));
   }

   private void propagateInferredTypes() {
      // TODO: do we need to stage this if we are in a nested expression and inferredtype has not been set yet (see IdentifierExpression or just inherit from that)
      //if (arguments != null && constructor == null && arguments.size() > 0) {
      //   System.out.println("***");
      //   ModelUtil.declaresConstructor(boundType, arguments, null);
      //}
      if (constructor != null && arguments != null && (inferredType != null || !hasInferredType())) {
         propagateInferredArgs(this, constructor, arguments);
      }
   }

   public boolean setInferredType(Object inferredType, boolean finalType) {
      if (constructor != null && arguments != null) {
         if (constructor instanceof ParamTypedMethod) {
            // Need to convert inferredType to an instance of boundType - which means mapping any type parameters
            if (boundType != null && !ModelUtil.sameTypes(inferredType, boundType)) {
               if (ModelUtil.isParameterizedType(inferredType) && ModelUtil.hasDefinedTypeParameters(boundType) && !ModelUtil.isTypeVariable(inferredType) && ModelUtil.isAssignableFrom(inferredType, boundType)) {
                  List<?> typeParams = ModelUtil.getTypeParameters(boundType);
                  LayeredSystem sys = getLayeredSystem();
                  inferredType = new ParamTypeDeclaration(sys, typeParams, ModelUtil.resolveSubTypeParameters(boundType, inferredType), boundType);
               }
               else
                  inferredType = boundType;
            }
            ((ParamTypedMethod) constructor).setInferredType(inferredType);
            super.setInferredType(inferredType, finalType);
         }
         propagateInferredArgs(this, constructor, arguments);
      }
      else
         super.setInferredType(inferredType, finalType);
      return false;
   }

   public void validate() {
      if (validated) return;

      super.validate();

      if (boundType != null && arguments != null) {
         if (constructor == null && arguments.size() > 0) {
            displayTypeError("Missing constructor in type: " + boundType + " for new expression: ");
            ModelUtil.declaresConstructor(getLayeredSystem(), boundType, arguments, null);
         }

         if (constructor == null && arguments.size() == 0) {
            if (bindingDirection != null && boundType instanceof BodyTypeDeclaration) {
               ((BodyTypeDeclaration) boundType).needsDynDefaultConstructor = true;
            }
         }
         else if (constructor != null) {
            IdentifierExpression.checkForDynMethod(constructor, boundType, this);
         }
      }
   }

   public Object findMember(String name, EnumSet<MemberType> mtype, Object fromChild, Object refType, TypeContext ctx, boolean skipIfaces) {
      // Don't search this types info unless coming from the body.  Any parameters to the constructors
      // should not see those values.
      if (classBody != null && classBody.indexOf(fromChild) != -1) {
         Object v = BodyTypeDeclaration.findMemberInBody(classBody, name, mtype, refType, ctx);
         if (v != null)
            return v;

         if (boundType != null) {
            v = ModelUtil.definesMember(boundType, name, mtype, refType, ctx, skipIfaces, false);
            if (v != null)
               return v;
         }
      }
      return super.findMember(name, mtype, this, refType, ctx, skipIfaces);
   }

   public Object findMethod(String name, List<? extends Object> params, Object fromChild, Object refType, boolean staticOnly, Object inferredType) {
      if (classBody != null && classBody.indexOf(fromChild) != -1) {
         Object v = BodyTypeDeclaration.findMethodInBody(classBody, name, params, null, refType, staticOnly, inferredType, null);
         if (v != null)
            return v;

         if (boundType != null) {
            v = ModelUtil.definesMethod(boundType, name, params, null, refType, ModelUtil.isTransformedType(boundType), staticOnly, inferredType, null);
            if (v != null)
               return v;
         }
      }
      return super.findMethod(name, params, this, refType, staticOnly, inferredType);
   }
   
   public boolean isReferenceInitializer() {
      return true;
   }

   public boolean transform(ILanguageModel.RuntimeType runtime) {
      boolean any = false;

      if (classBody != null  && getLayeredSystem().getNeedsAnonymousConversion()) {
         // Need to create the anonymous type during the transform step, so that it shows up as an inner type even
         // if we never called transformToJS - cause needsSave is false.
         ClassDeclaration anonType = getAnonymousType(true);
      }

      if (!getLayeredSystem().options.clonedTransform && anonType != null) {
         anonType.transform(runtime);
      }

      /* We are creating a component.  To get the right semantics, we use the newX method on the enclosing class */
      if (needsTransform()) {
         if (ModelUtil.isDynamicNew(boundType)) {
            SemanticNodeList<Expression> newArgs = new SemanticNodeList<Expression>();
            IdentifierExpression dc = IdentifierExpression.create("sc.lang.DynObject.create");
            newArgs.add(StringLiteral.create(ModelUtil.getTypeName(boundType)));
            if (constructor == null && arguments.size() > 1) {
               newArgs.add(NullLiteral.create());
            }
            else {
               String conSig = constructor == null ? null : ModelUtil.getTypeSignature(constructor);
               newArgs.add(StringLiteral.createNull(conSig));
            }
            for (Expression oldArg:arguments) {
               newArgs.add(oldArg);
            }
            dc.setProperty("arguments", newArgs);
            CastExpression ce = CastExpression.create(ModelUtil.getCompiledClassName(boundType), dc);
            parentNode.replaceChild(this, ce);
         }
         else {
            IdentifierExpression ie;
            boolean isTopLevelType = ModelUtil.getEnclosingType(boundType) == null;
            if (typeIdentifier.indexOf(".") == -1) {
               String newStr = "new" + CTypeUtil.capitalizePropertyName(classPropertyName);
               if (identifiers != null && identifiers.size() > 0) {
                  ArrayList<String> ids = new ArrayList<String>();
                  for (int i = 0; i < identifiers.size(); i++)
                     ids.add(identifiers.get(i).toString());
                  if (isTopLevelType)
                     ids.add(typeIdentifier);
                  ids.add(newStr);
                  ie = IdentifierExpression.create(ids.toArray(new String[ids.size()]));
               }
               else {
                  if (isTopLevelType)
                     ie = IdentifierExpression.create(typeIdentifier, newStr);
                  else
                     ie = IdentifierExpression.create(newStr);
               }
               //ie = IdentifierExpression.create(typeIdentifier, "new" + TypeUtil.capitalizePropertyName(classPropertyName));
            }
            else {
               Object enclInstType;
               String newIdentifier = typeIdentifier;
               String[] types = StringUtil.split(newIdentifier, '.');
               int len = types.length;
               String lastTypeName = types[len-1];

               /** Java makes us qualify the type name with an explicit "this" if we are creating a new class which is a non-static inner instance class. Otherwise, it says the type is not in scope (even if the method we are in is in scope) */
               if ((enclInstType = ModelUtil.getEnclosingInstType(boundType)) != null) {
                  BodyTypeDeclaration enclType = getEnclosingType();
                  while (enclType != null) {
                     if (ModelUtil.isAssignableFrom(enclInstType, enclType)) {
                        newIdentifier = ModelUtil.getTypeName(ModelUtil.getAccessClass(boundType)) + ".this";

                        types = StringUtil.split(newIdentifier, '.');
                        len = types.length;
                        break;
                     }
                     enclType = enclType.getEnclosingType();
                  }
               }
               String[] identifiers = new String[len+1];
               System.arraycopy(types,0,identifiers,0,len);
               identifiers[len] = "new" + CTypeUtil.capitalizePropertyName(lastTypeName);
               ie = IdentifierExpression.create(identifiers);
            }
            ie.setProperty("arguments", arguments);
            parentNode.replaceChild(this, ie);
            int last = ie.identifiers.size() - 1;
            // If we are not able to resolve the newX method, it's because the component has not been transformed yet.  We store the enclosing type
            // so that when we transformToJS this identifier expression, we know the type for property 'this' and 'outer' generation.
            if (ie.boundTypes[last] == null)
               ie.boundTypes[last] = boundType;
         }
         any = true;
      }

      if (super.transform(runtime))
         any = true;

      return any;
   }

   public boolean needsTransform() {
      return boundType != null &&
              ((ModelUtil.transformNewExpression(getLayeredSystem(), boundType) &&
                !inNamedPropertyMethod(classPropertyName) && !inObjectGetMethod(boundType) && !inNewMethod() && arrayDimensions == null) ||
               ModelUtil.isDynamicNew(boundType));
   }

   public Object getTypeDeclaration() {
      if (!started)
         start();
      resolve();
      if (arrayDimensions == null)
         return boundType;
      else {
         // [1] or [] are both 1d
         int ndim = arrayDimensions.size();
         if (ndim == 0)
            ndim = 1;
         Object type = boundType;
         JavaModel model = getJavaModel();
         if (type == null) // undefined reference - don't inject a null type into the system
            type = Object.class;

         TypeDeclaration modelType = model == null ? null : model.getModelTypeDeclaration();
         return new ArrayTypeDeclaration(getLayeredSystem(), modelType, type, StringUtil.repeat("[]", ndim));
      }
   }

   public List<Object> evalTypeArguments(Object type) {
      ArrayList<Object> res = new ArrayList<Object>();
      // Handle the diamond operator: <> when this is specified, we inherit the type arguments from the inferredType
      if (typeArguments.size() == 0) {
         if (inferredType != null && !ModelUtil.isTypeVariable(inferredType)) {
            int numTypeParams = ModelUtil.getNumTypeParameters(inferredType);
            for (int i = 0; i < numTypeParams; i++) {
               res.add(ModelUtil.getTypeParameter(inferredType, i));
            }
         }
         else {
            List<?> typeParams = ModelUtil.getTypeParameters(type);
            for (int i = 0; i < typeParams.size(); i++) {
               res.add(typeParams.get(i));
            }
         }
      }
      else {
         for (int i = 0; i < typeArguments.size(); i++) {
            res.add(typeArguments.get(i).getTypeDeclaration());
         }
      }
      return res;
   }

   public Object getGenericType() {
      Object type = getTypeDeclaration();
      if (typeArguments == null)
         return type;
      if (type == null)
         return null;
      ITypeDeclaration enclType = getEnclosingType();
      if (enclType != null)
         return new ParamTypeDeclaration(enclType.getLayeredSystem(), enclType, ModelUtil.getTypeParameters(type), evalTypeArguments(type), type);
      else
         return new ParamTypeDeclaration(getLayeredSystem(), ModelUtil.getTypeParameters(type), evalTypeArguments(type), type);
   }

   protected boolean inNewMethod() {
      MethodDefinition currentMethod;

      if ((currentMethod = getCurrentMethod()) != null &&
              currentMethod.name != null && currentMethod.name.startsWith("new")) {
         if (currentMethod.name.endsWith(CTypeUtil.capitalizePropertyName(classPropertyName)) &&
             currentMethod.name.length() == 3 + classPropertyName.length())
         return true;
      }
      return false;
   }

   public ExecResult exec(ExecutionContext ctx) {
      eval(null, ctx);
      return ExecResult.Next;
   }

   public void resolve() {
      boundType = ModelUtil.resolve(boundType, true);
   }

   public Object eval(Class expectedType, ExecutionContext ctx) {
      if (bindingDirection != null)
         return initBinding(expectedType, ctx);

      resolve();

      if (boundType == null) {
         throw new IllegalArgumentException("No type for " + toDefinitionString());
      }
      if (!ctx.allowCreate(boundType))
         throw new IllegalArgumentException("Not allowed to create new instance of type for " + toDefinitionString());
      if (arguments != null) {
         Object typeToCreate = classBody != null ? getAnonymousType(false) : boundType;
         boolean isDynamic = ModelUtil.isDynamicType(typeToCreate);
         Object type;
         if (!isDynamic) {
            Class cl = ModelUtil.getCompiledClass(typeToCreate);
            if (cl == null) {
               throw new IllegalArgumentException("No compiled class: " + typeToCreate);
            }
            if (!transformed && ModelUtil.isComponentType(cl)) {
               Class accessClass = (Class) ModelUtil.getEnclosingInstType(cl);
               if (accessClass != null) {
                  String name = cl.getName().replace('$', '.');
                  String methodName = "new" + CTypeUtil.capitalizePropertyName(CTypeUtil.getClassName(name));
                  Object method = ModelUtil.definesMethod(accessClass, methodName, arguments, null, null, true, false, null, null);
                  if (method != null) {
                     Object[] params = ModelUtil.expressionListToValues(arguments, ctx);
                     Object thisObj = ctx.findThisType(accessClass);
                     return PTypeUtil.invokeMethod(thisObj, method, params);
                  }
                  else {
                     throw new IllegalArgumentException("*** No new method for component");
                  }
               }
               else {
                  Object[] params = ModelUtil.constructorArgListToValues(cl, arguments, ctx, null);
                  return RTypeUtil.newComponent(cl, params);
               }
            }
            else
               return ModelUtil.createInstance(cl, ModelUtil.getTypeSignature(constructor), arguments, ctx);
         }
         else {
            if (typeToCreate instanceof TypeDeclaration) {
               TypeDeclaration typeDecl = (TypeDeclaration) typeToCreate;
               BodyTypeDeclaration enclInst = typeDecl.getEnclosingInstType();
               if (isStaticContext || enclInst == null)
                  return typeDecl.createInstance(ctx, ModelUtil.getTypeSignature(constructor), arguments);
               else {
                  Object outerInst;
                  if (identifiers != null && identifiers.size() >= 1) {
                     outerInst = super.eval(null, ctx); // This is the var.new X() case - evaluate the identifier expression and use that as the outer instance
                  }
                  else
                     outerInst = ctx.findThisType(enclInst);
                  return typeDecl.createInstance(ctx, ModelUtil.getTypeSignature(constructor), arguments, null, outerInst, -1);
               }
            }
            else {
               runtimeError(IllegalArgumentException.class, "No type for new expression: ");
               return null; // not reached
            }
         }
      }
      else {
         Class componentClass = ModelUtil.getCompiledClass(boundType);
         int ndim = Math.max(1, arrayDimensions.size());
         Class expectedtype = Type.get(componentClass).getArrayClass(componentClass, ndim);
         if (arrayInitializer == null) {
            if (arrayDimensions.size() == 0) {
               displayError("Missing dimensions to new array: ");
               return null;
            }
            else {
               int nalloc, ntype;
               nalloc = arrayDimensions.size();
               int last = nalloc - 1;
               while (arrayDimensions.get(last) == null && last >= 0)
                  last--;

               ntype = nalloc - 1 - last;
               nalloc = last + 1;

               int[] dims = new int[nalloc];
               for (int i = 0; i < nalloc; i++) {
                  Expression dim = arrayDimensions.get(i);
                  dims[i] = (Integer) dim.eval(int.class, ctx);
               }
               if (ntype > 0) {
                  Type t = Type.get(componentClass);
                  componentClass = t.getArrayClass(componentClass, ntype);
               }
               return Array.newInstance(componentClass, dims);
            }
         }
         else
            return arrayInitializer.eval(expectedType, ctx);
      }
   }

   public ClassDeclaration getAnonymousType(boolean xform) {
      if (anonType == null) {
         initAnonymousType();
      }
      if (xform) {
         if (getEnclosingType().isTransformedType()) {
            if (anonTypeTransformed == null) {
               anonTypeTransformed = (AnonClassDeclaration) anonType.deepCopy(CopyAll | CopyTransformed, null);
               anonTypeTransformed.newExpr = this;
               getEnclosingType().addToHiddenBody(anonTypeTransformed);
               anonTypeTransformed.transform(ILanguageModel.RuntimeType.JAVA);
            }
            return anonTypeTransformed;
         }
      }
      return anonType;
   }

   void initAnonymousType() {
      if (classBody == null)
         return;

      /* This happens when styling nodes but we start the model properly below I think?
      if (!started && !starting) {
         System.err.println("*** Initializing anonymous type on an unstarted new expression");
      }
      */

      if (!anonTypeInited) {
         // It seems we can get here at least from refreshBoundTypes - when after an incremental compile we might not have started the model.
         // always start models from the top-down, or weird problems show up - like anonIds getting allocated in an inconsistent order
         // One place this can happen is getEnclosingType, which for a new expression will call this.
         if (!isStarted() && !starting) {
            JavaModel model = getJavaModel();
            if (!model.isStarted())
               ParseUtil.initAndStartComponent(model);
         }

         BodyTypeDeclaration enclType = getEnclosingType();
         if (anonTypeInited)
            return;

         boolean needsAdd = false;
         if (anonType == null) {
            anonType = new AnonClassDeclaration();
            needsAdd = true;
            anonType.newExpr = this;
            anonType.operator = "class";
         }
         if (anonId == -1) {
            anonId = enclType == null ? 1 : enclType.allocateAnonId();
            anonType.typeName = ANON_TYPE_PREFIX + anonId;
         }
         anonTypeInited = true;

         //anonType.parentNode = getEnclosingType().body;
         // If the context in which we define the anon type is static, it needs to be static
         if (isStaticContext = isStatic())
            anonType.addModifier("static");
         anonType.addModifier("public");
         if (boundType == null)
            boundType = Object.class;
         JavaType baseType = ClassType.create(ModelUtil.getTypeName(boundType));
         if (typeArguments != null && baseType instanceof ClassType) {
            ClassType classBaseType = (ClassType) baseType;
            SemanticNodeList<JavaType> newArgs = (SemanticNodeList<JavaType>) typeArguments.deepCopy(CopyNormal, null);
            classBaseType.setResolvedTypeArguments(newArgs);
         }
         if (ModelUtil.isInterface(boundType)) {
            SemanticNodeList impl = new SemanticNodeList(2);
            impl.add(baseType);
            anonType.setProperty("implementsTypes", impl);
         }
         else
            anonType.setProperty("extendsType", baseType);

         anonType.setProperty("body", classBody.deepCopy(CopyNormal, null));
         if (needsAdd) {
            if (enclType != null)
               enclType.addToHiddenBody(anonType);
         }
         else {
            // In case the type was started, init the type info on it again after we added the extends/implements.
            anonType.reInitTypeInfo();
         }
         // Make sure it's been initialized and started before we return it.
         ParseUtil.realInitAndStartComponent(anonType);
      }
   }

   public void changeExpressionsThis(TypeDeclaration td, TypeDeclaration outer, String varName) {
      // TODO: it seems like we need to figure out if the type identifier for this expression
      // will be affected by the move and possibly rewrite it in terms of the variable name provided?

      // If we're physically moving to a new file because someone modified an extended type, we need to
      // rewrite local names as absolute.
      if (!ModelUtil.sameTypes(ModelUtil.getRootType(td), ModelUtil.getRootType(outer))) {
         // Convert to an absolute type since the local scope and imports are changing
         setProperty("typeIdentifier", ModelUtil.getTypeName(boundType));
      }
   }

   public String getBindingTypeName() {
      return arrayDimensions == null ? (nestedBinding ? "bindNewP" : "bindNew" ) : nestedBinding ? "newArrayP" : "newArray";
   }

   // TODO: need to handle the identifiers that might prefix this expression and array initializers
   public void transformBindingArgs(SemanticNodeList<Expression> bindArgs, BindDescriptor bd) {
      LayeredSystem sys = getLayeredSystem();
      if (ModelUtil.needsDynType(boundType) && constructor != null) {
         TypeDeclaration td = (TypeDeclaration) boundType;
         TypeDeclaration outerType = td.getRootType();
         if (outerType == null)
            outerType = td;

         IdentifierExpression getMethod = create(outerType.getFullTypeName() + ".resolveMethod" + td.getDynamicStubParameters().getRuntimeInnerName());
         SemanticNodeList<Expression> getMethodArgs = new SemanticNodeList<Expression>();
         getMethodArgs.add(StringLiteral.create(td.getTypeName()));
         getMethodArgs.add(StringLiteral.create(ModelUtil.getTypeSignature(constructor)));
         /*
         for (int i = 0; i < arguments.size(); i++) {
            Object argType = arguments.get(i).getTypeDeclaration();
            if (argType == null) {
               System.err.println("*** No defined type for argument: " + arguments.get(i).toDefinitionString() + " in expression: " + toDefinitionString());
               getMethodArgs.add(NullLiteral.create());
            }
            else
               getMethodArgs.add(ClassValueExpression.create(ModelUtil.getTypeName(argType)));
         }
         */
         getMethod.setProperty("arguments",getMethodArgs);
         bindArgs.add(getMethod);
      }
      else if (sys.needsExtDynType(boundType) && constructor != null) {
         String fullTypeName = ModelUtil.getTypeName(boundType);
         IdentifierExpression getMethod = IdentifierExpression.create(sys.buildInfo.getExternalDynTypeName(fullTypeName) + ".resolveMethod");
         SemanticNodeList<Expression> getMethodArgs = new SemanticNodeList<Expression>();
         getMethodArgs.add(StringLiteral.create(CTypeUtil.getClassName(fullTypeName)));
         getMethodArgs.add(StringLiteral.create(ModelUtil.getTypeSignature(constructor)));
         /*
         for (int i = 0; i < arguments.size(); i++) {
            Object argType = arguments.get(i).getTypeDeclaration();
            if (argType == null) {
               System.err.println("*** No defined type for argument: " + arguments.get(i).toDefinitionString() + " in expression: " + toDefinitionString());
               getMethodArgs.add(NullLiteral.create());
            }
            else
               getMethodArgs.add(ClassValueExpression.create(ModelUtil.getTypeName(argType)));
         }
         */
         getMethod.setProperty("arguments",getMethodArgs);
         bindArgs.add(getMethod);
      }
      else
         bindArgs.add(ClassValueExpression.create(typeIdentifier));
      if (arrayDimensions == null) {
         bindArgs.add(constructor == null ? NullLiteral.create() : StringLiteral.create(ModelUtil.getTypeSignature(constructor)));
         // For instance "new" operators for inner classes
         if (!bd.isStatic && isInnerInstanceClass()) {
            SemanticNodeList<Expression> argsWithThis = new SemanticNodeList<Expression>(this, arguments.size() + 1);
            IdentifierExpression ic = IdentifierExpression.create("sc.bind.Bind.constantP");
            SemanticNodeList<Expression> thisArg = new SemanticNodeList<Expression>(1);
            thisArg.add(IdentifierExpression.create("this"));
            ic.setProperty("arguments", thisArg);
            argsWithThis.add(ic);
            argsWithThis.addAll(arguments);
            bindArgs.add(createBindingArray(argsWithThis, false));
         }
         else
            bindArgs.add(createBindingArray(arguments, false));
      }
      else {
         bindArgs.add(createBindingArray(arrayInitializer.initializers, false));
      }
   }

   public void evalBindingArgs(List<Object> bindArgs, boolean isStatic, Class expectedType, ExecutionContext ctx) {
      bindArgs.add(ModelUtil.getRuntimeType(boundType));
      if (arrayDimensions == null) {
         bindArgs.add(constructor == null ? null : ModelUtil.getTypeSignature(constructor));
         // For instance "new" operators for inner classes
         if (!isStatic && isInnerInstanceClass()) {
            /*
            SemanticNodeList<Expression> argsWithThis = new SemanticNodeList<Expression>(this, arguments.size() + 1);
            IdentifierExpression ic = IdentifierExpression.create("sc.bind.Bind.constantP");
            SemanticNodeList<Expression> thisArg = new SemanticNodeList<Expression>(1);
            thisArg.add(IdentifierExpression.create("this"));
            ic.setProperty("arguments", thisArg);
            argsWithThis.add(ic);
            argsWithThis.addAll(arguments);
            argsWithThis.parentNode = this;
            ParseUtil.startComponent(argsWithThis);
            bindArgs.add(evalBindingParameters(expectedType, ctx, argsWithThis.toArray(new Expression[argsWithThis.size()])));
            */
            IBinding[] result = new IBinding[arguments.size()+1];
            result[0] = Bind.constantP(ctx.getCurrentObject());
            for (int i = 0; i < arguments.size(); i++)
               result[i+1] = (IBinding) arguments.get(i).evalBinding(expectedType, ctx);
            bindArgs.add(result);
         }
         else {
            if (arguments != null)
               bindArgs.add(evalBindingParameters(expectedType, ctx, arguments.toArray(new Expression[arguments.size()])));
         }
      }
      else {
         Class cl = ModelUtil.getCompiledClass(boundType);
         // Returns the IBinding[] array for the initializers
         bindArgs.add(evalBindingArray(cl, arrayInitializer.initializers, ctx));
      }
   }

   private boolean isInnerInstanceClass() {
      TypeDeclaration currentType = getEnclosingType();
      // This variant will do the "a.b" resolution whereas TypeDeclaration.getInnerType does not
      Object innerType = ModelUtil.getInnerType(currentType, typeIdentifier, null);
      return innerType != null && !ModelUtil.hasModifier(innerType, "static");
   }

   public void styleNode(IStyleAdapter adapter) {
      // TODO: is there a case where we need to do per-value styling here?
      if (parseNode != null) {
         parseNode.styleNode(adapter, null, null, -1);
      }
      // For this case unless it is a rootless node, we could walk up to the root node, use the start parselet from that language and generate it.
      // then presumably we'd have a parse node we could use.
      else
         throw new IllegalArgumentException("No parse tree new expression's semantic node");
   }

   public void refreshBoundTypes(int flags) {
      super.refreshBoundTypes(flags);

      if (arrayDimensions != null) {
         for (Expression ad:arrayDimensions) {
            if (ad != null)
               ad.refreshBoundTypes(flags);
         }
      }

      if (arrayInitializer != null)
         arrayInitializer.refreshBoundTypes(flags);

      if (typeArguments != null)
         for (JavaType ta:typeArguments)
            ta.refreshBoundType(flags);

      if (classBody != null)
         for (Statement cb:classBody)
            cb.refreshBoundTypes(flags);

      if (boundType != null) {
         boundType = ModelUtil.refreshBoundType(getLayeredSystem(), boundType, flags);
      }
   }

   public int transformTemplate(int ix, boolean statefulContext) {
      ix = super.transformTemplate(ix, statefulContext);

      if (arrayDimensions != null) {
         for (Expression ad:arrayDimensions) {
            if (ad != null)
               ix = ad.transformTemplate(ix, statefulContext);
         }
      }

      if (arrayInitializer != null)
         ix = arrayInitializer.transformTemplate(ix, statefulContext);

      if (typeArguments != null)
         for (JavaType ta:typeArguments)
            ix = ta.transformTemplate(ix, statefulContext);

      if (classBody != null)
         for (Statement cb:classBody)
            ix = cb.transformTemplate(ix, statefulContext);

      return ix;
   }

   public void addDependentTypes(Set<Object> types) {
      super.addDependentTypes(types);

      if (arrayDimensions != null) {
         for (Expression ad:arrayDimensions) {
            if (ad != null)
               ad.addDependentTypes(types);
         }
      }

      if (boundType != null)
         types.add(boundType);

      if (arrayInitializer != null)
         arrayInitializer.addDependentTypes(types);

      if (typeArguments != null)
         for (JavaType ta:typeArguments)
            ta.addDependentTypes(types);

      if (classBody != null)
         for (Statement cb:classBody)
            cb.addDependentTypes(types);
   }

   public Statement transformToJS() {
      super.transformToJS();

      Expression outerExpr = null;

      int sz;
      if (identifiers != null && (sz = identifiers.size()) > 0) {
         outerExpr = IdentifierExpression.create(getIdentifierPathName(sz));
         setProperty("identifiers", null);
      }

      if (boundTypeName == null && boundType != null && classBody == null)
         boundTypeName = ModelUtil.getTypeName(boundType);

      String typeNameToUse = boundTypeName;

      // If there's a non-static instance outer type, we need to add that implicit argument to the JS arguments.
      Object enclInstType = null;

      if (classBody != null) {

         ClassDeclaration anonType = getAnonymousType(true);

         setProperty("typeIdentifier", typeNameToUse = anonType.getFullTypeName());
         setProperty("classBody", null);

         // The inner type is defined in an instance context, it automatically becomes an inner type of the current type.
         if (!isStaticContext)
            enclInstType = getEnclosingType();
      }

      if (enclInstType == null)
         enclInstType = ModelUtil.getEnclosingInstType(boundType);

      setProperty("typeArguments", null);

      if (arrayInitializer != null) {
         arrayInitializer.transformToJS();
         parentNode.replaceChild(this, arrayInitializer);
         return arrayInitializer;
      }
      else if (arrayDimensions != null) {
         for (Expression ad:arrayDimensions) {
            if (ad != null)
               ad.transformToJS();
         }
         SemanticNodeList args = (SemanticNodeList) arrayDimensions.deepCopy(CopyNormal, null);

         String prefix =  getLayeredSystem().runtimeProcessor.getStaticPrefix(boundType, this);
         args.add(0, IdentifierExpression.create(prefix));
         IdentifierExpression newExpr = IdentifierExpression.createMethodCall(args, "sc_newArray");
         parentNode.replaceChild(this, newExpr);
         return newExpr;
      }
      else {
         setProperty("typeIdentifier", JSUtil.convertTypeName(getLayeredSystem(), typeNameToUse));
      }

      /* We null these out so no need to transformToJS
      if (typeArguments != null)
         for (JavaType ta:typeArguments)
            ta.transformToJS();
      */

      if (arguments != null && boundType != null && enclInstType != null) {
         ArrayList<IString> args = new ArrayList<IString>();
         args.add(new NonKeywordString("this"));

         // What is the current context of 'this'?  Is it the type we need?  If not, we may need to add outer's to get to that type.
         Object enclType = getEnclosingType();

         while (enclType != null && !ModelUtil.isAssignableFrom(enclInstType, enclType)) {
            int ct = ModelUtil.getOuterInstCount(enclType);
            enclType = ModelUtil.getEnclosingType(enclType);
            args.add(PString.toIString("_outer" + (ct == 0 ? "" : ct)));
         }

         if (outerExpr == null)
            outerExpr = IdentifierExpression.create(args.toArray(new IString[args.size()]));
         arguments.add(0, outerExpr);
      }

      if (arguments != null) {
         for (Expression expr:arguments)
            expr.transformToJS();
      }

      // A call to new String(char[] arr) or new String(char[] arr, int start, int end) - needs to be converted so we add a join('') on the array.
      if (boundTypeName != null && boundTypeName.equals("java.lang.String") && arguments != null) {
         int numArgs = arguments.size();
         if (numArgs == 1 || numArgs == 3) {
            Expression firstArg = arguments.get(0);
            Object firstArgType = firstArg.getTypeDeclaration();
            if (ModelUtil.isArray(firstArgType) && ModelUtil.isCharacter(ModelUtil.getArrayComponentType(firstArgType))) {
               SemanticNodeList<Expression> joinArgs = new SemanticNodeList<Expression>();
               joinArgs.add(StringLiteral.create(""));
               VariableSelector joinSel = VariableSelector.create("join", joinArgs);
               VariableSelector spliceSel;
               if (numArgs == 3) {
                  spliceSel = VariableSelector.createArgs("splice", arguments.get(1), arguments.get(2));
                  arguments.remove(2);
                  arguments.remove(1);
                  arguments.set(0, SelectorExpression.create(firstArg, spliceSel, joinSel));
               }
               else {
                  arguments.set(0, SelectorExpression.create(firstArg, joinSel));
               }
            }
         }
      }

      return this;
   }

   public void setBindingInfo(BindingDirection dir, Statement dest, boolean nested) {
      super.setBindingInfo(dir, dest, nested);
      if (arrayInitializer != null)
         arrayInitializer.setBindingInfo(dir, dest, nested);
   }

   /* By default, when cloning NewExpressions, do not create a new enclosing type.  Use the old one.  In JS generation, we are cloning a method which shares the enclosing type - so we end up with two NewExprs, using the same enclosing type which really refer to the same inner type.  Maybe this is not always a great idea though? */
   public NewExpression deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      NewExpression expr = (NewExpression) super.deepCopy(options, oldNewMap);
      if ((options & CopyState) != 0) {
         expr.anonType = anonType;
         expr.anonTypeInited = anonTypeInited;
         expr.anonId = anonId;
         expr.boundTypeName = boundTypeName;
         expr.boundType = boundType;
         expr.anonTypeTransformed = anonTypeTransformed;
      }

      if ((options & CopyInitLevels) != 0) {
         expr.constructor = constructor;
         expr.classPropertyName = classPropertyName;
         expr.isStaticContext = isStaticContext;

      }
      return expr;
   }

   protected String dimsToGenerateString() {
      StringBuilder sb = new StringBuilder();
      if (arrayDimensions != null) {
         sb.append("[");
         int subIx = 0;
         for (Expression expr:arrayDimensions) {
            if (subIx != 0)
               sb.append("][");
            if (expr != null)
               sb.append(expr.toGenerateString());
            subIx++;
         }
         sb.append("]");
      }
      return sb.toString();
   }

   public String toGenerateString() {
      StringBuilder sb = new StringBuilder();
      if (typeIdentifier != null) {
         sb.append("new ");
         sb.append(typeIdentifier);
         ClassType.appendTypeArguments(sb, typeArguments);
         if (arrayDimensions != null)
            sb.append(dimsToGenerateString());
         sb.append(argsToGenerateString(arguments));
         if (arrayInitializer != null) {
            sb.append(arrayInitializer.toGenerateString());
         }
         if (classBody != null) {
            classBody.validateParseNode(false);
            sb.append(classBody.toLanguageString(SCLanguage.getSCLanguage().classBody));
         }
         return sb.toString();
      }
      return super.toGenerateString();
   }

   /** TODO: very similar to ClassValueExpression.suggestCompletions - try to share this code? */
   public int suggestCompletions(String prefix, Object currentType, ExecutionContext ctx, String command, int cursor, Set<String> candidates, Object continuation) {
      String typeName = typeIdentifier;
      if (typeName == null || typeName.length() == 0 || arrayDimensions != null || arrayInitializer != null || classBody != null)
         return -1;

      JavaModel model = getJavaModel();
      if (model == null)
         return -1;

      String pkgName = CTypeUtil.getPackageName(typeName);
      String leafName = typeName;
      if (pkgName != null) {
         leafName = CTypeUtil.getClassName(leafName);
         ModelUtil.suggestTypes(model, pkgName, leafName, candidates, false);
      }

      ModelUtil.suggestTypes(model, prefix, typeName, candidates, true);

      int relPos = -1;

      if (parseNode != null && parseNode.getStartIndex() != -1)
         relPos = parseNode.getStartIndex() + parseNode.lastIndexOf(leafName);
      else
         relPos = command.lastIndexOf(leafName);
      // TODO: Unfortunately IntelliJ wants the candidates string to match the PsiElement which in this case includes the "new "
      // This won't work I think if there's more than one trailing space
      if (candidates.size() > 0) {
         ArrayList<String> newCandidates = new ArrayList<String>();
         for (String cand:candidates)
            newCandidates.add("new " + cand);
         candidates.clear();
         candidates.addAll(newCandidates);
         relPos -= 4;
      }
      return relPos;
   }

   public void addBreakpointNodes(List<ISrcStatement> res, ISrcStatement srcStatement) {
      super.addBreakpointNodes(res, srcStatement);
      if (classBody != null) {
         for (Statement st:classBody) {
            st.addBreakpointNodes(res, srcStatement);
         }
      }
   }

   public void stop() {
      super.stop();

      boundType = null;
      classPropertyName = null;
      boundTypeName = null;
      anonType = null;
      anonTypeTransformed = null;
      anonTypeInited = false;
      anonId = -1;
      constructor = null;
   }

   // Don't let the IdentifierExpression implementation work on new expressions.  It can cause weird problems parsing
   // partial values (see reparseTest/re70)
   public boolean applyPartialValue(Object value) {
      return false;
   }

   public boolean isLineStatement() {
      return true;
   }

   @Override
   public List<Statement> getBodyStatements() {
      return classBody;
   }

   public String addNodeCompletions(JavaModel origModel, JavaSemanticNode origNode, String extMatchPrefix, int offset, String dummyIdentifier, Set<String> candidates) {
      // TODO: for some reason this results in the completion including the 'new' keyword - e.g. "new Foo" rather than just "Foo".  Not sure how to remove that but it's better than it not completing at all.
      if (extMatchPrefix.startsWith("new "))
         extMatchPrefix = extMatchPrefix.substring(4);
      return addStatementNodeCompletions(origModel, origNode, extMatchPrefix, offset, dummyIdentifier, candidates);
   }
}
