/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.dyn.DynUtil;
import sc.lang.*;
import sc.lang.template.GlueStatement;
import sc.layer.BuildInfo;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.obj.IObjectId;
import sc.parser.GenFileLineIndex;
import sc.parser.IParseNode;
import sc.parser.ParseUtil;
import sc.util.StringUtil;

import java.util.*;

public abstract class AbstractMethodDefinition extends TypedDefinition implements IMethodDefinition, INamedNode, IBlockStatementWrapper, IObjectId {
   public String name;
   public Parameter parameters;
   public String arrayDimensions;
   public List<JavaType> throwsTypes;
   public BlockStatement body;
   public List<TypeParameter> typeParameters;

   /** True when this method modifies another method: TODO: rename this to "modifyingMethod"? It's hard to tell which side it reflects now */
   public transient boolean modified = false;
   public transient boolean needsDynInvoke = false;

   /** When this method overrides a method in a downstream layer, the generated name of the method this method has modified so we can remap super calls to this method (_super_x()) */
   public transient String overriddenMethodName = null;
   public transient String overriddenLayer = null;
   public transient Object[] parameterTypes;

   /** A method to be used in place of the previous method - either because it was modified (replaced=false) or a type was reloaded (replaced=true) */
   public transient AbstractMethodDefinition replacedByMethod;
   public transient AbstractMethodDefinition overriddenMethod; // TODO: rename this to "modifiedMethod"
   /** Goes along with replacedByMethod when that is used to indicate a method which actually replaces this instance in this layer */
   public transient boolean replaced;

   transient String origName;
   private transient boolean templateBody;

   /** If this method is used from a remote runtime and does not have the sc.obj.Remote annotation set explicitly, we'll add this annotation so we know it's safe to call this method during deserialization  */
   private transient ArrayList<String> remoteRuntimes;

   public static final String METHOD_TYPE_PREFIX = "_M__";

   private transient int anonMethodId = 0;

   public void init() {
      if (initialized) return;
      super.init();
      if (origName == null)
         origName = name;
      templateBody = body != null && body.statements != null && body.statements.size() == 1 &&
                     body.statements.get(0) instanceof GlueStatement;
   }

   public void process() {
      super.process();
      Object editorSettings = getAnnotation("sc.obj.EditorCreate"); // TODO: need getInheritedAnnotation for methods
      if (editorSettings != null) {
         String constrParamNames = (String) ModelUtil.getAnnotationValue(editorSettings, "constructorParamNames");
         if (constrParamNames != null && constrParamNames.length() > 0) {
            String[] paramNames = StringUtil.split(constrParamNames, ',');
            if (paramNames.length != getNumParameters()) {
               displayError("EditorCreate.constructorParamNames num params does not match: " + paramNames.length + " != " + getNumParameters());
            }
         }
         BodyTypeDeclaration enclType = getEnclosingType();
         if (!isConstructor()) {
            Object returnType = getReturnType(false);
            if (returnType != null && !ModelUtil.isAssignableFrom(returnType, enclType)) {
               displayError("EditorCreate method return type: " + ModelUtil.getTypeName(returnType) + " does not match class type: " + enclType.typeName + " for: ");
            }
         }
         if (enclType != null) {
            LayeredSystem sys = getLayeredSystem();
            BuildInfo bi = sys.buildInfo;
            bi.addTypeGroupMember(enclType.getFullTypeName(),  enclType.getTemplatePathName(), BuildInfo.AllowEditorCreateGroupName);
         }
      }
   }

   private Annotation getRemoteRuntimesAnnotation() {
      return Annotation.create("sc.obj.Remote", "remoteRuntimes", String.join(",",remoteRuntimes));
   }

   public boolean transform(ILanguageModel.RuntimeType type) {
      if (remoteRuntimes != null) {
         // Mark this method so it's accessible from the remote runtime
         addModifier(getRemoteRuntimesAnnotation());
      }
      boolean res = super.transform(type);
      return res;
   }

   public AbstractMethodDefinition resolve(boolean modified) {
      if (modified)
         return resolveDefinition();
      else {
         AbstractMethodDefinition cur = this;
         while (cur != null && cur.replaced)
            cur = cur.replacedByMethod;
         if (cur == null)
            return this;
         return cur;
      }
   }

   public AbstractMethodDefinition resolveDefinition() {
      AbstractMethodDefinition methDef = this, repl;

      do {
         if ((repl = methDef.replacedByMethod) == null)
            return methDef;

         methDef = repl;
      } while (true);
   }

