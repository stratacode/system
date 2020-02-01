/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.Bind;
import sc.bind.BindingDirection;
import sc.dyn.DynUtil;
import sc.lang.*;
import sc.lang.js.JSUtil;
import sc.lang.sql.DBProvider;
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
import java.util.*;

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

   // Used only for serialization purposes because we use this class in the meta-data model for the client
   public transient boolean indexedProperty = false;

   // When doing conversions to other languages, may need to freeze the type
   public transient Object frozenTypeDecl;

   public transient ISrcStatement fromStatement;

   // Used for serializing VariableDefinition metadata
   public transient Map<String,Object> annotations = null;
   public transient int modifierFlags = 0;
   public transient String enclosingTypeName = null;

   private static boolean wasBound = false;

   // Used for serializing only
   private Boolean writable = null;
   public transient boolean methodMetadata = false; // We create these VariableDefinition's for the metadata of a property - they are properties even if they are not part of a FieldDefinition

   public void init() {
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
         Statement def = getDefinition();
         Object annotObj = ModelUtil.getAnnotation(def, "sc.obj.GetSet");
         if (annotObj != null) {
            if (!(def instanceof FieldDefinition)) {
               displayError("@GetSet annotation invalid on non-fields: ");
            }
            else {
               Object value = ModelUtil.getAnnotationValue(annotObj, "value");
               if (value == null || (value instanceof Boolean && ((Boolean) value)))
                  convertGetSet = true;
            }
         }
         /*
          * TODO: If we have a field which lives in a type with an abstract getX and setX methods, but no implementation, should we set convertGetSet to
          * automatically fill that contract?  What are the side-effects of doing that for an abstract class where maybe a getX or setX is already implemented downstream?
          * Need to match the public/private modifiers... since a private field should not convertGetSet on an interface.
         if (!convertGetSet && def instanceof FieldDefinition) {
            TypeDeclaration enclType = getEnclosingType();
            Object getXMeth = enclType.definesMethod("get" + CTypeUtil.capitalizePropertyName(variableName), null, )
            if (getMeth.isAbstract() && modifiers match) convertGetSet = true;
         }
         */
      }
      super.init();
   }

   public void start() {
      if (started)
         return;
      super.start();

      Object annot = null;
      TypeDeclaration enclType = getEnclosingType();
      if (enclType != null && variableName != null) {
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

      if (!convertGetSet) {
         DBProvider dbProvider = ModelUtil.getDBProviderForProperty(getLayeredSystem(), getLayer(), this);
         if (dbProvider != null && dbProvider.getNeedsGetSet()) {
            convertGetSet = true;
         }
      }

      // Check to be sure the initializer is compatible with the property
      if (initializer != null) {
         Object varType = getTypeDeclaration();
         // TODO: or should we clone the type here?   the lambda expression will set type parameters to further refine the type
         if (varType instanceof ParamTypeDeclaration)
            ((ParamTypeDeclaration) varType).writable = true;

         initializer.setInferredType(varType, true);
         Object initType = initializer.getGenericType();
         if (initType != null && varType != null && !ModelUtil.isAssignableFrom(varType, initType, true, null, getLayeredSystem()) && (bindingDirection == null || bindingDirection.doForward())) {
            if (!ModelUtil.hasUnboundTypeParameters(initType)) {
               // Weird case - if this is a synchronization operation involving a remote method that is a 'void' return - we don't know that on the client and so create a field/variable assignment
               if (initType != Void.TYPE || getJavaModel().mergeDeclaration) {
                  displayTypeError("Type mismatch - assignment to variable with type: " + ModelUtil.getTypeName(varType, true, true) + " does not match expression type: " + ModelUtil.getTypeName(initType, true, true) + " for: ");
                  boolean xx = initType != null && varType != null && !ModelUtil.isAssignableFrom(varType, initType, true, null, getLayeredSystem()) && (bindingDirection == null || bindingDirection.doForward());
                  initType = initializer.getGenericType();
               }
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

      TypedDefinition tdef = (TypedDefinition) def;
      JavaType varType = tdef.type;
      if (varType == null)
         return null;
      Object type = varType.getTypeDeclaration();

      // Handles old school array dimensions after the variable name
      if (arrayDimensions == null)
         return type;
      return new ArrayTypeDeclaration(getLayeredSystem(), getEnclosingType(), type, arrayDimensions);
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
      String typeName;
      if (frozenTypeDecl != null)
         typeName = ModelUtil.getTypeName(frozenTypeDecl);
      else {
         TypedDefinition tdef = (TypedDefinition) def;
         if (tdef.type != null)
            typeName = tdef.type.toString(); // Used to use getFullTypeName here but for array types that returns the Class.getName() value - [[[i not int[][][]
         else
            typeName = "<no type>";
      }

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
         LayeredSystem sys = model == null ? null : model.getLayeredSystem();

         // TODO: if we've already transformed the model and are making it bindable, we have a problem. We can re-transform this model, but then
         // may need to retransform any model that depends on this model too because we changed the definition. This happens when we use an intermediate
         // build layer but don't make properties bindable that are later used in bindable expressions. The best from the code perspective to avoid that is to use annotations
         // to make them bindable before building the first time.
         //if (model != null)
         //   model.clearTransformed();

         // Only start checking after the current buildLayer is compiled.  Otherwise, this touches runtime classes
         // during the normal build which can suck in a version that will be replaced later on.
         // Don't do this test for the JS runtime... it forces us to load the js version of the class
         // Don't do this when we are being restarted - i.e. before we are validated - it's too soon to init the property cache
         if (sys != null && sys.buildLayer != null && sys.buildLayer.compiled && !sys.isDynamicRuntime() && isValidated()) {
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

      // TODO: Do we need this?
      //newVarDef.fromStatement = this;

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
         if (c != null && c.isPrimitive())
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
      if (errorArgs == null && !getParseNode().isErrorNode())
         ParseUtil.styleString(adapter, isStatic() ? "staticMember" : "member", (ParentParseNode) pnode.children.get(0), false);
      else
         ParseUtil.toStyledString(adapter, pnode.children.get(0));
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

   public boolean refreshBoundType(int flags) {
      boolean res = false;
      if (initializer != null)
         res = initializer.refreshBoundTypes(flags);
      return res;
   }

   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      if (initializer != null)
         initializer.addDependentTypes(types, mode);
   }

   public void setAccessTimeForRefs(long time) {
      if (initializer != null)
         initializer.setAccessTimeForRefs(time);
   }

   public void transformToJS() {
      if (arrayDimensions != null)
         setProperty("arrayDimensions", null);
      if (initializer != null) {
         initializer.transformToJS();

         if (initializer instanceof ArrayInitializer) {
            Statement st = getDefinition();
            if (st instanceof TypedDefinition && frozenTypeDecl != null) {
               ArrayInitializer arrInit = (ArrayInitializer) initializer;
               if (ModelUtil.isArray(frozenTypeDecl)) {
                  int numDims = ModelUtil.getArrayNumDims(frozenTypeDecl);
                  SemanticNodeList args = new SemanticNodeList<Expression>();
                  String prefix =  getLayeredSystem().runtimeProcessor.getStaticPrefix(ModelUtil.getArrayComponentType(frozenTypeDecl), this);
                  args.add(IdentifierExpression.create(prefix));
                  args.add(IntegerLiteral.create(numDims));
                  args.add(arrInit);
                  // TODO: add extra dims as args here
                  IdentifierExpression initExpr = IdentifierExpression.createMethodCall(args, "sc_initArray");
                  replaceChild(initializer, initExpr);
               }
               else
                  System.err.println("*** TODO: handle non arrays initialized by arrays?");
            }
         }
      }
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

   public boolean getIndexedProperty() {
      return indexedProperty;
   }

   public void setIndexedProperty(boolean v) {
      indexedProperty = v;
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
      varDef.annotations = ModelUtil.createAnnotationsMap(field.getAnnotations());
      varDef.modifierFlags = field.getModifiers();
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

   public int suggestCompletions(String prefix, Object currentType, ExecutionContext ctx, String command, int cursor, Set<String> candidates, Object continuation, int max) {
      if (initializer != null)
         return initializer.suggestCompletions(prefix, currentType, ctx, command, cursor, candidates, continuation, max);
      return -1;
   }

   public VariableDefinition refreshNode() {
      JavaModel oldModel = getJavaModel();
      Statement def = getDefinition();
      if (def instanceof FieldDefinition) {
         BodyTypeDeclaration enclType = getEnclosingType();
         if (enclType == null)
            return this;
         BodyTypeDeclaration type = enclType.refreshNode();
         if (type == null)
            return this;
         Object newField = type.declaresMember(variableName, MemberType.FieldSet, null, null);
         if (newField instanceof VariableDefinition)
            return (VariableDefinition) newField;
         System.err.println("Failed to find field on refresh " + variableName + " for: ");
         // TODO: debug only
         newField = type.declaresMember(variableName, MemberType.FieldSet, null, null);
      }
      else if (def instanceof VariableStatement) {
         AbstractMethodDefinition newMeth = getEnclosingMethod();
         VariableStatement varSt;
         if (newMeth != null) {
            varSt = (VariableStatement) newMeth.findStatement(def);
            if (varSt == null) {
               System.err.println("*** Can't refresh VarStatement from method");
               varSt = (VariableStatement) newMeth.findStatement(def);
            }
         }
         else {
            TypeDeclaration enclType = getEnclosingType();
            if (enclType == null)
               return this;
            BodyTypeDeclaration type = enclType.refreshNode();
            if (type == null)
               return this;
            varSt = (VariableStatement) type.findStatement((Statement) def);
            if (varSt == null)
               System.err.println("*** Can't refresh VarStatement from type");
         }
         if (varSt != null) {
            for (VariableDefinition varDef:varSt.definitions) {
               if (varDef.variableName.equals(variableName))
                  return varDef;
            }
            System.err.println("*** Can't find varDef");
         }
      }
      return this;
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
      if (initializer != null && initializer.findFromStatement(st) != null)
         return this;
      if (origInitializer != null && st instanceof VariableDefinition) {
         VariableDefinition varSt = (VariableDefinition) st;
         if (varSt.initializer != null && origInitializer.findFromStatement(varSt.initializer) != null)
            return this;
      }
      return null;
   }

   public ISrcStatement getFromStatement() {
      return fromStatement;
   }

   public boolean getNodeContainsPart(ISrcStatement partNode) {
      return this == partNode || sameSrcLocation(partNode);
   }

   @Override
   public int getNumStatementLines() {
      return ParseUtil.countLinesInNode(getParseNode());
   }

   public boolean childIsTopLevelStatement(ISrcStatement st) {
      return false;
   }

   public void addBreakpointNodes(List<ISrcStatement> res, ISrcStatement st) {
      ISrcStatement fromSt = findFromStatement(st);
      if (fromSt != null)
         res.add(fromSt);
   }

   // Here only for meta-data serialization purposes
   public int getModifierFlags() {
      Definition def = getDefinition();
      if (def == null)
         return modifierFlags;
      return getDefinition().getModifierFlags();
   }
   public void setModifierFlags(int flags) {
      this.modifierFlags = flags;
   }

   public String addNodeCompletions(JavaModel origModel, JavaSemanticNode origNode, String matchPrefix, int offset, String dummyIdentifier, Set<String> candidates, boolean nextNameInPath, int max) {
      if (initializer != null) {
         return initializer.addNodeCompletions(origModel, origNode, matchPrefix, offset, dummyIdentifier, candidates, nextNameInPath, max);
      }
      return super.addNodeCompletions(origModel, origNode, matchPrefix, offset, dummyIdentifier, candidates, nextNameInPath, max);
   }

   public String getArrayDimensions() {
      if (arrayDimensions != null)
         return arrayDimensions;
      Definition def = getDefinition();
      if (def instanceof TypedDefinition) {
         JavaType type = ((TypedDefinition) def).type;
         return type.arrayDimensions;
      }
      return null;
   }

   public boolean needsIndexedSetter() {
      String dims = getArrayDimensions();
      if (dims != null && dims.length() == 2 && convertGetSet) {
         LayeredSystem sys = getLayeredSystem();
         if (sys == null || !sys.useIndexSetForArrays)
            return false;
         return true;
      }
      return false;
   }

   public String getEnclosingTypeName() {
      return enclosingTypeName;
   }

   public boolean getWritable() {
      if (writable != null)
         return writable;
      Definition def = getDefinition();
      return def == null || !def.hasModifier("final");
   }

   public void setWritable(boolean v) {
      writable = v;
   }

   public boolean isProperty() {
      return methodMetadata || getDefinition() instanceof FieldDefinition;
   }

}

