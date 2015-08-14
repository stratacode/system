/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.dyn.DynUtil;
import sc.lang.ILanguageModel;
import sc.lang.ISrcStatement;
import sc.lang.SemanticNodeList;
import sc.lang.js.JSFormatMode;
import sc.lang.js.JSRuntimeProcessor;
import sc.lang.js.JSUtil;
import sc.layer.LayeredSystem;
import sc.obj.ScopeDefinition;
import sc.parser.IString;
import sc.parser.PString;
import sc.sync.SyncManager;
import sc.type.CTypeUtil;
import sc.type.Type;
import sc.type.TypeUtil;
import sc.util.StringUtil;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class FieldDefinition extends TypedDefinition {
   public SemanticNodeList<VariableDefinition> variableDefinitions;

   private transient boolean frozenStatic;
   //private transient Object frozenType;

   public static FieldDefinition create(Object type, String fieldName, String op, Expression init) {
      return createFromJavaType(JavaType.createJavaType(type), fieldName, op, init);
   }

   public static FieldDefinition create(Object type, String fieldName) {
      return createFromJavaType(JavaType.createJavaType(type), fieldName);
   }

   public static FieldDefinition createFromJavaType(JavaType type, String fieldName) {
      return createFromJavaType(type, fieldName, null, null);
   }

   public static FieldDefinition createFromJavaType(JavaType type, String fieldName, String op, Expression initializer) {
      FieldDefinition fd = new FieldDefinition();
      fd.setProperty("type", type);
      VariableDefinition varDef = new VariableDefinition();
      varDef.variableName = fieldName;
      if (initializer != null)
         varDef.setProperty("initializer", initializer);
      if (op != null)
         varDef.setProperty("operator", op);
      SemanticNodeList snl = new SemanticNodeList(1);
      snl.add(varDef);
      fd.setProperty("variableDefinitions", snl);
      return fd;
   }

   public void init() {
      if (initialized) return;

      super.init();
   }

   public void start() {
      if (started) return;

      super.start();

   }

   public void validate() {
      if (validated) return;

      BodyTypeDeclaration enclType = getEnclosingType();

      if (variableDefinitions != null) {
         if (enclType != null) {
            LayeredSystem sys = enclType.getLayeredSystem();
            // The type hierarchy needs to be defined before we can call "getMethods" so this should not be in "start"
            if (sys != null) {
               for (VariableDefinition varDef:variableDefinitions) {
                  if (StringUtil.equalStrings(sys.getRuntimeName(), "js") && enclType.getMethods(varDef.variableName, null) != null)
                     varDef.shadowedByMethod = true;
               }
            }
         }
      }

      super.validate();

      if (hasAnyBindings())
         detectCycles();
   }

   private boolean hasAnyBindings() {
      for (VariableDefinition v:variableDefinitions)
         if (v.bindingDirection != null)
            return true;
      return false;
   }

   public Object definesMember(String name, EnumSet<MemberType> mtype, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      if (mtype.contains(MemberType.Field)) {
         for (VariableDefinition v:variableDefinitions) {
            if (v.variableName.equals(name) && (refType == null || ModelUtil.checkAccess(refType, this))) {
               if ((!mtype.contains(MemberType.Initializer) || v.initializer != null))
                  return v;
            }
         }
      }
      return super.definesMember(name, mtype, refType, ctx, skipIfaces, isTransformed);
   }

   public void clearDynFields(Object inst, ExecutionContext ctx) {
      if (hasModifier("static"))
         return;

      Object varType = type.getTypeDeclaration();
      if (ModelUtil.isPrimitive(varType)) {
         for (VariableDefinition v:variableDefinitions) {
            Object pval;
            if (ModelUtil.isLong(varType))
               pval = 0L;
            else if (ModelUtil.isAnInteger(varType))
               pval = 0;
            else if (ModelUtil.isFloat(varType))
               pval = 0.0F;
            else if (ModelUtil.isDouble(varType))
               pval = 0.0;
            else if (ModelUtil.isBoolean(varType))
               pval = Boolean.FALSE;
            else if (ModelUtil.isCharacter(varType))
               pval = '\0';
            else
               pval = null;

            if (pval != null)
               DynUtil.setProperty(inst, v.variableName, pval, true);
         }
      }
   }

   public void refreshBoundTypes(int flags) {
      super.refreshBoundTypes(flags);
      if (variableDefinitions != null)
         for (VariableDefinition v:variableDefinitions)
            v.refreshBoundType(flags);
   }

   public void addDependentTypes(Set<Object> types) {
      super.addDependentTypes(types);
      if (variableDefinitions != null)
         for (VariableDefinition v:variableDefinitions)
            v.addDependentTypes(types);
   }

   public void initDynStatement(Object inst, ExecutionContext ctx, TypeDeclaration.InitStatementMode mode, boolean inherit) {
      if (hasModifier("static"))
         return;

      Class pType = type.getRuntimeClass();
      for (VariableDefinition v:variableDefinitions) {
         Expression initializer = v.initializer;
         Object pval;

         // Note: we already cleared prim nums to zero so do not set things to null here
         if (initializer != null) {
            if (mode.evalExpression(initializer, v.bindingDirection)) {
               pval = initializer.eval(pType, ctx);

               if (pval == null) {
                  Class rc = v.getRuntimeClass();
                  if (rc != null && rc.isPrimitive())
                     pval = Type.get(rc).getDefaultObjectValue();
               }

               // don't set the value for =: bindings.
               if (v.bindingDirection == null || v.bindingDirection.doForward())
                  TypeUtil.setDynamicProperty(inst, v.variableName, pval, true);
            }
         }
      }
   }

   // TODO: need to validate that all items in the expression are marked with the @Sync annotation - i.e. are available to the client
   public void updateRuntimeType(Object inst, SyncManager.SyncContext syncCtx, ExecutionContext ctx) {
      Object rtType = type.getRuntimeType();
      Class rtClass = type.getRuntimeClass();
      for (VariableDefinition v:variableDefinitions) {
         TypeDeclaration enclType = getEnclosingType();
         String prefix;
         // If this type is an inner type, use the enclType's full type name.  e.g. TodoList.TodoItem should have TodoList in the name
         if (ModelUtil.getEnclosingType(rtType) != null)
            prefix = enclType.getFullTypeName();
         // But in this case, UIIcon, a top-level type should not have UIIcon in the name.  This is an artifact of the fact that we need some outer type even for a top-level type.
         else
            prefix = CTypeUtil.getPackageName(enclType.getFullTypeName());

         String objName = CTypeUtil.prefixPath(prefix, v.variableName);
         Object oldFieldValue = ScopeDefinition.lookupName(objName);

         boolean flushQueue = SyncManager.beginSyncQueue();

         try {

            Expression initializer = v.initializer;
            Object newValue;
            // Note: we already cleared prim nums to zero so do not set things to null here
            if (initializer != null) {
               newValue = initializer.eval(rtClass, ctx);

               if (newValue == null) {
                  Class rc = v.getRuntimeClass();
                  if (rc.isPrimitive())
                     newValue = Type.get(rc).getDefaultObjectValue();
               }

               if (oldFieldValue != null && newValue != null) {
                  System.out.println("*** Warning - overriding field already defined " + objName + " exists - the old instance is being replaced");
               }

               /*
                * Not registering it in scopes.  This does not seem necessary... we just need to register this name with the sync system at this point.  It
                * manages when the name gets reset for example which we do not do with scopes.   Same code over in BodyTypeDeclaration for objects.
               String scopeName = ModelUtil.getScopeName(type.getTypeDeclaration());
               ScopeDefinition scope;
               if (scopeName != null) {
                  scope = ScopeDefinition.getScopeByName(scopeName);

                  if (scope == null)
                     throw new IllegalArgumentException("No scope named: " + scopeName);

               }
               else
                  scope = syncCtx.getScopeContext().getScopeDefinition();

               // Notice that we are not actually creating a field here.  Instead, for this scope and this object name
               // you store all of the dynamically created object instances on either side.  In effect, there's a runtime
               // layer on the entire type system for the purposes of the sync runtime.
               if (scope != null)
                  scope.registerInstance(objName, newValue);
               */

               // Also need to register the name with the sync system so it uses the same name for the object.  This has to happen after the addSyncInst call
               // but we have enabled the sync queue so we know this will happen before we actually add the sync inst itself, so it will get the right name.
               // Since this reference comes from the client, when the client refreshes, we do need to send the register inst the next time (hence false for the last arg)
               if (newValue != null) {
                  syncCtx.registerObjName(newValue, objName, false, false);

               }

               if (flushQueue) {
                  SyncManager.flushSyncQueue();
                  flushQueue = false;
               }

               // New expressions here are for defining new instances, they are handled separately and not processed as method results.
               if (v.initializer instanceof IdentifierExpression && !(v.initializer instanceof NewExpression)) {
                  IdentifierExpression ie = (IdentifierExpression) v.initializer;
                  if (ie.arguments != null) {
                     syncCtx.addMethodResult(inst == null ? enclType : inst, v.variableName, newValue);
                  }
               }
            }
         }
         finally {
            if (flushQueue)
               SyncManager.flushSyncQueue();
         }
      }
   }

   public void addInitStatements(List<Statement> res, InitStatementsMode mode) {
      // In the ModelStream case, we want everything in a consistent order so fields come in during the Init phase, not
      // pre-init.  I think that for the regular JS case, we null out fields in preInit and initialize their values in
      // init.
      if (hasModifier("static") != mode.doStatic() || (mode == InitStatementsMode.PreInit && !getJavaModel().mergeDeclaration))
         return;
      res.add(this);
   }

   public Definition modifyDefinition(BodyTypeDeclaration base, boolean doMerge, boolean inTransformed) {
      Object refType = getEnclosingType();
      for (int i = 0; i < variableDefinitions.size(); i++) {
         VariableDefinition v = variableDefinitions.get(i);
         Object oldVar;
         if ((oldVar = base.definesMember(v.variableName, MemberType.FieldSet, refType, null)) != null) {
            if (oldVar == v) {
               System.out.println("*** error: base type returns same field as modified type!");
               break;
            }
            if (!ModelUtil.isAssignableFrom(ModelUtil.getVariableTypeDeclaration(oldVar), ModelUtil.getVariableTypeDeclaration(v), true, null))
               System.err.println("Illegal attempt to modify a field definition with an incompatible type - new types must match or extend the modified type : " + toDefinitionString()); // TODO: fix error

            if (!(oldVar instanceof VariableDefinition))
               System.err.println("Illegal attempt to replace a field from a compiled class: " + oldVar + " in: " + toDefinitionString()); 


            /* Remove the old variable and field if it is the last variable in that field def */
            VariableDefinition oldVarDef = (VariableDefinition) oldVar;

            FieldDefinition oldFieldDef = (FieldDefinition) oldVarDef.parentNode.getParentNode();

            overrides = oldFieldDef;

            if (oldFieldDef.variableDefinitions.size() == 1)
               oldFieldDef.parentNode.removeChild(oldFieldDef);
            else {
               int ix = oldFieldDef.variableDefinitions.indexOf(oldVarDef);
               oldFieldDef.variableDefinitions.remove(ix);
            }
         }
      }
      base.addBodyStatement(this);
      return this;
   }

   public void collectReferenceInitializers(List<Statement> refInits) {
      for (VariableDefinition v:variableDefinitions) {
         if (v.isReferenceInitializer()) {
            refInits.add(convertToAssignmentExpression(v));
            if (v.initializer != null) {
               v.origInitializer = v.initializer;
               v.setProperty("initializer", null);
            }
            v.setProperty("operator", null);
         }
      }
   }

   /**
    * If we need to do the initialization of this field after the constructor, call this method,
    * It returns an AssignmentExpression that refers to this variable name for inclusion in the
    * generated preInit method.
    *
    * We also null out the initializer so the code does not get executed twice.
    */
   public AssignmentExpression convertToAssignmentExpression(VariableDefinition v) {
      AssignmentExpression ae = new AssignmentExpression();
      IdentifierExpression ie = new IdentifierExpression();
      ie.identifiers = new SemanticNodeList<IString>(ie, 1);
      ie.identifiers.add(PString.toIString(v.variableName));
      ae.setProperty("lhs", ie);
      ae.fromDefinition = v;
      ae.setProperty("operator", "=");   
      v.initializer.parentNode = ae; // TODO: no longer needed?

      Expression initializer = v.initializer;
      v.origInitializer = v.initializer;
      v.setProperty("initializer", null);
      v.setProperty("operator", null);
      ae.setProperty("rhs", initializer);

      return ae;
   }


   public boolean isStatic() {
      return hasModifier("static");
   }

   public boolean hasDefinedModifier(String modifier) {
      if (frozenStatic && modifier.equals("static"))
         return true;

      return super.hasModifier(modifier);
   }

   public boolean hasModifier(String modifier) {
      if (frozenStatic && modifier.equals("static"))
         return true;

      TypeDeclaration enclType = getEnclosingType();
      // Fields that are part of an interface are implicitly public, static, final in Java but not in StrataCode
      if (enclType != null && enclType.getDeclarationType() == DeclarationType.INTERFACE) {
         if (modifier.equals("public"))
            return true;
         // SC files do still always make interface fields public automatically like Java but do allow instance
         // fields so static and final are not implied.
         if (!enclType.getJavaModel().enableExtensions() && (modifier.equals("static") || modifier.equals("final")))
            return true;
      }
      return super.hasModifier(modifier);
   }

   public Object[] getExtraModifiers() {
      TypeDeclaration enclType = getEnclosingType();
      // Fields that are part of an interface are implicitly public, static, final in Java but not in StrataCode
      if (enclType != null && enclType.getDeclarationType() == DeclarationType.INTERFACE) {
         if (enclType.getJavaModel().enableExtensions()) {
            if (!super.hasModifier("public"))
               return new Object[] {"public"};
         }
         else
            return new Object[] {"public", "final", "static"};
      }
      return null;
   }

   public boolean isProperty() {
      return true;
   }

   /** Turn this on unless we are in an interface or annotation type */
   public boolean useDefaultModifier() {
      TypeDeclaration type = getEnclosingType();
      if (type == null)
         return false;
      return type.useDefaultModifier();
   }

   public void visitTypeReferences(CycleInfo info, TypeContext ctx) {
      info.visitList(variableDefinitions, ctx);
   }

   public boolean isReferenceValueObject() {
      return true;
   }

   public String getUserVisibleName() {
      return "field";
   }

   public boolean transform(ILanguageModel.RuntimeType runtime) {
      // Need to first transform the type.  It may get used in the applyPropertyTemplate applied when we
      // transform the variable definitions.
      boolean any = type != null && type.transform(runtime);
      if (super.transform(runtime))
         any = true;
      return any;
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      if (type == null)
         sb.append("<no type>");
      else
         sb.append(type.toString());
      sb.append(" ");
      int i = 0;
      if (variableDefinitions == null)
         sb.append("<no var defs>");
      else {
         for (VariableDefinition v:variableDefinitions) {
            if (i != 0)
               sb.append(", ");
            sb.append(v.variableName);
            if (v.initializer != null && v.operator != null) {
               sb.append(" ");
               sb.append(v.operator);
               sb.append(" ");
               sb.append(v.initializer.toSafeLanguageString());
            }
            i++;
         }
      }
      return sb.toString();
   }

   public void removeVariable(VariableDefinition varDef, ExecutionContext ctx, boolean updateInstances) {
      String name = varDef.variableName;
      if (variableDefinitions.removeChild(varDef) != -1) {
         BodyTypeDeclaration enclType = getEnclosingType();
         Object override = enclType.definesMember(name, MemberType.PropertyAnySet, null, null);
         if (override != null && override instanceof JavaSemanticNode) {
            enclType.updatePropertyForType((JavaSemanticNode) override, ctx, BodyTypeDeclaration.InitInstanceType.Init, updateInstances, null);
         }
      }
      else {
         System.err.println("*** Can't find variable to remove from field: " + this);
      }
   }

   public void addVariable(int ix, VariableDefinition varDef, ExecutionContext execContext, boolean updateInstances) {
      variableDefinitions.add(ix, varDef);
      BodyTypeDeclaration enclType = getEnclosingType();
      enclType.addInstMemberToPropertyCache(varDef.getVariableName(), varDef);
      enclType.updatePropertyForType(varDef, execContext, BodyTypeDeclaration.InitInstanceType.Init, updateInstances, null);
   }

   public Statement transformToJS() {
      freezeType();  // Snag the type info so we can destroy it but still have this node behave during the transform

      // Before we erase the type, tell the variable def to freeze it
      if (variableDefinitions != null)
         for (VariableDefinition v:variableDefinitions)
            v.freezeType();
      type.transformToJS();
      int sz = variableDefinitions.size();
      for (int i = 0; i < sz; i++) {
         VariableDefinition varDef = variableDefinitions.get(i);
         varDef.transformToJS();
      }

      if (modifiers != null)
         modifiers.clear();

      return this;
   }

   private String getNullInit(VariableDefinition varDef) {
      String nullInit = "null";

      Object type = varDef.getTypeDeclaration();
      Object varType = varDef.getTypeDeclaration();
      // No need to initialize things to null - unless they are primitives which need to be zero'd
      if (ModelUtil.isPrimitive(varType)) {
         Class c = ModelUtil.getCompiledClass(varType);
         if (c == char.class || c == Character.class)
            nullInit = "'\\0'";
         else
            nullInit = String.valueOf(Type.get(c).getDefaultObjectValue());
      }
      return nullInit;
   }

   public CharSequence formatToJS(JSFormatMode mode) {
      JavaModel model = getJavaModel();
      int sz = variableDefinitions.size();
      StringBuilder res = new StringBuilder();
      for (int i = 0; i < sz; i++) {
         VariableDefinition varDef = variableDefinitions.get(i);
         String nullInit = getNullInit(varDef);
         if (varDef.initializer == null) {
            // We initialized this in the PreInit phase
            if (mode == JSFormatMode.InstInit)
               continue;
         }
         if (i != 0)
            res.append(" ");
         else
            res.append(getIndentStr());
         String prefix;

         LayeredSystem sys = getLayeredSystem();

         TypeDeclaration enclType = getEnclosingType();

         // If there's a custom resolver which produces this value (i.e. it's a synchronized instance), use it on the remote side to register the instance
         if (model.customResolver != null && varDef.initializer != null) {
            String enclTypeName = enclType.getFullTypeName();
            // If it's a top level object, its just packageName + varName.  If it's an inner object, it's the full type name of the parent plus the variable name.
            //String packageName = enclType.getEnclosingType() == null ? CTypeUtil.prefixPath(CTypeUtil.getPackageName(enclTypeName) : enclTypeName;
            String packageName = enclTypeName;
            String fullName = CTypeUtil.prefixPath(packageName, varDef.variableName);

            Object resolvedObj = model.customResolver.resolveObject(model.getPackagePrefix(), fullName, false, false);
            // We have problems here because sometimes enclType = the type we are creating, e.g. UIIcon - in which case we strip out the top-level type name
            // and other times it is the outer type of the type we are creating, where we do not
            if (resolvedObj == null) {
               packageName = enclType.getEnclosingType() == null ? CTypeUtil.getPackageName(enclTypeName) : enclTypeName;
               String newFullName = CTypeUtil.prefixPath(packageName, varDef.variableName);
               if (!newFullName.equals(fullName)) {
                  resolvedObj = model.customResolver.resolveObject(model.getPackagePrefix(), newFullName, false, false);
                  if (resolvedObj != null)
                     fullName = newFullName;
               }
            }

            if (resolvedObj != null) {
               // Not sure whether this case is used for syncing but for now keeping the old registerSyncInst(<expr>, "name") pattern
               if (!(varDef.initializer instanceof NewExpression)) {
                  String regInstName = model.customResolver.getRegisterInstName();
                  String regInstTypeName = CTypeUtil.getPackageName(regInstName);
                  String jsName = JSUtil.convertTypeName(sys, regInstTypeName);
                  res.append(jsName);
                  res.append(((JSRuntimeProcessor) model.layeredSystem.runtimeProcessor).prototypeSuffix);
                  res.append(".");
                  res.append(CTypeUtil.getClassName(regInstName));
                  res.append("(");
                  // Need to pull off the ; here.  You'd think expressions would not format with a ; but
                  // it does because the grammar allows an expression to be a statement.  Maybe the statement
                  // version of expression should be a different type so we could format each type unambiguously?
                  String initStr = varDef.initializer.formatExprToJS().toString();
                  // TODO: what if the expression has a ; in it and there isn't one on the end?
                  //int semiIx = initStr.lastIndexOf(';');
                  //if (semiIx != -1)
                  //   initStr = initStr.substring(0, semiIx);
                  res.append(initStr);
                  res.append(", \"");
                  res.append(fullName);
                  res.append("\");\n");
               }
               // Generates code of the form: SyncManager.resolveOrCreateSyncInst("name", TypeToCreate, "<method-sign>", arg0, arg1, arg2)
               else {
                  NewExpression newExpr = (NewExpression) varDef.initializer;
                  String regInstName = model.customResolver.getResolveOrCreateInstName();
                  String regInstTypeName = CTypeUtil.getPackageName(regInstName);
                  String jsName = JSUtil.convertTypeName(sys, regInstTypeName);
                  res.append(jsName);
                  res.append(((JSRuntimeProcessor) model.layeredSystem.runtimeProcessor).prototypeSuffix);
                  res.append(".");
                  res.append(CTypeUtil.getClassName(regInstName));
                  res.append("(\"");
                  res.append(fullName);
                  res.append("\", ");
                  res.append(JSUtil.convertTypeName(sys, newExpr.typeIdentifier));
                  res.append(", ");
                  Object constr = newExpr.constructor;
                  if (constr == null)
                     res.append("null");
                  else {
                     res.append("\"");
                     res.append(ModelUtil.getTypeSignature(constr));
                     res.append("\"");
                  }
                  if (newExpr.arguments != null) {
                     for (Expression constArg:newExpr.arguments) {
                        res.append(", ");
                        res.append(constArg.formatExprToJS().toString());
                     }
                  }
                  res.append(");\n");
               }
               return res;
            }
         }
         if (sys == null) {
            System.err.println("*** formatToJS on disconnected node");
            prefix = "???";
         }
         else if (hasModifier("static"))
            prefix = sys.runtimeProcessor.getStaticPrefix(enclType, this);
         else
            prefix = "this";
         res.append(prefix);
         res.append(".");
         if (varDef.shadowedByMethod)
            res.append(JSUtil.ShadowedPropertyPrefix);
         res.append(varDef.variableName);
         res.append(" = ");
         if (varDef.initializer != null) {
            if (mode == JSFormatMode.PreInit) {
               res.append(nullInit);
            }
            else
               res.append(varDef.initializer.formatToJS(mode));
         }
         else
            res.append(nullInit);

         res.append(";\n");
      }
      return res;
   }

   public boolean needsDataBinding() {
      int sz = variableDefinitions.size();
      for (int i = 0; i < sz; i++) {
         VariableDefinition varDef = variableDefinitions.get(i);
         if (varDef.needsDataBinding())
            return true;
      }
      return false;
   }

   public void freezeType() {
      //frozenType = type.getTypeDeclaration();
      frozenStatic = isStatic();
   }

   public int transformTemplate(int ix, boolean statefulContext) {
      int sz = variableDefinitions.size();
      for (int i = 0; i < sz; i++) {
         VariableDefinition varDef = variableDefinitions.get(i);
         ix = varDef.transformTemplate(ix, statefulContext);
      }
      return ix;
   }

   public boolean getNodeContainsPart(ISrcStatement fromSt) {
      if (super.getNodeContainsPart(fromSt))
         return true;
      if (variableDefinitions != null) {
         for (VariableDefinition varDef:variableDefinitions)
            if (varDef == fromSt || varDef.getNodeContainsPart(fromSt))
               return true;
      }
      return false;
   }

   public ISrcStatement findFromStatement(ISrcStatement st) {
      ISrcStatement fromSt = super.findFromStatement(st);
      if (fromSt != null)
        return fromSt;
      // We may generate a FieldDefinition from a VariableDefinition.  In that case, the Field that encloses that
      // variable may contain the link we need to follow to find this src file.
      if (fromStatement instanceof VariableDefinition) {
         Definition def = (((VariableDefinition) fromStatement).getDefinition());
         if (def instanceof FieldDefinition) {
            fromSt = ((FieldDefinition) def).findFromStatement(st);
            if (fromSt != null)
               return this;
         }
      }
      return null;
   }

   public boolean needsEnclosingClass() {
      return true;
   }
}