   public Object findMember(String memberName, EnumSet<MemberType> type, Object fromChild, Object refType, TypeContext ctx, boolean skipIfaces) {
      Object v;
      if (type.contains(MemberType.Variable)) {
         if (parameters != null)
            for (Parameter p:parameters.getParameterList())
               if ((v = p.definesMember(memberName, type, refType, ctx, skipIfaces, false)) != null)
                  return v;
      }

      return super.findMember(memberName, type, this, refType, ctx, skipIfaces);
   }

   public Object definesMethod(String methodName, List<?> methParams, ITypeParamContext ctx, Object refType, boolean isTransformed, boolean staticOnly, Object inferredType, List<JavaType> methodTypeArgs) {
      if (name != null && methodName.equals(name)) {
         Object meth = parametersMatch(methParams, ctx, inferredType, methodTypeArgs);
         if (meth != null) {
            // In Java, it's possible to have static and non-static methods with the identical signature.  If we are calling this from a static
            // context, we need to disambiguate them by the context of the caller
            if (staticOnly && !hasModifier("static"))
               return null;
            if (refType == null || ModelUtil.checkAccess(refType, this)) {
               return meth;
            }
         }
      }
      return null;
   }

   public Object parametersMatch(List<? extends Object> otherParams, ITypeParamContext ctx, Object inferredType, List<JavaType> methodTypeArgs) {
      if (parameters == null) {
         return otherParams == null || otherParams.size() == 0 ? this : null;
      }

      List<Parameter> params = parameters.getParameterList();
      int sz = params.size();
      if (otherParams == null)
         return sz == 0 ? this : null;

      int otherSize = otherParams.size();
      int last = sz - 1;
      Parameter lastP = params.get(last);
      boolean repeatingLast = lastP != null && lastP.repeatingParameter;

      if (otherSize != sz) {
         if (last < 0 || !repeatingLast || otherSize < sz-1)
            return null;
      }

      Object[] boundParamTypes = null;
      ParamTypedMethod paramMethod = null;

      if (ModelUtil.isParameterizedMethod(this)) {
         paramMethod = new ParamTypedMethod(getLayeredSystem(), this, ctx, getEnclosingType(), otherParams, inferredType, methodTypeArgs);
         boundParamTypes = paramMethod.getParameterTypes(true);
         if (paramMethod.invalidTypeParameter)
            return null;
      }

      for (int i = 0; i < otherSize; i++) {
         Object otherP = otherParams.get(i);
         Parameter thisP;
         Object thisType;
         // Must be a repeating parameter because of the test above
         int thisParamIx;
         if (i >= sz) {
            thisParamIx = last;
         }
         else {
            thisParamIx = i;
         }

         thisP = params.get(thisParamIx);
         thisType = boundParamTypes == null ? thisP.getTypeDeclaration() : boundParamTypes[thisParamIx];

         if (otherP instanceof Expression) {
            Object paramType = thisType;
            if (repeatingLast && i >= last && ModelUtil.isArray(paramType)) {
               paramType = ModelUtil.getArrayComponentType(paramType);
            }
            if (paramType instanceof ParamTypeDeclaration)
               paramType = ((ParamTypeDeclaration) paramType).cloneForNewTypes();
            ((Expression) otherP).setInferredType(paramType, false);
         }

         if (otherP instanceof ITypedObject)
             otherP = ((ITypedObject) otherP).getTypeDeclaration();

         if (otherP instanceof BaseLambdaExpression.LambdaInvalidType)
            return null;

         // If it's an unbound lambda expression, we still need to do some basic checks to see if this one is a match.
         if (otherP instanceof BaseLambdaExpression.LambdaInferredType) {
            BaseLambdaExpression.LambdaInferredType lambdaType = (BaseLambdaExpression.LambdaInferredType) otherP;
            if (!lambdaType.rootExpr.lambdaParametersMatch(thisType, thisP.repeatingParameter, null))
               return null;
         }

         // Null entry means match
         if (otherP == null || thisP == null)
            continue;

         // Take a conservative approach... if types are not available, just match
         boolean allowUnbound = otherP instanceof ParamTypeDeclaration && ((ParamTypeDeclaration) otherP).unboundInferredType;
         if (thisType != null && thisType != otherP && !ModelUtil.isAssignableFrom(thisType, otherP, false, ctx, allowUnbound, getLayeredSystem())) {
            if (i >= last && repeatingLast) {
               if (!ModelUtil.isAssignableFrom(ModelUtil.getArrayComponentType(thisType), otherP, false, ctx, getLayeredSystem())) {
                  return null;
               }
            }
            else
               return null;
         }
      }
      return paramMethod == null ? this : paramMethod;
   }

