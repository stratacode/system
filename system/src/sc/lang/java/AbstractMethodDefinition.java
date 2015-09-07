/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.dyn.DynUtil;
import sc.lang.INamedNode;
import sc.lang.ISrcStatement;
import sc.lang.SCLanguage;
import sc.lang.SemanticNodeList;
import sc.lang.template.GlueStatement;
import sc.layer.Layer;
import sc.parser.ParseUtil;

import java.util.*;

public abstract class AbstractMethodDefinition extends TypedDefinition implements IMethodDefinition, INamedNode {
   public String name;
   public Parameter parameters;
   public String arrayDimensions;
   public List<JavaType> throwsTypes;
   public BlockStatement body;
   public List<TypeParameter> typeParameters;

   public transient boolean modified = false;
   public transient boolean needsDynInvoke = false;

   /** When overriding a method in a layer, the name of the method this method has modified */
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

   public void init() {
      if (initialized) return;
      super.init();
      origName = name;
      templateBody = body != null && body.statements != null && body.statements.size() == 1 &&
                     body.statements.get(0) instanceof GlueStatement;
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

   public Object definesMethod(String methodName, List<?> methParams, ITypeParamContext ctx, Object refType, boolean isTransformed, boolean staticOnly) {
      if (name != null && methodName.equals(name)) {
         if (parametersMatch(methParams, ctx)) {
            // In Java, it's possible to have static and non-static methods with the identical signature.  If we are calling this from a static
            // context, we need to disambiguate them by the context of the caller
            if (staticOnly && !hasModifier("static"))
               return null;
            if (refType == null || ModelUtil.checkAccess(refType, this)) {
               if (typeParameters != null)
                  return new ParamTypedMethod(this, ctx, getEnclosingType(), methParams);
               return this;
            }
         }
      }
      return null;
   }

   public boolean parametersMatch(List<? extends Object> otherParams, ITypeParamContext ctx) {
      if (parameters == null)
         return otherParams == null || otherParams.size() == 0;
      List<Parameter> params = parameters.getParameterList();
      int sz = params.size();
      if (otherParams == null)
         return sz == 0;

      int otherSize = otherParams.size();
      int last = sz - 1;
      Parameter lastP = params.get(last);
      boolean repeatingLast = lastP != null && lastP.repeatingParameter;

      if (otherSize != sz) {
         if (last < 0 || !repeatingLast || otherSize < sz-1)
            return false;
      }

      for (int i = 0; i < otherSize; i++) {
         Object otherP = otherParams.get(i);
         Parameter thisP;
         Object thisType;
         // Must be a repeating parameter because of the test above
         if (i >= sz) {
            thisP = params.get(last);
         }
         else {
            thisP = params.get(i);
         }
         thisType = thisP.getTypeDeclaration();

         if (otherP instanceof ITypedObject)
             otherP = ((ITypedObject) otherP).getTypeDeclaration();

         // Null entry means match
         if (otherP == null || thisP == null || otherP instanceof BaseLambdaExpression.LambdaInferredType)
            continue;

         // Take a conservative approach... if types are not available, just match
         boolean allowUnbound = otherP instanceof ParamTypeDeclaration && ((ParamTypeDeclaration) otherP).unboundInferredType;
         if (thisType != null && thisType != otherP && !ModelUtil.isAssignableFrom(thisType, otherP, false, ctx, allowUnbound)) {
            if (i >= last && repeatingLast) {
               if (!ModelUtil.isAssignableFrom(ModelUtil.getArrayComponentType(thisType), otherP, false, ctx)) {
                  return false;
               }
            }
            else
               return false;
         }
      }
      return true;
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
      if (body != null) {
         List<Statement> statements = body.statements;
         if (statements != null) {
            for (Statement st:statements) {
               if (st instanceof TypeDeclaration) {
                  TypeDeclaration td = (TypeDeclaration) st;
                  if (td.typeName.equals(name))
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

         // Marks that this method was modified in this operation.  This means that any super definition in this
         // method needs to be remapped.  We simulate super because the modify operation does not actually create
         // a new Java class.  This is set to true even if we do not override a method since our "super" references
         // need to get rewritten due to the elimination of that class.
         modified = true;
      }

      if (overridden != null) {
         boolean overriddenIsAbstract = overridden.hasModifier("abstract");
         TypeDeclaration enclType = overridden.getEnclosingType();
         if (overriddenIsAbstract) {
            enclType.removeStatement(overridden);
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
            // Needs to be unique per layer, per-type  TODO: is there a shorter way to make this unique?
            overriddenMethodName = "_super_" + enclType.getLayer().getLayerUniqueName().replace('.','_') + "_" + enclType.getInnerTypeName().replace('.', '_') + '_' +  origName;

            overrides = overridden;

            /* Constructors need to be void so we need to change them to a method definition */
            if (overridden instanceof ConstructorDefinition) {
               ((ConstructorDefinition) overridden).convertToMethod(overriddenMethodName);
            }
            else
               overridden.setProperty("name", overriddenMethodName);
         }
      }

      base.addBodyStatementIndent(this);
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
      if (parameters != null && parameters.getNumParameters() > 0) {
         sb.append("(");
         sb.append(parameters.toLanguageString(SCLanguage.getSCLanguage().parameters));
         sb.append(")");
      }
      else
         sb.append("()");
      return sb;
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
      Object res = ModelUtil.definesMethod(compiledClass, name, getParameterList(), null, null, false, false);
      if (res == null) {
         System.err.println("*** No runtime method for: " + name);
         boolean x = isDynMethod();
         Object y = ModelUtil.definesMethod(compiledClass, name, getParameterList(), null, null, false, false);
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
         for (int i = 0; i < num; i++)
            sb.append(paramList.get(i).type.getSignature());
         return sb.toString();
      }
      return null;
   }

   public String toString() {
      if (debugDisablePrettyToString)
         return toModelString();
      return toDeclarationString();
   }

   public boolean callsSuper() {
      BlockStatement bd = body;
      if (bd == null || bd.statements == null)
         return false;
      for (Statement st:bd.statements)
         if (st.callsSuper())
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

   public void refreshBoundTypes(int flags) {
      super.refreshBoundTypes(flags);
      if (parameters != null)
         parameters.refreshBoundType(flags);
      if (throwsTypes != null)
         for (JavaType t:throwsTypes)
            t.refreshBoundType(flags);

      if (body != null)
         body.refreshBoundTypes(flags);

      if (typeParameters != null)
         for (TypeParameter tp:typeParameters)
            tp.refreshBoundType(flags);
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

   public void addDependentTypes(Set<Object> types) {
      super.addDependentTypes(types);
      if (parameters != null)
         parameters.addDependentTypes(types);
      if (throwsTypes != null)
         for (JavaType t:throwsTypes)
            t.addDependentTypes(types);

      if (body != null)
         body.addDependentTypes(types);

      if (typeParameters != null)
         for (TypeParameter tp:typeParameters)
            tp.addDependentTypes(types);
   }

   public JavaType[] getParameterJavaTypes() {
      return parameters == null ? null : parameters.getParameterJavaTypes();
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
      JavaModel oldModel = getJavaModel();
      if (!oldModel.removed)
         return this; // We are still valid
      if (replaced)
         return replacedByMethod.refreshNode();
      // Or we can look our replacement up...
      Object newType = oldModel.layeredSystem.getSrcTypeDeclaration(getEnclosingType().getFullTypeName(), null, true,  false, false, oldModel.getLayer(), oldModel.isLayerModel);
      if (newType instanceof BodyTypeDeclaration) {
         return ((BodyTypeDeclaration) newType).declaresMethodDef(name, getParameterList());
      }
      displayError("Method ", name, " removed for ");
      return null;
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
   }
}
