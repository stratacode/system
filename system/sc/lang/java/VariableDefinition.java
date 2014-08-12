/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.Bind;
import sc.bind.BindingDirection;
import sc.dyn.DynUtil;
import sc.lang.*;
import sc.lang.js.JSUtil;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.parser.IStyleAdapter;
import sc.parser.Language;
import sc.parser.ParentParseNode;
import sc.parser.ParseUtil;
import sc.type.IBeanMapper;
import sc.type.Type;
import sc.util.StringUtil;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public class VariableDefinition extends AbstractVariable implements IVariableInitializer, ISrcStatement {
   public Expression initializer;
   public String operator;        // Usually '=' - used for data binding initializes

   public transient BindingDirection bindingDirection;
   public transient boolean convertGetSet = false;
   public transient boolean bindable = false;
   public transient boolean needsDynAccess = false;

   // For short x = 3 to  setX(3) we need to do setX((short) 3)
   public transient boolean needsCastOnConvert = false;
   public transient Expression origInitializer;

   public transient VariableDefinition replacedBy;

   // Set to true if there's a method of the same name.  This is used in the Javascript conversion, which unlike Java has one namespace shared by fields and methods.
   public transient boolean shadowedByMethod = false;

   // When doing conversions to other languages, may need to freeze the type
   public transient Object frozenTypeDecl;

   public transient ISrcStatement fromStatement;

   private static boolean wasBound = false;

   public void initialize() {
      if (initialized)
         return;

      // Propagate the old-school C array dimension syntax to the type
      if (variableName != null && variableName.endsWith("[]")) {
         int dimsIx = variableName.indexOf("[]");
         arrayDimensions = variableName.substring(dimsIx);
         variableName = variableName.substring(0,dimsIx);
      }

      // The annotation makes us bindable, or this gets set if we are used in a binding expression
      Object annot = null;
      bindable = bindable || (annot = ModelUtil.getBindableAnnotation(getDefinition())) != null;

      bindingDirection = ModelUtil.initBindingDirection(operator);
      if (bindingDirection != null) {
         Definition def = getDefinition();
         if (!(def instanceof FieldDefinition)) {
            def.displayError("Data binding operator only valid for field definitions: ");
            bindingDirection = null;
         }
         else if (initializer == null) {
            displayError("Invalid empty binding expression: ");
            bindingDirection = null;
         }
         else
            initializer.setBindingInfo(bindingDirection, getDefinition(), false);
      }

      if (bindable) {
         needsDynAccess = true;

         if (!(getDefinition() instanceof FieldDefinition)) {
            displayError("@Bindable annotation invalid on non-fields: ");
         }
         if (annot != null && ModelUtil.isAutomaticBindingAnnotation(annot)) {
            if (canMakeBindable())
               convertGetSet = true;
            else
               displayError("@Bindable not allowed on static interface properties for: ");
         }
      }

      if (!convertGetSet) {
         Object annotObj = ModelUtil.getAnnotation(getDefinition(), "sc.obj.GetSet");
         if (annotObj != null) {
            Object value = ModelUtil.getAnnotationValue(annotObj, "value");
            if (value == null || (value instanceof Boolean && ((Boolean) value)))
               convertGetSet = true;
         }
      }
      super.initialize();
   }

   public void start() {
      if (started)
         return;
      super.start();

      Object annot = null;
      TypeDeclaration enclType = getEnclosingType();
      if (enclType != null) {
         Object getMethod = enclType.declaresMember(variableName, JavaSemanticNode.MemberType.GetMethodSet, null, null);
         if (getMethod != null) {
            annot = ModelUtil.getBindableAnnotation(getMethod);
         }
         if (annot == null) {
            Object setMethod = enclType.declaresMember(variableName, JavaSemanticNode.MemberType.SetMethodSet, null, null);
            if (setMethod != null)
               annot = ModelUtil.getBindableAnnotation(setMethod);
         }
         if (annot != null) {
            // If we are marked with manual=true we are already bindable - don't do convertGetSet
            if (!ModelUtil.isAutomaticBindingAnnotation(annot))
               bindable = true;
         }
      }

      if (bindingDirection != null && (bindingDirection.doReverse() || bindable)) {
         makeBindable(false);
      }

      // Check to be sure the initializer is compatible with the property
      if (initializer != null) {
         Object initType = initializer.getGenericType();
         Object varType = getTypeDeclaration();
         if (initType != null && varType != null && !ModelUtil.isAssignableFrom(varType, initType, true, null) && (bindingDirection == null || bindingDirection.doForward())) {
            if (!ModelUtil.hasUnboundTypeParameters(initType)) {
               displayTypeError("Type mismatch - assignment to variable with type: " + ModelUtil.getTypeName(varType, true, true) + " does not match expression type: " + ModelUtil.getTypeName(initType, true, true) + " for: ");
               boolean xx = initType != null && varType != null && !ModelUtil.isAssignableFrom(varType, initType, true, null) && (bindingDirection == null || bindingDirection.doForward());
               initType = initializer.getGenericType();
            }
         }
         else if (initType != varType) {
            if (ModelUtil.isANumber(varType)) {
               needsCastOnConvert = true;
            }
         }
      }
   }

   /** Can be an AnnotatedElementTypeDeclaration or a TypedDefinition of some kind */
   public Statement getDefinition() {
      return (Statement) (parentNode instanceof SemanticNodeList ? parentNode.getParentNode() : parentNode);
   }

   public Class getRuntimeClass() {
      if (frozenTypeDecl != null)
         return ModelUtil.getCompiledClass(frozenTypeDecl);

      Statement def = getDefinition();
      if (def instanceof FieldDefinition) {
         return ((FieldDefinition) def).type.getRuntimeClass();
      }
      if (def instanceof VariableStatement) {
         return ((VariableStatement) def).type.getRuntimeClass();
      }
      return null;
   }

   public Object getRuntimePropertyMapping() {
      Statement def = getDefinition();
      if (def instanceof FieldDefinition) {
         TypeDeclaration type = def.getEnclosingType();
         Object cl = type.getRuntimeType();
         if (cl == null) {
            // Happens when we have a compiled type which is not yet compiled.
            return null;
            //System.err.println("*** No runtime type: " + type.getFullTypeName() + " compiled as: " + type.getCompiledClassName());
         }
         IBeanMapper mapper = DynUtil.getPropertyMapping(cl, variableName);
         return mapper;
      }
      return null;
   }

   public Object getRuntimeField() {
      Statement def = getDefinition();
      if (def instanceof FieldDefinition) {
         TypeDeclaration type = def.getEnclosingType();
         Object cl = type.getRuntimeType();
         if (cl == null)
             System.err.println("*** No runtime type: " + type.getFullTypeName() + " compiled as: " + type.getCompiledClassName());
         IBeanMapper mapper = DynUtil.getPropertyMapping(cl, variableName);
         return mapper == null ? null : mapper.getField();
      }
      return null;
   }

   public Object getTypeDeclaration() {
      if (frozenTypeDecl != null)
         return frozenTypeDecl;

      Definition def = getDefinition();
      if (def instanceof TypeDeclaration)
         return def;
      Object type = ((TypedDefinition) def).type.getTypeDeclaration();

      // Handles old school array dimensions after the variable name
      if (arrayDimensions == null)
         return type;
      return new ArrayTypeDeclaration(getJavaModel().getModelTypeDeclaration(), type, arrayDimensions);
   }

   public String getVariableTypeName() {
      Object varType = getTypeDeclaration();
      if (varType == null)
         return null;
      return ModelUtil.getTypeName(varType);
   }

   public String getGenericTypeName(Object resultType, boolean includeDims) {
      Definition def = getDefinition();
      if (def instanceof TypeDeclaration)
         return ModelUtil.getTypeName(def);
      JavaType type = ((TypedDefinition) def).type;

      // Handles old school array dimensions after the variable name
      if (arrayDimensions == null)
         return type.getGenericTypeName(resultType, includeDims);
      return type.getGenericTypeName(resultType, includeDims) + (includeDims ? arrayDimensions : "");
   }

   public String getAbsoluteGenericTypeName(Object resultType, boolean includeDims) {
      Definition def = getDefinition();
      if (def instanceof TypeDeclaration)
         return ModelUtil.getTypeName(def);
      JavaType type = ((TypedDefinition) def).type;

      // Handles old school array dimensions after the variable name
      if (arrayDimensions == null)
         return type.getAbsoluteGenericTypeName(resultType, includeDims);
      return type.getAbsoluteGenericTypeName(resultType, includeDims) + (includeDims ? arrayDimensions : "");
   }

   public String getTypeName() {
      Definition def = getDefinition();
      if (def instanceof TypeDeclaration)
         return ((TypeDeclaration) def).typeName;
      String typeName = frozenTypeDecl != null ? ModelUtil.getTypeName(frozenTypeDecl) :
              ((TypedDefinition) def).type.getFullTypeName();

      // Handles old school array dimensions after the variable name
      if (arrayDimensions == null)
         return typeName;
      return typeName + arrayDimensions;
   }

   public boolean isReferenceInitializer() {
      Statement def = getDefinition();
      // No good collecting static initializers since we do not move the code in static blocks either
      if (def.hasModifier("final") || def.hasModifier("static"))
         return false;
      if (initializer == null)
         return false;
      return initializer.isReferenceInitializer();
   }

   public boolean needsTransform() {
      return bindingDirection != null || convertGetSet || super.needsTransform();
   }

   public boolean needsBindable() {
      if (bindable)
         return true;

      JavaModel model = getJavaModel();
      model.initReverseDeps();

      return bindable;
   }

   public boolean needsGetSet() {
      if (convertGetSet)
         return true;

      JavaModel model = getJavaModel();
      model.initReverseDeps();

      return convertGetSet;
   }

   public boolean transform(ILanguageModel.RuntimeType runtime) {
      if (transformed)
         return false;

      boolean any;

      // If this initializer has been stripped off, we can't reset the operator here
      if (bindingDirection != null && operator != null) {
         if (bindingDirection.doForward()) {
            setProperty("operator", "=");
         }
         else {
            // If there is no forward component to the binding, we will skip the variable initialization
            // instead, this binding expression will go into its own block that is added right after
            // the field.
            BlockStatement newInit = new BlockStatement();
            newInit.fromDefinition = this;
            newInit.visible = true;
            newInit.setProperty("statements", new SemanticNodeList<Statement>(1));
            newInit.statements.add(initializer);
            setProperty("initializer", null);
            setProperty("operator", null);

            FieldDefinition field = (FieldDefinition) getDefinition();
            SemanticNodeList parentList = (SemanticNodeList) field.parentNode;
            int ix = parentList.indexOf("field");
            parentList.add(ix+1,newInit);
            newInit.transform(runtime);
         }
         any = true;
      }
      else
         any = false;

      // Need to transform our initializer before we do the convert to get/set as it makes a copy of that
      // to put into the get/set methods.
      if (super.transform(runtime))
         any = true;

      if (convertGetSet) {
         TransformUtil.convertFieldToGetSetMethods(this, bindable, ModelUtil.isInterface(getEnclosingType()), runtime);
         any = true;
      }
      else if (initializer instanceof ArrayInitializer) {
         Object arrayType = getTypeDeclaration();
         if (ModelUtil.isAssignableFrom(Collection.class, arrayType)) {
            NewExpression newExpr = TransformUtil.convertArrayInitializerToNewCollection(this, arrayType, (ArrayInitializer) initializer);
            setProperty("initializer", newExpr);
            any = true;
         }
      }
      return any;
   }

   public void makeBindable(boolean referenceOnly) {
      if (!bindable && !referenceOnly) {
         JavaModel model = getJavaModel();
         LayeredSystem sys = model.getLayeredSystem();
         // Only start checking after the current buildLayer is compiled.  Otherwise, this touches runtime classes
         // during the normal build which can suck in a version that will be replaced later on.
         if (sys != null && sys.buildLayer != null && sys.buildLayer.compiled) {
            Object field = getRuntimePropertyMapping();
            // If field is null, it means we have not compiled a compiled class yet
            if (field != null && (!ModelUtil.isDynamicType(field) && !ModelUtil.isBindable(field)) && canMakeBindable() && !ModelUtil.isConstant(field))
               model.layeredSystem.setStaleCompiledModel(true, "Recompile needed to make ", variableName, " bindable for type: ", getEnclosingType().toString());
         }
      }
      needsDynAccess = true;
      if (!referenceOnly) {
         // If we are manually bindable, no need to force the convert get/set
         if (!bindable) {
            // Cannot get RuntimePropertyMapping here - the validate phase happens before the compile so that's not available
            Object field = getDefinition();
            if (canMakeBindable() && (field == null || !ModelUtil.isConstant(field))) {
               convertGetSet = true;
            }
         }
         bindable = true;
      }
   }

   private boolean canMakeBindable() {
      return (!isStatic() || getEnclosingType().getDeclarationType() != DeclarationType.INTERFACE);
   }

   public String toListDisplayString() {
      StringBuilder sb = new StringBuilder();
      sb.append(variableName);
      sb.append(": ");
      sb.append(getTypeNameString());
      if (operator != null) {
         sb.append(" ");
         sb.append(operator);
         if (initializer != null) {
            sb.append(" ");
            sb.append(initializer.toLanguageString());
         }
      }
      return sb.toString();
   }

   private CharSequence getTypeNameString() {
      Statement def = getDefinition();
      StringBuilder sb = new StringBuilder();
      if (def != null) {
         SemanticNodeList mods = def.modifiers;
         sb.append((mods == null ? "" : mods.toLanguageString(JavaLanguage.getJavaLanguage().modifiers) + " ") + getTypeName());
      }
      else {
         if (frozenTypeDecl != null)
            sb.append(ModelUtil.getTypeName(frozenTypeDecl));
         else
            sb.append("<no type>");
      }
      return sb;
   }

   public String toDeclarationString() {
      Statement def = getDefinition();
      StringBuilder sb = new StringBuilder();
      sb.append(getTypeNameString());
      sb.append(" ");
      sb.append(variableName);

      // Include the initializer if it is there
      if (operator != null && initializer != null) {
         sb.append(operator);
         try {
            if (initializer.parseNode != null && !initializer.parseNodeInvalid)
               sb.append(initializer.toLanguageString());
            else
               sb.append(initializer.getUserVisibleName());
         }
         catch (RuntimeException exc) {
            sb.append("<uninitialized variable with name: " + variableName + ">");
         }
      }
      return sb.toString();
   }

   // Here just to make it easier to sync to the client
   public void setInitializerExprStr(String s) {
      throw new UnsupportedOperationException();
   }

   // Exposed as a property for synchronizing to the client;
   public String getInitializerExprStr() {
      if (initializer == null)
         return null;
      else
         return initializer.toLanguageString();
   }

   public VariableDefinition deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      VariableDefinition newVarDef = (VariableDefinition) super.deepCopy(options, oldNewMap);

      if ((options & CopyState) != 0) {
         newVarDef.bindable = bindable;
         newVarDef.convertGetSet = convertGetSet;
         newVarDef.bindingDirection = bindingDirection;
         newVarDef.needsCastOnConvert = needsCastOnConvert;
         newVarDef.shadowedByMethod = shadowedByMethod;
      }

      if ((options & CopyInitLevels) != 0) {
         newVarDef.needsDynAccess = needsDynAccess;
         // For short x = 3 to  setX(3) we need to do setX((short) 3)
         newVarDef.origInitializer = origInitializer;
         newVarDef.replacedBy = replacedBy;
         // Set to true if there's a method of the same name.  This is used in the Javascript conversion, which unlike Java has one namespace shared by fields and methods.
         newVarDef.shadowedByMethod = shadowedByMethod;
         // When doing conversions to other languages, may need to freeze the type
         newVarDef.frozenTypeDecl = frozenTypeDecl;
      }
      return newVarDef;
   }

   public void visitTypeReferences(CycleInfo info, TypeContext ctx) {
      if (bindingDirection != null) {
         ctx = new TypeContext(ctx);
         info.visit(initializer == null ? origInitializer : initializer, ctx, true);
      }
   }

   public boolean isReferenceValueObject() {
      return true;
   }

   public void initDynamicInstance(Object inst, ExecutionContext ctx) {
      // ??? Test this - it was commented out for some reason?
      if (initializer == null)
         return;

      Object pval;
      try {

         if (wasBound) {
            Bind.removePropertyBindings(inst, variableName, true, true);
         }

         ctx.pushCurrentObject(inst);
         pval = getInitialValue(ctx);
      }
      finally {
         ctx.popCurrentObject();
      }

      DynUtil.setProperty(inst, variableName, pval, true);
   }

   public Object getInitialValue(ExecutionContext ctx) {
      Class c = getRuntimeClass();
      if (initializer == null) {
         if (c.isPrimitive())
            return Type.get(c).getDefaultObjectValue();
         return null;
      }

      Object res = initializer.eval(c, ctx);
      // Binding or expr may return null but primitive properties can't store null.
      if (res == null && c.isPrimitive())
         return Type.get(c).getDefaultObjectValue();
      return res;
   }

   public void styleNode(IStyleAdapter adapter) {
      if (!(getDefinition() instanceof FieldDefinition)) { // Field members only
         super.styleNode(adapter);
         return;
      }

      ParentParseNode pnode = (ParentParseNode) parseNode;
      ParseUtil.styleString(adapter, isStatic() ? "staticMember" : "member", (ParentParseNode) pnode.children.get(0), false);
      for (int i = 1; i < pnode.children.size(); i++)
         ParseUtil.toStyledString(adapter, pnode.children.get(i));
   }

   public Expression getInitializerExpr() {
      return initializer;
   }

   public void updateInitializer(String op, Expression expr) {
      setProperty("operator", op);
      setProperty("initializer", expr);
      if (isInitialized()) {
         wasBound = bindingDirection != null;

         bindingDirection = ModelUtil.initBindingDirection(operator);
         if (bindingDirection != null)
            expr.setBindingInfo(bindingDirection, getDefinition(), false);
      }
      Bind.sendChangedEvent(this, null);
   }

   public void refreshBoundType() {
      if (initializer != null)
         initializer.refreshBoundTypes();
   }

   public void addDependentTypes(Set<Object> types) {
      if (initializer != null)
         initializer.addDependentTypes(types);
   }

   public void transformToJS() {
      if (arrayDimensions != null)
         setProperty("arrayDimensions", null);
      if (initializer != null)
         initializer.transformToJS();
   }

   public boolean needsDataBinding() {
      return bindingDirection != null;
   }

   public String toString() {
      if (debugDisablePrettyToString)
         return toModelString();
      return toDeclarationString();
   }

   public String toSafeLanguageString() {
      if (parseNode == null || parseNodeInvalid) {
         if (variableName != null) {
            StringBuilder sb = new StringBuilder();
            sb.append(variableName);
            if (operator != null) {
               sb.append(" ");
               sb.append(operator);
               sb.append(" ");

               if (initializer != null)
                  sb.append(initializer.toSafeLanguageString());
            }
            return sb.toString();
         }
      }
      return super.toSafeLanguageString();
   }

   public Object getPreviousDefinition() {
      BodyTypeDeclaration btd = getEnclosingType();
      Object base = btd.getDerivedTypeDeclaration();
      if (!(base instanceof BodyTypeDeclaration))
         return null;
      return ((BodyTypeDeclaration) base).definesMember(variableName, MemberType.PropertyAnySet, null, null);
   }

   public Object getStaticValue(ExecutionContext ctx) {
      if (initializer != null)
         return initializer.eval(getRuntimeClass(), ctx);
      else
         return null;
   }

   public void setVariableTypeName(String s) {
      throw new UnsupportedOperationException();
   }

   public void setComment(String s) {
      throw new UnsupportedOperationException();
   }

   public void setLayer(Layer l) {
      throw new UnsupportedOperationException();
   }

   // Here for the client api
   public void setOperatorStr(String s) {
      throw new UnsupportedOperationException();
   }

   public String getOperatorStr() {
      return operator;
   }

   public void updateComplete() {
      wasBound = false;
   }

   public void freezeType() {
      frozenTypeDecl = getTypeDeclaration();
   }

   public static Object createFromField(Field field) {
      VariableDefinition varDef = new VariableDefinition();
      varDef.variableName = field.getName();
      varDef.frozenTypeDecl = field.getType();
      return varDef;
   }

   public Layer getLayer() {
      TypeDeclaration typeDecl = getEnclosingType();
      if (typeDecl == null)
         return null;
      return typeDecl.getLayer();
   }

   public String getComment() {
      Statement def = getDefinition();
      if (def != null) {
         // TODO: should we support comments after the variableDefinition to for:  int foo, /* comment */ bar /* comment */
         return def.getComment();
      }
      return "";
   }

   public String getRealVariableName() {
      if (shadowedByMethod && StringUtil.equalStrings(getLayeredSystem().getRuntimeName(), "js"))
         return JSUtil.ShadowedPropertyPrefix + variableName;
      return variableName;
   }

   public int suggestCompletions(String prefix, Object currentType, ExecutionContext ctx, String command, int cursor, Set<String> candidates, Object continuation) {
      if (initializer != null)
         return initializer.suggestCompletions(prefix, currentType, ctx, command, cursor, candidates, continuation);
      return -1;
   }

   public VariableDefinition refreshNode() {
      JavaModel oldModel = getJavaModel();
      if (!oldModel.removed)
         return this; // We are still valid
      Statement def = getDefinition();
      if (def instanceof FieldDefinition) {
         Object res = oldModel.layeredSystem.getSrcTypeDeclaration(getEnclosingType().getFullTypeName(), null, true,  false, false, oldModel.layer, oldModel.isLayerModel);
         if (res instanceof BodyTypeDeclaration) {
            Object newField = ((BodyTypeDeclaration) res).declaresMember(variableName, MemberType.FieldSet, null, null);
            if (newField instanceof VariableDefinition)
               return (VariableDefinition) newField;
            displayError("Field removed ", variableName);
         }
      }
      if (def instanceof VariableStatement) {
         // Don't think we need these references outside of the file
         return null;
      }
      return null;
   }

   public ISrcStatement getSrcStatement(Language lang) {
      if (lang != null && (parseNode != null && parseNode.getParselet().getLanguage() == lang))
         return this;
      if (fromStatement == null) {
         Statement def = getDefinition();
         if (def.fromStatement != null)
            return def.fromStatement.getSrcStatement(lang);
         return this;
      }
      return fromStatement.getSrcStatement(lang);
   }

   public ISrcStatement findFromStatement (ISrcStatement st) {
      if (fromStatement == st)
         return this;
      // Note we return the first reference even after following the chain - since we want the resulting generated source
      // as mapped to the original source.
      if (fromStatement != null && fromStatement.findFromStatement(st) != null)
         return this;
      Statement def = getDefinition();
      if (def != null && def.findFromStatement(st) != null) {
         return this;
      }
      return null;
   }

   public ISrcStatement getFromStatement() {
      return fromStatement;
   }

   public boolean getNodeContainsPart(ISrcStatement partNode) {
      return this == partNode;
   }

   public boolean childIsTopLevelStatement(ISrcStatement st) {
      return false;
   }

   public void addGeneratedFromNodes(List<ISrcStatement> res, ISrcStatement st) {
      ISrcStatement fromSt = findFromStatement(st);
      if (fromSt != null)
         res.add(fromSt);
   }
}