   public Object findType(String typeName, Object refType, TypeContext ctx) {
      if (typeParameters != null)
         for (TypeParameter tp:typeParameters)
            if (tp.name.equals(typeName))
               return tp;

      Object res;
      if ((res = definesType(typeName, ctx)) != null) {
         return res;
      }
      return super.findType(typeName, refType, ctx);
   }

   public Object definesType(String name, TypeContext ctx) {
      // No classes have been defined in this method yet so no possible matches
      if (anonMethodId == 0)
         return null;
      /** We can look up a specific local method's class name using the special method type prefix */
      if (name.startsWith(METHOD_TYPE_PREFIX)) {
         int prefLen = METHOD_TYPE_PREFIX.length();
         int i;
         for (i = prefLen + 1; i < name.length() && name.charAt(i) >= '0' && name.charAt(i) <= '9'; i++) {
         }
         int endIdIx = i;
         if (endIdIx == -1)
            return null;
         String idStr = name.substring(prefLen, endIdIx);
         try {
            int id = Integer.parseInt(idStr);
            if (id == anonMethodId)
               name = name.substring(endIdIx);
         }
         catch (NumberFormatException exc) {}
      }
      if (body != null) {
         List<Statement> statements = body.statements;
         if (statements != null) {
            for (Statement st:statements) {
               if (st instanceof TypeDeclaration) {
                  TypeDeclaration td = (TypeDeclaration) st;
                  if (td.typeName != null && td.typeName.equals(name))
                     return td;
               }
            }
         }
      }
      return null;
   }

   public Definition modifyDefinition(BodyTypeDeclaration base, boolean doMerge, boolean inTransformed) {
      assert origName != null;

      // Use origName here - if we were overridden, we still want to find and override the same method
      AbstractMethodDefinition overridden = base.declaresMethodDef(origName, getParameterList());

      Layer baseLayer = base.getLayer();
      if (!modified && overridden != null) {
         overriddenLayer = baseLayer.getLayerUniqueName();

         // Marks that this method is modifying a base method in the same class in this operation.  This means that any super definition in this
         // method needs to be remapped.  We simulate super because the modify operation does not actually create
         // a new Java class.  This is set to true even if we do not override a method since our "super" references
         // need to get rewritten due to the elimination of that class.
         modified = true;
      }

      boolean isOverrideMethod = this instanceof MethodDefinition && ((MethodDefinition) this).override;

      if (!isOverrideMethod) {
         if (overridden != null) {
            boolean overriddenIsAbstract = overridden.hasModifier("abstract");
            TypeDeclaration overEnclType = overridden.getEnclosingType();
            if (overriddenIsAbstract) {
               overEnclType.removeStatement(overridden);
            }
            else {
               boolean overriddenIsStatic = overridden.hasModifier("static");
               boolean thisIsStatic = hasModifier("static");
               if (!overriddenIsStatic == thisIsStatic) {
                  if (overriddenIsStatic)
                     addModifier("static");
                  else
                     displayError("Cannot make an instance method static in a derived layer: ");
               }

               boolean doOverride = true;

               if (overridden instanceof ConstructorDefinition) {
                  ConstructorDefinition overConst = (ConstructorDefinition) overridden;
                  if (overConst.body != null) {
                     BlockStatement constrBody = overConst.body;
                     SemanticNodeList<Statement> sts = constrBody.statements;
                     if (sts != null && sts.size() > 0) {
                        Statement st = sts.get(0);
                        // First check for any super that's alone in the method
                        if (st.callsSuper(false)) {
                           if (sts.size() == 1) {
                              // If it's only super(x) then this method can just be removed
                              overConst.parentNode.removeChild(overConst);
                              doOverride = false;
                              modified = false; // We are no longer modifying another method - so clear this flag
                           }
                           // Only if it will turn into a real super() in the code do we move it though...
                           else if (st.callsSuper(true)) {
                              sts.remove(0);
                              TypeDeclaration enclType = getEnclosingType();
                              AbstractMethodDefinition lastModMeth;
                              if (enclType == null)
                                 lastModMeth = this;
                              else
                                 lastModMeth = enclType.declaresMethodDef(origName, getParameterList());

                              // TODO: need to change the names of the parameters if they are different
                              lastModMeth.body.addStatementAt(0, st);
                              st.markFixedSuper();
                           }
                        }
                     }
                  }
               }

               if (doOverride) {
                  // Needs to be unique per layer, per-type  TODO: is there a shorter way to make this unique?
                  overriddenMethodName = "_super_" + overEnclType.getLayer().getLayerUniqueName().replace('.', '_') + "_" + overEnclType.getInnerTypeName().replace('.', '_') + '_' + origName;

                  overrides = overridden;

                  /* Constructors need to be void so we need to change them to a method definition */
                  if (overridden instanceof ConstructorDefinition) {
                     ((ConstructorDefinition) overridden).convertToMethod(overriddenMethodName);
                  }
                  else
                     overridden.setProperty("name", overriddenMethodName);
               }
            }
         }

         base.addBodyStatementIndent(this);
      }
      else {
         if (overridden != null) {
            overridden.mergeModifiers(this, false, true);
         }
      }
      return this;
   }

   private final static List<Parameter> EMPTY_PARAMETER_LIST = Collections.emptyList();

   public List<Parameter> getParameterList() {
      if (parameters == null) return EMPTY_PARAMETER_LIST;
      return parameters.getParameterList();
   }

   public int getNumParameters() {
     return parameters == null ? 0 : parameters.getNumParameters();
   }

   public void initBody() {
      if (body == null)
         setProperty("body", new BlockStatement());
      if (body.statements == null)
         body.setProperty("statements", new SemanticNodeList());
   }

   public void addStatementsAt(int position, List<Statement> statements) {
      initBody();
      for (int i = 0; i < statements.size(); i++) {
         Statement st = statements.get(i);
         body.statements.add(i+position, st);
      }
   }

   public void addStatementAt(int position, Statement statement) {
      initBody();
      body.statements.add(position, statement);
   }

   public void addStatement(Statement statement) {
      initBody();
      body.statements.add(statement);
   }

   public void addStatementIndent(Statement statement) {
      initBody();
      TransformUtil.appendIndentIfNecessary(body.statements);
      body.statements.add(statement);
   }

   public boolean isDynMethod() {
      BodyTypeDeclaration bte;
      return (bte = getEnclosingType()) != null && bte.isDynamicType();
   }

   public Object invoke(ExecutionContext ctx, List<Object> paramValues) {
      if (!isValidated())
         ParseUtil.initAndStartComponent(this);

      if (replaced) {
         // We've been physically replaced in the model by another method.
         if (replacedByMethod != null)
             return resolveDefinition().invoke(ctx, paramValues);
         else {
            System.err.println("*** invoking method which was removed: " + this);
            throw new IllegalArgumentException("Method: " + name + " invoked after it was deleted");
         }
      }

      if (body == null)
         return null;

      int methodSize = templateBody ? 1 : 0;
      ctx.pushFrame(true, body.frameSize + methodSize, paramValues, parameters, getEnclosingType());

      // TODO: put in a configurable stack size - or just let the runtime throw a stack overflow exception?
      if (ctx.getFrameSize() > 500)
         System.out.println("*** Warning - stack overflow?");

      try {
         ExecResult res;
         if (templateBody) {
            StringBuilder sb = new StringBuilder();
            ctx.defineVariable("out", sb);
            res = ModelUtil.execStatements(ctx, body.statements);
            return sb.toString(); // TODO: cast to the return type of the method
         }
         else {
            res = ModelUtil.execStatements(ctx, body.statements);
         }

         if (res == ExecResult.Return) {
            return ctx.currentReturnValue;
         }

         if (res != ExecResult.Next)
            System.err.println("*** Dynamic runtime error: attempt to: " + res + " out of method: " + origName);
      }
      finally {
         ctx.popFrame();
      }
      return null;
   }


   /** Calls this method externally with the given this */
   public Object callVirtual(Object thisObj, Object... values) {
      if (thisObj == null || hasModifier("static"))
         return call(thisObj, values);
      Object type = DynUtil.getType(thisObj);
      Object method;
      method = ModelUtil.getMethodFromSignature(type, name, getTypeSignature(), true);
      if (method == null)
         throw new UnsupportedOperationException();
      if (method == this)
         return call(thisObj, values);
      return ModelUtil.callMethod(thisObj, method, values);
   }

   /** Calls this method externally with the given this */
   public Object call(Object thisObj, Object... values) {
      ExecutionContext ctx = new ExecutionContext(getJavaModel());
      if (thisObj != null)
         ctx.pushCurrentObject(thisObj);
      else {
         BodyTypeDeclaration enclType = getEnclosingType();
         enclType.staticInit();
      }
      try {
         return invoke(ctx, Arrays.asList(values));
      }
      finally {
         if (thisObj != null)
            ctx.popCurrentObject();
      }
   }

   public Object callStatic(Object thisType, Object... values) {
      ExecutionContext ctx = new ExecutionContext(getJavaModel());

      if (thisType instanceof BodyTypeDeclaration)
         ((BodyTypeDeclaration) thisType).staticInit();

      ctx.pushStaticFrame(thisType);
      try {
         return invoke(ctx, Arrays.asList(values));
      }
      finally {
         ctx.popStaticFrame();
      }
   }

   public Object getDeclaringType() {
      return getEnclosingType();
   }

   /** Turn this on unless we are in an interface or annotation type */
   public boolean useDefaultModifier() {
      TypeDeclaration enclType = getEnclosingType();
      return enclType != null && enclType.useDefaultModifier();
   }

   public String toListDisplayString() {
      StringBuilder sb = new StringBuilder();
      sb.append(toMethodDeclString());
      if (type != null) {
         sb.append(": ");
         sb.append(type.toLanguageString(SCLanguage.getSCLanguage().type));
      }
      return sb.toString();
   }

   private CharSequence toMethodDeclString() {
      StringBuilder sb = new StringBuilder();
      sb.append(name);
      sb.append(getParameterString());
      return sb;
   }

   public String getParameterString() {
      StringBuilder sb = new StringBuilder();
      if (parameters != null && parameters.getNumParameters() > 0) {
         //sb.append("("); part of language string
         sb.append(parameters.toLanguageString(SCLanguage.getSCLanguage().parameters));
         //sb.append(")"); part of language string?
      }
      else
         sb.append("()");
      return sb.toString();
   }

   public String toDeclarationString() {
      StringBuilder sb = new StringBuilder();
      if (modifiers != null && modifiers.size() > 0) {
         sb.append(modifiers.toLanguageString(SCLanguage.getSCLanguage().modifiers));
      }
      if (type != null) {
         if (sb.length() != 0 && sb.charAt(sb.length()-1) != ' ')
            sb.append(" ");
         sb.append(type.toLanguageString(SCLanguage.getSCLanguage().type));
      }
      if (sb.length() != 0 && sb.charAt(sb.length()-1) != ' ')
         sb.append(" ");
      sb.append(toMethodDeclString());
      // Do not convert NewExpression's to anon types in toString()
      BodyTypeDeclaration enclType = getStructuralEnclosingType();
      if (enclType != null) {
         sb.append(" in " );
         sb.append(enclType.typeName);
         Layer l = enclType.getLayer();
         if (l != null) {
            sb.append(" layer: ");
            sb.append(l.layerDirName);
         }
      }
      return sb.toString();
   }

   public String getThrowsClause() {
      return ModelUtil.typesToThrowsClause(getExceptionTypes());
   }

   public Object getRuntimeMethod() {
      // Use the enclosing type's idDynamictype flag, not the methods.  The method may be overridden - and dynamic for
      // external clients.  This determines how we use the method internally... if the type is compiled, the method is
      // compiled.
      if (isDynMethod())
         return this;
      Object compiledClass = ModelUtil.getCompiledClass(getDeclaringType());
      if (compiledClass == null) {
         System.err.println("*** No compiled class for: " + getDeclaringType());
      }
      Object res = ModelUtil.definesMethod(compiledClass, name, getParameterList(), null, null, false, false, null, null, getLayeredSystem());
      if (res == null) {
         System.err.println("*** No runtime method for: " + name);
         boolean x = isDynMethod();
         Object y = ModelUtil.definesMethod(compiledClass, name, getParameterList(), null, null, false, false, null, null, getLayeredSystem());
      }
      return res;
   }

   public Object[] getParameterTypes(boolean bound) {
      if (parameterTypes == null && parameters != null && parameters.getNumParameters() > 0) {
         parameterTypes = parameters.getParameterTypes();
      }
      return parameterTypes;
   }

   public String getMethodName() {
      return name;
   }

   public void addBodyStatementAt(int i, Statement superSt) {
      if (body == null)
         setProperty("body", new BlockStatement());
      body.addStatementAt(i, superSt);
   }

   public void setExceptionTypes(Object[] types) {
      if (types == null || types.length == 0)
         return;

      SemanticNodeList newTypes = new SemanticNodeList(types.length);
      for (Object type:types) {
         newTypes.add(JavaType.createJavaTypeFromName(ModelUtil.getTypeName(type, true)));
      }
      setProperty("throwsTypes", newTypes);
   }

   public JavaType[] getExceptionTypes() {
      if (throwsTypes == null)
         return null;
      return throwsTypes.toArray(new JavaType[throwsTypes.size()]);
   }

   public String getTypeSignature() {
      int num;
      if (parameters != null && (num = parameters.getNumParameters()) != 0) {
         List<Parameter> paramList = parameters.getParameterList();
         StringBuilder sb = new StringBuilder();
         for (int i = 0; i < num; i++) {
            Parameter param = paramList.get(i);
            // Repeating parameters have the array-like signature
            if (param.repeatingParameter)
               sb.append("[");
            sb.append(param.type.getSignature(true));
         }
         return sb.toString();
      }
      return null;
   }

   public String toString() {
      if (debugDisablePrettyToString)
         return toModelString();
      return toDeclarationString();
   }

   public boolean callsSuper(boolean checkModSuper) {
      BlockStatement bd = body;
      if (bd == null || bd.statements == null)
         return false;
      for (Statement st:bd.statements)
         if (st.callsSuper(checkModSuper))
            return true;
      return false;
   }

   public boolean callsThis() {
      BlockStatement bd = body;
      if (bd == null || bd.statements == null)
         return false;
      for (Statement st:bd.statements)
         if (st.callsThis())
            return true;
      return false;
   }

   public void markFixedSuper() {
      BlockStatement bd = body;
      if (bd == null || bd.statements == null)
         return;
      for (Statement st:bd.statements)
         st.markFixedSuper();
   }

   public Expression[] getConstrArgs() {
      BlockStatement bd = body;
      if (bd == null || bd.statements == null)
         return null;
      Expression[] res;
      for (Statement st:bd.statements)
         if ((res = st.getConstrArgs()) != null)
            return res;
      return null;
   }

   public boolean isVarArgs() {
      int num;
      return parameters != null && (num = parameters.getNumParameters()) > 0 && parameters.getParameterList().get(num-1).repeatingParameter;
   }

   public boolean isStatic() {
      return hasModifier("static");
   }

   public Statement transformToJS() {
      super.transformToJS();
      if (parameters != null)
         parameters.transformToJS();
      if (throwsTypes != null)
         for (JavaType t:throwsTypes)
            t.transformToJS();

      if (body != null)
         body.transformToJS();

      // TODO: clear type parameters?

      return this;
   }

   public boolean refreshBoundTypes(int flags) {
      boolean res = super.refreshBoundTypes(flags);
      if (parameters != null)
         parameters.refreshBoundType(flags);
      if (throwsTypes != null)
         for (JavaType t:throwsTypes)
            t.refreshBoundType(flags);

      if (body != null) {
         if (body.refreshBoundTypes(flags))
            res = true;
      }


      if (typeParameters != null)
         for (TypeParameter tp:typeParameters)
            tp.refreshBoundType(flags);

      return res;
   }

   public int transformTemplate(int ix, boolean statefulContext) {
      ix = super.transformTemplate(ix, statefulContext);
      if (parameters != null)
         ix = parameters.transformTemplate(ix, statefulContext);
      if (throwsTypes != null)
         for (JavaType t:throwsTypes)
            ix = t.transformTemplate(ix, statefulContext);

      if (body != null)
         ix = body.transformTemplate(ix, statefulContext);

      if (typeParameters != null)
         for (TypeParameter tp:typeParameters)
            ix = tp.transformTemplate(ix, statefulContext);
      return ix;
   }

   public void addDependentTypes(Set<Object> types, DepTypeCtx ctx) {
      super.addDependentTypes(types, ctx);
      if (parameters != null)
         parameters.addDependentTypes(types, ctx);
      if (throwsTypes != null)
         for (JavaType t:throwsTypes)
            t.addDependentTypes(types, ctx);

      if (body != null)
         body.addDependentTypes(types, ctx);

      if (typeParameters != null)
         for (TypeParameter tp:typeParameters)
            tp.addDependentTypes(types, ctx);
      // Any remote methods?  If so, the parent type needs to be in the sync type filter so we can
      // send replies to the method calls.
      if (ctx.mode == DepTypeMode.RemoteMethodTypes && remoteRuntimes != null) {
         TypeDeclaration enclType = getEnclosingType();
         if (enclType != null)
            types.add(enclType);
      }
   }

   public void setAccessTimeForRefs(long time) {
      super.setAccessTimeForRefs(time);
      if (parameters != null) {
         parameters.setAccessTimeForRefs(time);
      }
      if (throwsTypes != null)
         for (JavaType t:throwsTypes)
            t.setAccessTimeForRefs(time);

      if (body != null)
         body.setAccessTimeForRefs(time);
   }

   public JavaType[] getParameterJavaTypes(boolean convertRepeating) {
      return parameters == null ? null : parameters.getParameterJavaTypes(convertRepeating);
   }


   public JavaType getReturnJavaType() {
      return type;
   }

   public boolean getNeedsDynInvoke() {
      return needsDynInvoke || (replacedByMethod != null && replacedByMethod.needsDynInvoke) || (overriddenMethod != null && overriddenMethod.needsDynInvoke);
   }

   public AbstractMethodDefinition deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      AbstractMethodDefinition res = (AbstractMethodDefinition) super.deepCopy(options, oldNewMap);

      if ((options & CopyInitLevels) != 0) {
         res.modified = modified;
         res.needsDynInvoke = needsDynInvoke;

         /** When overriding a method in a layer, the name of the method this method has modified */
         res.overriddenMethodName = overriddenMethodName;
         res.overriddenLayer = overriddenLayer;
         res.parameterTypes = parameterTypes;

         /** A method to be used in place of the previous method - either because it was modified (replaced=false) or a type was reloaded (replaced=true) */
         res.replacedByMethod = replacedByMethod;
         res.overriddenMethod = overriddenMethod;
         /** Goes along with replacedByMethod when that is used to indicate a method which actually replaces this instance in this layer */
         res.replaced = replaced;

         res.origName = origName;
         res.templateBody = templateBody;
         res.remoteRuntimes = remoteRuntimes;
      }
      return res;
   }

   public void setNodeName(String newName) {
      setProperty("name", newName);
   }

   public String getNodeName() {
      return this.name;
   }

   public AbstractMethodDefinition refreshNode() {
      if (replaced)
         return replacedByMethod.refreshNode();

      // Or we can look our replacement up...
      TypeDeclaration enclType = getEnclosingType();
      BodyTypeDeclaration newType = enclType == null ? null : enclType.refreshNode();
      if (newType == null) {
         System.err.println("*** Unable to refresh method enclosing type: " + (enclType == null ? " no enclosing type" : enclType.typeName));
         return this;
      }
      return newType.declaresMethodDef(name, getParameterList());
   }

   public void addBreakpointNodes(List<ISrcStatement> res, ISrcStatement toFind) {
      super.addBreakpointNodes(res, toFind);
      if (body != null)
         body.addBreakpointNodes(res, toFind);
   }

   public boolean updateFromStatementRef(Statement fromSt, ISrcStatement defaultSt) {
      super.updateFromStatementRef(null, defaultSt);
      if (body == null)
         return false;
      return body.updateFromStatementRef(fromSt, defaultSt);
   }

   public boolean needsEnclosingClass() {
      return true;
   }

   public Object[] getMethodTypeParameters() {
      if (typeParameters == null)
         return null;
      return typeParameters.toArray();
   }

   public void stop() {
      super.stop();
      parameterTypes = null;
      overriddenMethod = null;
      modified = false;
      overriddenMethodName = null;
      overriddenLayer = null;
   }

   public String getInnerTypeName() {
      if (anonMethodId == 0) {
         TypeDeclaration enclType = getEnclosingType();
         if (enclType != null)
            anonMethodId = enclType.allocateAnonMethodId();
         else
            anonMethodId = 123; // Don't think is really used when this is a fragment not in a type
      }
      // Java names these just parentType$1InnerType
      return METHOD_TYPE_PREFIX + anonMethodId;
   }

   public Statement findStatement(Statement in) {
      if (body != null) {
         Statement out = body.findStatement(in);
         if (out != null)
            return out;
      }
      return super.findStatement(in);
   }

   public boolean isLeafStatement() {
      return false;
   }

   public boolean isLineStatement() {
      return true; // For JS we are returning true here so we can register the method's first line to use for the classInit call... TODO: for Java, should this be the last line though?
   }

   public int getNumStatementLines() {
      return 1;
   }

   public BlockStatement getWrappedBlockStatement() {
      return body;
   }

   public void setFromStatement(ISrcStatement from) {
      fromStatement = from;
      if (body != null)
         body.setFromStatement(from);
   }

   public void addRemoteRuntime(String remoteRuntimeName) {
      if (transformed) {
         System.err.println("*** Unable to add remote runtime to transformed method");
         return;
      }
      if (!ModelUtil.isRemoteMethod(getLayeredSystem(), this)) {
         if (remoteRuntimes == null)
            remoteRuntimes = new ArrayList<String>();
         if (!remoteRuntimes.contains(remoteRuntimeName))
            remoteRuntimes.add(remoteRuntimeName);
      }
   }

   /**
    * Use the default implementation to register the method declaration line.  After that, we add one for the final close brace - where
    * Java stops before exiting the method.
    */
   public void addToFileLineIndex(GenFileLineIndex idx, int rootStartGenLine) {
      super.addToFileLineIndex(idx, rootStartGenLine);
      ISrcStatement srcStatement = getSrcStatement(null);
      if (body != null && srcStatement != null && srcStatement != this && srcStatement instanceof AbstractMethodDefinition) {
         AbstractMethodDefinition srcMeth = (AbstractMethodDefinition) srcStatement;
         if (srcMeth.body == null)
            return;
         ISemanticNode srcRoot = srcStatement.getRootNode();
         if (srcRoot instanceof JavaModel) {
            JavaModel srcModel = (JavaModel) srcRoot;
            if (srcModel.getParseNode() == null) {
               srcModel.restoreParseNode();
            }
            IParseNode genBodyPN = body.getParseNode();
            int startGenOffset = genBodyPN.getStartIndex() + genBodyPN.lastIndexOf("}");
            if (startGenOffset == -1) {
               System.out.println("*** Attempt to add statement to genLine index with a parse-node that's missing a startIndex");
               return;
            }
            int startGenLine;
            if (rootStartGenLine == -1) {
               startGenLine = idx.genFileLineIndex.getLineForOffset(startGenOffset);
            }
            else {
               CharSequence cur = idx.currentStatement;
               startGenLine = rootStartGenLine + ParseUtil.countCodeLinesInNode(cur, startGenOffset+1);
            }
            int endGenLine = startGenLine + 1;

            IParseNode srcBodyPN = srcMeth.body.getParseNode();
            int startSrcOffset = srcBodyPN.getStartIndex() + srcBodyPN.lastIndexOf("}");

            //addMappingForSrcStatement(idx, srcModel, srcStatement, startGenLine, endGenLine);
            int startSrcLine = idx.getSrcFileIndex(srcModel.getSrcFile()).srcFileLineIndex.getLineForOffset(startSrcOffset);
            int endSrcLine = startSrcLine + 1;

            idx.addMapping(srcModel.getSrcFile(), startGenLine, endGenLine, startSrcLine, endSrcLine);
         }
         else {
            System.err.println("*** Unrecognized root node for src statement in generated model");
         }
      }
   }

   public boolean isIncompleteStatement() {
      if (body != null)
         return false; // TODO: When we have a body this incorrectly reports errors because we fail to clear the 'errorNode' flag during a reparse... possibly need to restrict the cases where we propagate the errorNode flag down the tree?
      return false; // TODO: It seems like we should be able to use the super here but it reports false errors - the result is that we lose the nicer placement of the incomplete error message for a missing ;
      //return super.isIncompleteStatement();
   }

   // Used for serializing a metadata model from this instance
   public String getMethodTypeName() {
      return getEnclosingType().getFullTypeName();
   }

   public void setMethodTypeName(String mtn) {
      throw new UnsupportedOperationException();
   }

   // Must stay in sync with the client version
   public String getObjectId() {
      String methName = name == null ? "_init_" : name;
      return DynUtil.getObjectId(this, null, "MMD_" + getMethodTypeName()  + "_" + methName);
   }

   public Object getAnnotation(String annotationName) {
      Object res = super.getAnnotation(annotationName);
      if (res != null)
         return res;
      if (remoteRuntimes != null && annotationName.equals("sc.obj.Remote")) {
         return getRemoteRuntimesAnnotation();
      }
      return null;
   }

   public List<Object> getRepeatingAnnotation(String annotationName) {
      List<Object> res = super.getRepeatingAnnotation(annotationName);
      if (res != null)
         return res;
      return null;
   }
}
