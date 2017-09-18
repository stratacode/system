/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.*;
import sc.lang.sc.PropertyAssignment;
import sc.lang.template.Template;
import sc.lang.template.TemplateDeclaration;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.parser.ParseUtil;
import sc.type.CTypeUtil;
import sc.util.StringUtil;
import sc.lang.sc.IScopeProcessor;
import sc.lang.sc.ModifyDeclaration;

import java.util.*;

/** The semantic node class which is used for class and object types. */
public class ClassDeclaration extends TypeDeclaration {
   public JavaType extendsType;
   public String operator;

   private transient DeclarationType declarationType;

   public static ClassDeclaration create(String operator, String typeName, JavaType extendsType) {
      ClassDeclaration cd = new ClassDeclaration();
      cd.typeName = typeName;
      cd.operator = operator;
      if (extendsType != null)
         cd.setProperty("extendsType", extendsType);
      return cd;
   }

   public void init() {
      if (initialized) return;

      if (operator == null) {
         displayError("Invalid class - no operator - ");
      }
      else {
         char c = operator.charAt(0);
         switch (c) {
            case 'o':
               declarationType = DeclarationType.OBJECT;
               break;
            case 'c':
               declarationType = DeclarationType.CLASS;
               break;
            default:
               throw new UnsupportedOperationException();
         }
      }
      super.init();
   }

   public void start() {
      if (started) return;

      if (!(this instanceof AnonClassDeclaration)) {
         JavaModel thisModel = getJavaModel();
         Layer layer = thisModel.getLayer();
         // In the activated case, we have only one set of types we are really processing and we load from top-down.
         // TODO: In the inactive case, we are really processing each layer individually.  What we really need in this case is to
         // check the refLayer in the 'resolve' and walk up to find the valid type for that layer when a type is replaced.
         // For incompatible changes, this will cause errors - e.g. coreRuntime PTypeUtil and fullRuntime PTypeUtil.
         // This solution reuses replacedByType - which will resolve to the most specific type in the stack and use that to
         // process all layers.  When incompatible changes are made to a type, this won't resolve the earlier references properly.
         if (layer != null && !layer.activated) {
            if (!thisModel.isLayerModel) {
               String fullTypeName = getFullTypeName();
               if (fullTypeName == null) {
                  super.start();
                  // Perhaps a model fragment or something we can't really start
                  return;
               }
               TypeDeclaration prevDecl = thisModel.getPreviousDeclaration(fullTypeName);
               if (prevDecl != null && prevDecl != this && prevDecl.getFullTypeName().equals(fullTypeName))
                  prevDecl.updateReplacedByType(this);
            }
         }
      }

      // Need to set this before we start the nested components of this class in super.start().
      // We also could inject ourselves as the "resolver" node?  
      if (extendsType != null) {
         JavaModel m = getJavaModel();

         if (m != null) {
            // We don't want to resolve from this type cause we get into a recursive loop in findType.
            JavaSemanticNode resolver = getEnclosingType();
            if (resolver == null)
               resolver = m;
            if (!extendsType.isBound())
               extendsType.initType(getLayeredSystem(), this, resolver, null, false, isLayerType, null);

            // Need to start the extends type as we need to dig into it
            Object extendsTypeDecl = getDerivedTypeDeclaration();
            extendsTypeDecl = ParamTypeDeclaration.toBaseType(extendsTypeDecl);

            if (extendsTypeDecl instanceof TypeDeclaration) {
               TypeDeclaration extTypeDecl = (TypeDeclaration) extendsTypeDecl;
               if (extTypeDecl.getLayer() != null && getLayer() != null && extTypeDecl.getLayer().activated != getLayer().activated) {
                  System.out.println("*** Mismatching activated/inactived for base and extends type");
                  // TODO: DEBUG remove
                  extendsType.initType(getLayeredSystem(), this, resolver, null, false, isLayerType, null);
               }
            }
            if (extendsTypeDecl instanceof TypeDeclaration) {
               // When there's a custom resolver, we may be in a ModelStream which sets up a case where modify types in the same stream
               if (m.customResolver == null && ModelUtil.sameTypes(extendsTypeDecl, this)) {
                  displayTypeError("Cycle found in extends - class extends itself: ", extendsType.getFullTypeName(), " for ");
                  extendsInvalid = true;
               }
               else {
                  TypeDeclaration extendsTD = (TypeDeclaration) extendsTypeDecl;
                  if (extendsTD.isDynamicType()) {
                     setDynamicType(true);
                     dynamicNew = false;
                  }
                  if (extendsTD.dynamicNew)
                     extendsTD.clearDynamicNew();
                  if (m.layeredSystem != null) {
                     m.layeredSystem.addSubType(extendsTD, this);
                  }
                  startExtendedType(extendsTD, "extended");
               }
            }
            else if (extendsTypeDecl == null)
               extendsType.displayTypeError("Extends class not found: ", extendsType.getFullTypeName(), " for ");
         }
      }
      super.start();
   }

   public void validate() {
      if (validated) return;

      validateExtends();

      /*
       * If this type is getting compiled out and it has a binding in the property assignments that will get
       * rewritten, we need dynamic access for this type
       */
      if (!needsOwnClass(true)) {
         if (body != null) {
            for (Statement st:body) {
               if (st instanceof PropertyAssignment) {
                  if (((PropertyAssignment) st).bindingDirection != null) {
                     needsDynAccess = true;
                     break;
                  }
               }
            }
         }
      }

      super.validate();
   }

   private void validateExtends() {
      Object ext = getExtendsTypeDeclaration();
      if (ext != null && ext instanceof TypeDeclaration) {
         TypeDeclaration extTd = (TypeDeclaration) ext;
         if (extTd == this) {
            System.err.println("*** recursive extends loop for type: " + typeName);
            return;
         }
         if (!extTd.isStarted())
            extTd.start();
         if (!extTd.isValidated())
            extTd.validate();
      }
   }

   public void process() {
      if (processed) return;

      Object ext = getExtendsTypeDeclaration();
      if (ext != null && ext instanceof TypeDeclaration) {
         TypeDeclaration extTd = (TypeDeclaration) ext;
         if (!extTd.isProcessed())
            ((TypeDeclaration) ext).process();
      }

      super.process();
   }

   public void unregister() {
      super.unregister();
      Object ext = getExtendsTypeDeclaration();
      if (ext != null && ext instanceof TypeDeclaration) {
         JavaModel model = getJavaModel();
         if (model != null && model.layeredSystem != null)
            model.layeredSystem.removeSubType((TypeDeclaration) ext, this);
      }
   }

   public Object getDerivedTransformedTypeDeclaration() {
      if (extendsType != null) {
         if (extendsInvalid)
            return Object.class;
         Object res = extendsType.getTypeDeclaration();
         // Once a type has been transformed, we need to return the transformed type for the last ClassDeclaration in the modify.
         // Need this at least for the definesMemmber/definesMethod calls.
         if (res instanceof BodyTypeDeclaration && isTransformedType()) {
            BodyTypeDeclaration tdRes = ((BodyTypeDeclaration) res).getTransformedResult();
            if (tdRes != null)
               return tdRes;
         }
         return res;
      }
      return Object.class;
   }

   protected void updateBoundExtendsType(Object newType, Object oldType) {
      Object curType = null;
      if (extendsType != null && ((curType = extendsType.getTypeDeclaration()) == oldType || curType == newType)) {
         extendsType.setTypeDeclaration(newType);
         return;
      }
      if (implementsTypes != null) {
         for (JavaType implType:implementsTypes) {
            if ((curType = implType.getTypeDeclaration()) == oldType || curType == newType) {
               implType.setTypeDeclaration(newType);
               return;
            }
         }
      }
      if (oldType == Object.class || newType == Object.class)
         return;
      System.err.println("*** Failed to update type in updateBoundExtendsType: " + oldType + " ->" + newType);
   }

   public Object getDerivedTypeDeclaration() {
      if (extendsType != null) {
         if (extendsInvalid)
            return Object.class;
         return extendsType.getTypeDeclaration();
      }
      return Object.class;
   }


   public JavaType getExtendsType() {
      // If it is a cycle or not found, treat it like it is null or we can get in an infinite loop
      if (extendsInvalid)
         return null;
      return extendsType;
   }

   public JavaType getDeclaredExtendsType() {
      return getExtendsType();
   }

   public void modifyExtendsType(JavaType type) {
      extendsInvalid = false;
      extendsOverridden = false; // We might be replacing an overridden extends type here so clear this flag
      setProperty("extendsType", type);

      if (isValidated() && type != null)
         validateExtends();
      incrVersion();
   }

   public String getDerivedTypeName() {
      if (extendsType != null)
         return extendsType.getFullTypeName();
      return null;
   }

   public DeclarationType getDeclarationType() {
      if (declarationType == null && !initialized)
         init();
      return declarationType;
   }

   public void setDeclarationType(DeclarationType dt) {
      throw new UnsupportedOperationException();
   }

   /**
    * This is the code which implements an important optimization.  For any inner classes which do not have
    * any fields or methods, we skip generating the class.  Since you can still derive from them and add
    * property assignments, we need to merge those all together into the constructor for that type.
    */
   public void addUncompiledPropertyAssignments(TypeDeclaration outer, SemanticNodeList<Statement> assignments) {
      Object baseTypeObj = getDerivedTypeDeclaration();
      while (baseTypeObj instanceof ModifyDeclaration)
         baseTypeObj = ((ModifyDeclaration)baseTypeObj).getDerivedTypeDeclaration();

      // If this is a class, we have all of its assignments compiled in, but if it is a declaration we may need to include those
      // here.
      if (baseTypeObj instanceof ClassDeclaration) {

         ClassDeclaration baseType = (ClassDeclaration) baseTypeObj;
         baseType = (ClassDeclaration) baseType.getTransformedResult();
         /* If our base type is not the compiled class itself, we also need to collect its property assignments */
         if (baseType.getClassDeclarationForType() != baseType)
            baseType.addUncompiledPropertyAssignments(outer, assignments);
      }
      String varName = CTypeUtil.decapitalizePropertyName(typeName);
      skippedClassVarName = varName;

      // TODO: should we eliminate double-initializations here?  We do reorder initializers anyway when we
      // set them on the field definition so it seems like we could reorder them here... or even sort them
      // based on dependencies?
      for (int x = 0; x < 2; x++) {
         List<Statement> theBody = x == 0 ? body : hiddenBody;
         if (theBody == null)
            continue;

         for (int i = 0; i < theBody.size(); i++) {
            Statement s = theBody.get(i);
            if (s instanceof PropertyAssignment) {
               PropertyAssignment assign = (PropertyAssignment) s;
               Expression newAssign = assign.convertToAssignmentExpression(varName, true, assign.operator, false);
               newAssign.parentNode = assign.parentNode;
               ParseUtil.initAndStartComponent(newAssign);
               // Need to call this after we've copied the expression (and resolved references) since assign is possibly inherited.
               // For AssignmentExpressions' the lhs already has been redone so we only do the rhs.
               if (newAssign instanceof AssignmentExpression) {
                  ((AssignmentExpression) newAssign).rhs.changeExpressionsThis(this, outer, varName);
               }
               else {
                  newAssign.changeExpressionsThis(this, outer, varName);
               }

               // Convert {"a", "b"} to new String[] {"a", "b"}
               TransformUtil.convertArrayInitializerToNewExpression(newAssign);
               assignments.add(newAssign);
            }
         }
      }
   }

   public boolean transform(ILanguageModel.RuntimeType runtime) {
      boolean any = false;
      boolean isComponent = false;
      boolean isOverrideGet = false;
      boolean needsMemberCast = false;
      boolean isOverrideField = false;
      boolean typeIsComponentClass;

      if (!processed)
         process();

      Template customTemplate = null;
      List<Template> mixinTemplates = null;
      List<Template> staticMixinTemplates = null;

      boolean isObject = getDeclarationType() == DeclarationType.OBJECT;
      String childTypeName = null;
      String onInitMethodName = null;
      String overrideStartName = "start";
      String parentName;
      String rootName;
      Object[] propagateConstructorArgs = null;
      boolean constructorInit = false;
      boolean automaticConstructor = false;
      boolean needsOwnClass = needsOwnClass(true);
      boolean useAltComponent = false;
      JavaModel model = getJavaModel();
      LayeredSystem lsys = model.getLayeredSystem();

      // Capture this so we can rely on it when transforming sub-types.
      this.needsOwnClass = needsOwnClass;

      // This is the flag for serialized models.  We do not do any of the object level transformations on them but will do get/set conversions on children.
      if (!model.mergeDeclaration) {
         return super.transform(runtime);
      }

      /* Should we try to ensure all extended types are transformed, so that for example setX methods are created and resolvable
        * immediately by this class during transform?   That seems too challenging to guarantee.   The transform of a parent type
        * expects to transform each child-type in order during it's transform.  We could fix that by making that process more involved.
        * I'm not sure that just fixing that fixes everything though.  An alternative is to deactivate each node, then have the JS layer
        * (which needs the resolve) reactivate the nodes, re-resolving them if necessary.   Instead we ignore errors during transform
        * and re-resolve during the JS conversion process.  During the copy, if we detect a conflict - i.e. that after restarting the node
        * it did not map to the setX method we would expect, it's on the copier to detect that and fix the reference, or we could rewrite all of the
        * copy algorithms so that they do not restart at all - use the CopyInitLevels mode in deepCopy perhaps.
      if (extendsType != null) {
         Object extTD = getExtendsTypeDeclaration();
         if (extTD instanceof BodyTypeDeclaration)
            ((BodyTypeDeclaration) extTD).ensureTransformed();
      }
      */

      transformIFields();

      // First we process the set of inherited compiler settings
      List<Object> compilerSettingsList = getAllInheritedAnnotations("sc.obj.CompilerSettings");

      if (compilerSettingsList != null && compilerSettingsList.size() > 0) {
         customTemplate = findTemplate(compilerSettingsList, isObject && !useNewTemplate ? "objectTemplate" : "newTemplate",
                                       ObjectDefinitionParameters.class);
         Template mixinTemplate = findTemplate(compilerSettingsList, "mixinTemplate", ObjectDefinitionParameters.class);
         if (mixinTemplate != null) {
            mixinTemplates = new ArrayList<Template>();
            mixinTemplates.add(mixinTemplate);
         }

         Template staticMixinTemplate = findTemplate(compilerSettingsList, "staticMixinTemplate", ObjectDefinitionParameters.class);
         if (staticMixinTemplate != null) {
            staticMixinTemplates = new ArrayList<Template>();
            staticMixinTemplates.add(staticMixinTemplate);
         }

         String childTypeParameter;
         childTypeParameter = (String) ModelUtil.getAnnotationValueFromList(compilerSettingsList, "childTypeParameter");
         if (childTypeParameter != null && childTypeParameter.length() > 0)
            childTypeName = mapTypeParameterNameToTypeName(childTypeParameter);
         onInitMethodName = (String) ModelUtil.getAnnotationValueFromList(compilerSettingsList, "onInitMethod");
         Boolean bv;
         useAltComponent = (bv = (Boolean) ModelUtil.getAnnotationValueFromList(compilerSettingsList, "useAltComponent")) != null && bv;
         overrideStartName = (String) ModelUtil.getAnnotationValueFromList(compilerSettingsList, "overrideStartName");
         if (overrideStartName == null || overrideStartName.length() == 0)
            overrideStartName = useAltComponent ? "_start" : "start";
         String pConstructor = (String) ModelUtil.getAnnotationValueFromList(compilerSettingsList, "propagateConstructor");
         if (pConstructor != null && pConstructor.length() > 0) {
            String[] argTypeNames = StringUtil.split(pConstructor, ',');
            propagateConstructorArgs = new Object[argTypeNames.length];
            JavaModel m = getJavaModel();
            for (int i = 0; i < argTypeNames.length; i++) {
               propagateConstructorArgs[i] = m.findTypeDeclaration(argTypeNames[i], false);
               if (propagateConstructorArgs[i] == null)
                  displayError("Bad value to CompilerSettings.propagateConstructor annotation " + pConstructor + " no type: " + argTypeNames[i]);
            }
         }
         Boolean boolObj;
         constructorInit = (boolObj = (Boolean) ModelUtil.getAnnotationValueFromList(compilerSettingsList, "constructorInit")) != null && boolObj.booleanValue();
         automaticConstructor = (boolObj = (Boolean) ModelUtil.getAnnotationValueFromList(compilerSettingsList, "automaticConstructor")) != null && boolObj.booleanValue();
      }

      // Need to do this before we retrieve CompilerSettings so we can inherit settings via the interface we add below.
      ArrayList<IDefinitionProcessor> defProcs = getAllDefinitionProcessors();

      String customResolver = null;
      String customSetter = null;
      boolean needsField = true;
      ArrayList<String> preAssignments = null;
      ArrayList<String> postAssignments = null;
      if (defProcs != null) {
         for (IDefinitionProcessor proc:defProcs) {
            String[] scopeInterfaces = proc.getAppendInterfaces();
            if (scopeInterfaces != null) {
               for (int si = 0; si < scopeInterfaces.length; si++) {
                  JavaType scopeType = ModelUtil.getJavaTypeFromTypeOrParamName(this, scopeInterfaces[si]);
                  addImplements(scopeType);
               }
            }
            String procCustomResolver = proc.getCustomResolver();
            if (procCustomResolver != null) {
               if (customResolver != null)
                  System.err.println("*** Warning: type - " + typeName + " already has a customResolver: " + customResolver + " being replaced by annotation: " + proc);
               customResolver = procCustomResolver;
            }
            String procCustomSetter = proc.getCustomSetter();
            if (procCustomSetter != null) {
               if (customSetter != null)
                  System.err.println("*** Warning: type - " + typeName + " already has a customSetter: " + customResolver + " being replaced by annotation: " + proc);
               customSetter = procCustomSetter;
            }
            boolean procNeedsField = proc.getNeedsField();
            if (!procNeedsField) {
               needsField = procNeedsField;
            }
            String procPreAssignment = proc.getPreAssignment();
            if (procPreAssignment != null) {
               if (preAssignments == null)
                  preAssignments = new ArrayList<String>();
               preAssignments.add(procPreAssignment);
            }

            String procPostAssignment = proc.getPostAssignment();
            if (procPostAssignment != null) {
               if (postAssignments == null)
                  postAssignments = new ArrayList<String>();
               postAssignments.add(procPostAssignment);
            }

            String procMixin = proc.getMixinTemplate();
            if (procMixin != null) {
               if (mixinTemplates == null)
                  mixinTemplates = new ArrayList<Template>();
               mixinTemplates.add(findTemplatePath(procMixin, "mixinTemplate", ObjectDefinitionParameters.class));
            }

            String procStaticMixin = proc.getStaticMixinTemplate();
            if (procStaticMixin != null) {
               if (staticMixinTemplates == null)
                  staticMixinTemplates = new ArrayList<Template>();
               staticMixinTemplates.add(findTemplatePath(procStaticMixin, "staticMixinTemplate", ObjectDefinitionParameters.class));
            }
         }
      }

      // Don't inherit this set of compiler settings
      Object myCompilerSettings = getAnnotation("sc.obj.CompilerSettings");
      if (myCompilerSettings != null) {
         String jarFile = (String) ModelUtil.getAnnotationValue(myCompilerSettings, "jarFileName");
         Boolean includeDeps = (Boolean) ModelUtil.getAnnotationValue(myCompilerSettings, "includeDepsInJar");
         if (jarFile != null && jarFile.length() > 0) {
            String [] jarPackages = (String[]) ModelUtil.getAnnotationValue(myCompilerSettings, "jarPackages");
            lsys.buildInfo.addModelJar(model, null, jarFile, jarPackages == null ? null : jarPackages.length > 0 ? jarPackages : null, false, includeDeps == null || includeDeps);
         }
         jarFile = (String) ModelUtil.getAnnotationValue(myCompilerSettings, "srcJarFileName");
         if (jarFile != null && jarFile.length() > 0) {
            String [] jarPackages = (String[]) ModelUtil.getAnnotationValue(myCompilerSettings, "jarPackages");
            lsys.buildInfo.addModelJar(model, null, jarFile, jarPackages == null ? null : jarPackages.length > 0 ? jarPackages : null, true, false);
         }
      }

      if (isAutoComponent()) {

         // If it doesn't need its own class, don't transform it - do mark it as a component type though for
         // template generation purposes.
         if (needsOwnClass)
            transformComponent(runtime, overrideStartName, useAltComponent);
         any = true;
         isComponent = true;
      }

      // If there are subobjects even for a simple class, we need to implement the getObjChildren method
      boolean hasSubobjects = false;
      if (!isObject || isComponent)
         hasSubobjects = hasInnerObjects();

      if (isComponent && constructorInit) {
         displayError("Component types cannot have CompilerSettings.constructorInit=true for: ");
      }

      boolean inHiddenBody = false;
      if (((isObject || isComponent || hasSubobjects) && !hasModifier("abstract")) || customTemplate != null || mixinTemplates != null || staticMixinTemplates != null) {
         TypeDeclaration outer = getEnclosingType();
         inHiddenBody = (outer != null && outer.hiddenBody != null && outer.hiddenBody.contains(this));

         // Compiled outer type and dynamic inner type - currently not supporting this... instead, the outer type should be made dynamic.
         // This decision is based on the idea that if we are recompiling the outer type anyway, might as well make it dynamic.  If that ends up making
         // too much code dynamic we could handle this by: 1) adding a generateDynamicStub call here.  2) pulling the code which generates the getX call from
         // the dyn stub template into this class. 3) removing this type from the the outer class.
         //
         // A special case here is the swing main's use of inner object definitions to instantiate a type.  those never need a concrete class and for some reason right now
         // Main is compiled and the inner type is dynamic.  In that case though, needsOwnClass is false and it just works.
         if (isDynamicType() && needsOwnClass) {
            System.out.println("*** error: compiled outer class has dynamic inner class!");
         }

         String objectClassName;
         String variableTypeName;
         ClassDeclaration accessClass;
         String newModifiers;
         Object compiledClass;

         if (outer != null) {
            if (!(outer instanceof ClassDeclaration)) {
               // The template declaration may contain a class but we've already made a copy of this type elsewhere and this does not affect the saved file so just ignore it.
               if (outer instanceof TemplateDeclaration)
                  return false;
               // If we are merging, all objects should get tranformed
               if (model.mergeDeclaration) {
                  displayError("object tag cannot be a member of: " + outer.getDeclarationType() + " for type: ");
                  return false;
               }
            }

            accessClass = (ClassDeclaration) outer.getClassDeclarationForType();

            compiledClass = getClassDeclarationForType();
            typeIsComponentClass = compiledClass != this && !ModelUtil.isObjectType(compiledClass) && ModelUtil.isComponentType(compiledClass);
            objectClassName = ModelUtil.getTypeName(compiledClass, false, true);
            variableTypeName = objectClassName;

            // Use accessBase here because the object in accessClass will shadow the get method in the derived class - in other words, we should not be able to resolve
            // the getX method from accessClass because it is hidden.
            Object accessBase = accessClass.getDerivedTypeDeclaration();
            Object overrideDef = accessBase == null ? null : ModelUtil.definesMember(accessBase, typeName, MemberType.GetMethodSet, null, null, false, true, getLayeredSystem());
            if (overrideDef != null) {
               mergeModifiers(overrideDef, false, true);

               // If it is a real property, we use the setX method to override the value.  If we are overriding an object
               // definition or a read-only property, we just override the getX method to create our new object.
               if (ModelUtil.hasSetMethod(overrideDef)) {
                  isOverrideGet = true;
                  // Can override getX with a subtype of the type.  Does mean we need to cast the call to super.get()
                  // and that it won't have a default incorrectly typed value that we need to toss?
                  String overrideTypeName = ModelUtil.getTypeName(ModelUtil.getVariableTypeDeclaration(overrideDef));
                  needsMemberCast = !overrideTypeName.equals(variableTypeName);
               }
            }

            // If we have an object X which overrides a property in the base class 'x' or 'X' we do an override.
            if (isObject) {
               // Check both cases here since type names might match the field name or be the upper case version.
               overrideDef = accessClass.definesMember(typeName, MemberType.FieldSet, this, null, false, true);
               if (overrideDef == null)
                  overrideDef = accessClass.definesMember(CTypeUtil.decapitalizePropertyName(typeName), MemberType.FieldSet, accessClass, null, false, true);
               if (overrideDef != null) {
                  if (ModelUtil.isAssignableFrom(ModelUtil.getPropertyType(overrideDef), this)) {
                     mergeModifiers(overrideDef, false, true);
                     isOverrideField = true;
                     //see above
                     String overrideTypeName = ModelUtil.getTypeName(ModelUtil.getVariableTypeDeclaration(overrideDef));
                     needsMemberCast = !overrideTypeName.equals(variableTypeName);
                  }
               }
               // Also need to check if we inherit an object definition for this type which has not yet been transformed.  If so, it will be a field.
               if (overrideDef == null) {
                  Object extType = accessClass.getExtendsTypeDeclaration();
                  if (extType != null) {
                     overrideDef = ModelUtil.definesMember(extType, typeName, MemberType.ObjectTypeSet, this, null, false, true, getLayeredSystem());
                     if (overrideDef != null) {
                        if (ModelUtil.isAssignableFrom(overrideDef, this)) {
                           isOverrideField = true;
                           needsMemberCast = !ModelUtil.sameTypes(overrideDef, this);
                           mergeModifiers(overrideDef, false, true);
                        }
                     }
                  }
               }
            }
            newModifiers = modifiersToString(false, true, false, false, false, null);

            if (!hasModifier("static")) {
               parentName = "this";
               ClassDeclaration rootType = (ClassDeclaration) outer.getRootType();
               if (rootType == null)
                  rootName = parentName;
               else {
                  rootName = rootType.getCompiledClassName() + ".this";
               }
            }
            else {
               // TODO - if these are objects, it seems like we could just use the getX methods here?
               rootName = parentName = null;
            }
         }
         else {
            rootName = parentName = null;
            newModifiers = modifiersToString(false, true, false, false, false, null);
            if (!constructorInit) {
               if (newModifiers.indexOf("static") == -1) {
                  if (newModifiers.length() == 0)
                     newModifiers = "static";
                  else
                     newModifiers = newModifiers + " static";
               }
            }
            compiledClass = accessClass = (ClassDeclaration) getClassDeclarationForType();
            typeIsComponentClass = accessClass != this && !ModelUtil.isObjectType(accessClass) && ModelUtil.isComponentType(accessClass);
            variableTypeName = objectClassName = ModelUtil.getTypeName(accessClass);
         }

         StringBuilder childNames = new StringBuilder();
         Map<String,StringBuilder> childNamesByScope = new HashMap<String,StringBuilder>();
         LinkedHashSet<String> objNames = new LinkedHashSet<String>();
         int numChildren = addChildNames(childNames, childNamesByScope, !constructorInit ? CTypeUtil.decapitalizePropertyName(typeName) : null, false, false, false, objNames);

         SemanticNodeList<Statement> assignments = null;
         int transformIx = -1;

         // Do the ext uncompiled assignments ahead of this types so that we override them property
         Object extType = getDerivedTypeDeclaration();
         while (extType instanceof ModifyDeclaration)
            extType = ((ModifyDeclaration)extType).getDerivedTypeDeclaration();

         if (needsOwnClass) {
            if (extType instanceof BodyTypeDeclaration) {
               BodyTypeDeclaration extTD = (BodyTypeDeclaration) extType;
               extType = extTD.getTransformedResult();
            }
            if (extType instanceof ClassDeclaration) {
               ClassDeclaration extDecl = (ClassDeclaration) extType;
               Object extClassDecl = extDecl.getClassDeclarationForType();
               /* If our extends type is not a real type, we need to change it to a real type */
               if (extClassDecl != extDecl) {
                  if (assignments == null)
                     assignments = new SemanticNodeList<Statement>();
                  ClassType ctype = (ClassType) extendsType;
                  // TODO: deal with "a.b" here?
                  ctype.setFullTypeName(ModelUtil.getTypeName(extClassDecl));
                  extDecl.addUncompiledPropertyAssignments(outer, assignments);
               }
            }
         }

         if (outer != null && !needsOwnClass) {
            if (assignments == null)
               assignments = new SemanticNodeList<Statement>();

            addUncompiledPropertyAssignments(outer, assignments);
            if (assignments.size() == 0)
               assignments = null;

            int ix = outer.body.indexOf(this);
            if (ix == -1)
                System.out.println("Unable to find object to during transform");
            else {
               // Remove this declaration since we'll use our parent type.
               outer.body.remove(ix);
               outer.addToHiddenBody(this);

               transformIx = ix;
            }
         }
         else if (isObject) {
            // Convert this ito a class definition
            setProperty("operator", "class");
         }

         MethodDefinition meth = null;
         if (constructorInit) {
            meth = prepareConstructorInit(propagateConstructorArgs);
         }

         ObjectDefinitionParameters params;
         IScopeProcessor scopeProc = getScopeProcessor();

         // This is done before we process the regular object definition as the scope template might add a constructor
         // that we need to process later.
         if (scopeProc != null) {
            String scopeTemplateName;
            if (isObject)
               scopeTemplateName = scopeProc.getObjectTemplate();
            else
               scopeTemplateName = scopeProc.getNewTemplate();

            if (scopeTemplateName != null) {
               Template scopeTemplate = findTemplatePath(scopeTemplateName, "scopeTemplate", ObjectDefinitionParameters.class);
               StringBuilder locChildNames = new StringBuilder();
               Map<String,StringBuilder> locChildNamesByScope = new HashMap<String,StringBuilder>();

               // Keep these in order so we can use them to build the comma separated string literal names for use by the
               // templates
               Set<String> childObjNames = new LinkedHashSet<String>();

               // The scope template also runs in the context
               int locNumChildren = addChildNames(locChildNames, locChildNamesByScope, null, false, false, false, childObjNames);

               params = new ObjectDefinitionParameters(compiledClass, objectClassName, variableTypeName, this, newModifiers,
                                             locChildNames, locNumChildren, locChildNamesByScope, ModelUtil.convertToCommaSeparatedStrings(childObjNames), isOverrideField,
                                             isOverrideGet, needsMemberCast, isComponent, typeIsComponentClass, childTypeName, parentName, rootName,
                                             null, "", "", this, useAltComponent, customResolver, customSetter, needsField, preAssignments, postAssignments);
               TransformUtil.addObjectDefinition(this, this, params,
                       assignments, scopeTemplate, isObject && !useNewTemplate, isComponent, inHiddenBody);
            }
         }

         // This template gives types the ability to override a method in each definition to collection the
         // children.
         if (mixinTemplates != null || staticMixinTemplates != null) {
            StringBuilder locChildNames = new StringBuilder();
            Map<String,StringBuilder> locChildNamesByScope = new HashMap<String,StringBuilder>();
            Set<String> locObjNames = new LinkedHashSet<String>();

            // The mixin template, like the constructorInit method is local and so doesn't need the prefix
            int locNumChildren = addChildNames(locChildNames, locChildNamesByScope, null, false, false, false, locObjNames);

            params = new ObjectDefinitionParameters(compiledClass, objectClassName, variableTypeName, this, newModifiers,
                    locChildNames, locNumChildren, locChildNamesByScope, ModelUtil.convertToCommaSeparatedStrings(locObjNames), isOverrideField,
                    isOverrideGet, needsMemberCast, isComponent, typeIsComponentClass, childTypeName, parentName, rootName, null, "", "", this, useAltComponent, customResolver, customSetter, needsField, preAssignments, postAssignments);

            if (mixinTemplates != null) {
               for (Template mixinTemplate:mixinTemplates) {
                  TransformUtil.addObjectDefinition(this, this, params, assignments, mixinTemplate, false, isComponent, inHiddenBody);
               }
            }
            if (staticMixinTemplates != null) {
               for (Template staticMixinTemplate:staticMixinTemplates) {
                  // Make sure to put the static mix template code into the root type of the type hierarchy since Java does not like static code in inner classes.
                  TransformUtil.addObjectDefinition(this.getEnclosingType() == null ? this : this.getRootType(), this, params, assignments, staticMixinTemplate, false, isComponent, inHiddenBody);
               }
            }
         }

         Object[] constructors = null;
         int i = 0;
         String constDecls, constParams;
         Object currentConstructor;
         do {
            if ((isObject && !useNewTemplate) || (constructors = getConstructors(null)) == null || constructors.length == 0 || automaticConstructor) {
               if (propagateConstructorArgs != null) {
                  constDecls = getDeclsFromTypes(propagateConstructorArgs);
                  constParams = getParamsFromTypes(propagateConstructorArgs);
               }
               else {
                  constDecls = "";
                  constParams = "";
               }
               currentConstructor = null;
            }
            else {
               currentConstructor = constructors[i];
               constDecls = ModelUtil.getParameterDecl(currentConstructor);
               String[] pnames = ModelUtil.getParameterNames(currentConstructor);
               if (pnames == null)
                  constParams = "";
               else
                  constParams = StringUtil.arrayToString(pnames);

               // Don't propagate if there are already constructors here
               propagateConstructorArgs = null;
            }

            params = new ObjectDefinitionParameters(compiledClass, objectClassName, variableTypeName, this, newModifiers,
                          childNames, numChildren, childNamesByScope, ModelUtil.convertToCommaSeparatedStrings(objNames), isOverrideField,
                    isOverrideGet, needsMemberCast, isComponent, typeIsComponentClass, childTypeName, parentName, rootName, currentConstructor,
                    constDecls, constParams, accessClass, useAltComponent, customResolver, customSetter, needsField, preAssignments, postAssignments);

            TransformUtil.addObjectDefinition(accessClass, this, params,
                                              assignments, customTemplate, isObject && !useNewTemplate, isComponent, inHiddenBody);
                                              
            i++;
         } while (constructors != null && i < constructors.length);

         if (constructorInit) {
            completeConstructorInit(propagateConstructorArgs, meth);
         }

         Statement transformObj = null;
         if (transformIx != -1 && transformIx < outer.body.size()) {
            // Because we just remove the current node, we need to transform the node in its new slot
            // Do it after we've added this definition though or it won't be able to resolve references
            // back to this guy during this transformation.  Need to check the body.size here since we
            // likely just added to the body above.
            transformObj = outer.body.get(transformIx);
         }

         if (transformObj != null && transformObj.transform(runtime))
            any = true;

         any = true;
      }

      if (isObject || propertiesAlreadyBindable != null) {
         if (getAnnotation("sc.obj.TypeSettings") == null) {
            Annotation annot = Annotation.create("sc.obj.TypeSettings");
            ArrayList<AnnotationValue> annotVals = new ArrayList<AnnotationValue>();
            if (isObject)
               annotVals.add(AnnotationValue.create("objectType", Boolean.TRUE));
            if (propertiesAlreadyBindable != null)
               annotVals.add(AnnotationValue.create("bindableProps", propertiesAlreadyBindable));
            annot.addAnnotationValues(annotVals.toArray(new AnnotationValue[annotVals.size()]));
            addModifier(annot);
         }
      }

      /** For types like Android's activity which get created externally, this provides the init hook to use */
      if (onInitMethodName != null && onInitMethodName.length() > 0) {
         insertInitMethodCall(onInitMethodName);
      }

      // Do not propagate the construct if we are not generating a type for it.  Or should this flag force needsOwnClass=true
      // if it is set?   Should work either way but adding these methods will cause "needsOwnClass" to subsequently return true which messes up
      // getCompiledClassName for this class.
      if (propagateConstructorArgs != null && needsOwnClass) {
         String simpleTypeName = CTypeUtil.getClassName(typeName);
         List constParams = Arrays.asList(propagateConstructorArgs);
         Object constMeth = definesConstructor(constParams, null, false);
         if (constMeth == null) {
            displayError("No matching constructor found for CompilerSettings.propagateConstructor annotation: " + Arrays.asList(propagateConstructorArgs) + " for: ");
         }
         else {
            if (declaresConstructor(constParams, null) == null) {
               TransformUtil.defineRedirectMethod(this, simpleTypeName, constMeth, true, true);
            }
         }
      }

      // Remove all dynamic interfaces
      if (implementsBoundTypes != null) {
         int j = 0;
         for (int i = 0; i < implementsBoundTypes.length; i++) {
            Object impl = implementsBoundTypes[i];
            if (ModelUtil.isDynamicType(impl)) {
               // TODO: either make the implementing class compiled or force the implemented class to be dynamic.  Either way it's tricky to propagate the compiled dynamic type flag... it gets set
               // from modified types and then must propagate through various dependencies.
               System.err.println("*** Warning: compiled class implementing dynamic type - not supported: " + typeName + " implements: " + implementsTypes.get(j));
               implementsTypes.remove(j);
            }
            else
               j++;
         }
         if (implementsTypes.size() == 0)
            setProperty("implementsTypes", null);
      }

      if (super.transform(runtime))
         any = true;
      return any;
   }

   private String getDeclsFromTypes(Object[] types) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < types.length; i++) {
         if (i != 0)
            sb.append(", ");
         sb.append(ModelUtil.getTypeName(types[i]));
         sb.append(" ");
         sb.append("_p");
         sb.append(i);
      }
      return sb.toString();
   }

   private String getParamsFromTypes(Object[] types) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < types.length; i++) {
         if (i != 0)
            sb.append(", ");
         sb.append("_p");
         sb.append(i);
      }
      return sb.toString();
   }

   private void completeConstructorInit(Object[] constArgs, MethodDefinition meth) {
      List constParams = constArgs == null ? null : Arrays.asList(constArgs);
      ConstructorDefinition constMeth = (ConstructorDefinition) declaresConstructor(constParams, null);
      if (constMeth == null)
         displayError("No constructor defined with types: " + constParams + " with constructorInit=true for: ");
      else
         constMeth.overrides = meth;
   }

   private MethodDefinition prepareConstructorInit(Object[] constArgs) {
      List constParams = constArgs == null ? null : Arrays.asList(constArgs);
      ConstructorDefinition constMeth = (ConstructorDefinition) declaresConstructor(constParams, null);
      if (constMeth != null) {
         constMeth.overriddenMethodName = typeName + "_orig";
         return constMeth.convertToMethod(constMeth.overriddenMethodName);
      }
      return null;
   }

   private void insertInitMethodCall(String onInitMethodName) {
      Object[] meths = ModelUtil.getMethods(this, onInitMethodName, null);
      if (meths == null || meths.length == 0)
         displayError("Type: " + getTypeName() + " has CompilerSettings.onInitMethod=" + onInitMethodName + " but no methods with that name: ");
      else {
         for (int i = 0; i < meths.length; i++) {
            Object meth = meths[i];
            Object declMethObj = declaresMethod(onInitMethodName, Arrays.asList(ModelUtil.getParameterTypes(meth)), null, null, false, null, null, false);
            MethodDefinition declMeth;
            if (declMethObj == null) {
               declMeth = (MethodDefinition) TransformUtil.defineRedirectMethod(this, onInitMethodName, meth, false, !ModelUtil.isAbstractMethod(meth));
            }
            else if (!(declMethObj instanceof MethodDefinition)) {
               // Don't think this will happen but just in case...
               displayError("Unable to perform onInitMethod replacement");
               continue;
            }
            else
               declMeth = (MethodDefinition) declMethObj;

            Statement superSt = IdentifierExpression.create("_init");
            superSt.setProperty("arguments", new SemanticNodeList());
            declMeth.addBodyStatementAt(0, superSt);
         }
      }
   }

   void transformComponent(ILanguageModel.RuntimeType runtime, String overrideStartName, boolean useAltComponent) {
      //   for each 2nd pass initializer: null out/remove the assignment where it is and put it into the initvars method
      //   for each component reference: nest the recursive initialize and start methods

      List<Statement> refInitializers = getReferenceInitializers();
      List<FieldDefinition> componentFields = getComponentFields();

      Object extendsType = getConcreteExtendsType();

      boolean isFirst = false;
      boolean extendsIsComponent = false;

      StringBuilder childCompNames = new StringBuilder();
      int numCompChildren = addChildNames(childCompNames, null, null, true, true, false, new TreeSet<String>());

      // If this class does not inherit from IComponent, we need to do more work:
      if (extendsType == null || !(extendsIsComponent = ModelUtil.isComponentType(extendsType))) {
         isFirst = true;
         // TODO: for now, basing this off of whether this has been set.  Should probably just get rid of overrideStartName in favor of the AltComponent interface
         if (overrideStartName.equals("start") || overrideStartName.equals("_start")) {
            if (!useAltComponent)
               addImplements(ClassType.create(TransformUtil.COMPONENT_INTERFACE));
            else
               addImplements(ClassType.create(TransformUtil.ALT_COMPONENT_INTERFACE));
         }

         TransformUtil.addInitedDefinition(this, useAltComponent);
      }

      String preInitName = useAltComponent ? "_preInit" : "preInit";

      MethodDefinition preInit = (MethodDefinition) declaresMethodDef(preInitName, null);
      if (preInit == null && (refInitializers != null || isFirst /* || numCompChildren > 0*/)) {
         preInit = defineInitMethod(preInitName, "public");
      }

      // We may not need to generate at all if no variable initializers and not the first class
      if (preInit != null) {
         if (refInitializers != null) {
            TransformUtil.convertArrayInitializersToNewExpressions(refInitializers);
            preInit.addStatementsAt(0, refInitializers);
         }
         // Add super.preInit() as the first statement

         addGuardAndSuperInitCall(preInit, preInitName, preInitName, 1, extendsIsComponent);
         // This is not required since typically the getX call will also access the children.
         // The reason we might need this is that sometimes the template wants the children
         // that have already been initialized.  In these cases, children's preInit will get called after
         // referenced properties have init/start called.  If you do add this call, you need to uncomment
         // || numCompChildren above.
         //addChildInitCall(preInit, numCompChildren, childCompNames, "preInit");
      }

      String initName = useAltComponent ? "_init" : "init";

      MethodDefinition initMethod = (MethodDefinition) declaresMethodDef(initName, null);
      if (initMethod == null && (componentFields != null || isFirst || numCompChildren > 0)) {
         initMethod = defineInitMethod(initName, "public");
      }

      if (initMethod != null) {
         if (componentFields != null)
            addFieldInitCalls(initMethod, componentFields, initName, useAltComponent);

         addGuardAndSuperInitCall(initMethod, initName, initName, 2, extendsIsComponent);
         // Note: needs "init", not initName here because this goes into the DynUtil.initComponent
         addChildInitCall(initMethod, numCompChildren, childCompNames, "init");
      }

      String startName = useAltComponent ? "_start" : "start";

      MethodDefinition startMethod = (MethodDefinition) declaresMethodDef(overrideStartName, null);
      if (startMethod == null && (componentFields != null || isFirst || numCompChildren > 0)) {
         if (overrideStartName.equals(startName))
            startMethod = defineInitMethod(startName, "public");
         else {
            List methods = getMethods(overrideStartName, null);
            if (methods == null || methods.size() == 0)   {
               displayError("overrideStartName refers to non-existent method");
            }
            else if (methods.size() != 1) {
               displayError("overrideStartName refers to overloaded method: " + methods);
            }
            else {
               Object superStart = methods.get(0);
               AccessLevel lev = ModelUtil.getAccessLevel(superStart, false);
               startMethod = defineInitMethod(overrideStartName, lev.levelName);
               Object[] excTypes = ModelUtil.getExceptionTypes(superStart);
               if (excTypes != null && excTypes.length > 0)
                  startMethod.setExceptionTypes(excTypes);
            }
         }
      }

      if (startMethod != null) {
         if (componentFields != null)
            addFieldInitCalls(startMethod, componentFields, startName, useAltComponent);

         addGuardAndSuperInitCall(startMethod, startName, overrideStartName, 3, extendsIsComponent);
         // Note: needs "start", not startName here because this goes into the DynUtil.startComponent
         addChildInitCall(startMethod, numCompChildren, childCompNames, "start");
      }

      String stopName = useAltComponent ? "_stop" : "stop";

      MethodDefinition stopMethod = (MethodDefinition) declaresMethodDef(stopName, null);
      if (stopMethod == null && (componentFields != null || isFirst || numCompChildren > 0)) {
         stopMethod = defineInitMethod(stopName, "public");
      }

      if (stopMethod != null) {
         addGuardAndSuperInitCall(stopMethod, stopName, stopName, 4, extendsIsComponent);
      }
   }

   private void addChildInitCall(MethodDefinition meth, int numCompChildren, StringBuilder childCompNames, String initMethodName) {
      if (numCompChildren == 0)
         return;

      ClassType cType = (ClassType) ClassType.create("Object");
      cType.arrayDimensions = "[]";
      VariableStatement varStatement = VariableStatement.create(cType, "_children", "=",
                                           ArrayInitializer.createFromExprNames(numCompChildren,childCompNames));
      meth.addStatementIndent(varStatement);

      IdentifierExpression loopBody = IdentifierExpression.create("sc.dyn.DynUtil." + initMethodName + "Component");
      SemanticNodeList<Expression> args = new SemanticNodeList<Expression>();
      loopBody.setProperty("arguments", args);
      IdentifierExpression initCall = IdentifierExpression.create("_c");
      args.add(initCall);
      cType = (ClassType) ClassType.create("Object");
      ForVarStatement loop = ForVarStatement.create(cType, "_c",
              IdentifierExpression.create("_children"), loopBody);
      meth.addStatement(loop);
   }

   private void addGuardAndSuperInitCall(MethodDefinition method, String name, String superName, int level, boolean extendsIsComponent) {
      // if _initState > level return;  _initState = level;
      Expression ce = ConditionalExpression.create(IdentifierExpression.create("_initState"),">", IntegerLiteral.create(level-1));

      IfStatement ifStatement = new IfStatement();
      ifStatement.setProperty("expression", ParenExpression.create(ce));
      ifStatement.setProperty("trueStatement", ReturnStatement.create(null));
      ifStatement.fromStatement = this;

      int spot = 0;

      method.addStatementAt(spot++, ifStatement);

      Object extendsMethod;

      // super.method() - either, there is a preInit method or we will generate one anyway cause it is a component too
      if (extendsIsComponent || (extendsMethod = extendsDefinesMethod(name, null, null, null, false, false, null, null)) != null &&
          (ModelUtil.hasModifier(extendsMethod, "public") || ModelUtil.hasModifier(extendsMethod, "protected"))) {
         IdentifierExpression ie = IdentifierExpression.create("super", superName);
         ie.setProperty("arguments", new SemanticNodeList(0));
         ie.fromStatement = this;
         method.addStatementAt(spot++, ie);
      }
      AssignmentExpression ae = AssignmentExpression.create(IdentifierExpression.create("_initState"), "=", IntegerLiteral.create(level));
      ae.fromStatement = this;
      method.addStatementAt(spot++, ae);
   }

   private MethodDefinition defineInitMethod(String name, String accessModifier) {
      MethodDefinition initMethod = new MethodDefinition();
      initMethod.addModifier(accessModifier);
      initMethod.name = name;
      PrimitiveType pt = new PrimitiveType();
      pt.typeName = "void";
      initMethod.setProperty("type", pt);

      TransformUtil.appendIndentIfNecessary((SemanticNodeList) body);
      addBodyStatement(initMethod);
      return initMethod;
   }

   public List<Statement> getReferenceInitializers() {
      if (body == null)
         return null;

      List<Statement> referenceInits = new ArrayList<Statement>();

      for (Statement s:body) {
         s.collectReferenceInitializers(referenceInits);
      }
      return referenceInits.size() > 0 ? referenceInits : null;
   }

   private Statement createCallToInitMethod(JavaType fieldType, String variableName, String methodName, boolean useAltComponent) {
      Statement initStatement;
      // Note: this used to use getCompiledClass here but we do not want to load the compiled classes during transform, unless they are final
      if (ModelUtil.isComponentType(fieldType.getTypeDeclaration())) {
         IdentifierExpression initCall = IdentifierExpression.create(variableName, methodName);
         initCall.setProperty("arguments", new SemanticNodeList(0));
         initStatement = initCall;
      }
      else {
         VariableSelector vsel = new VariableSelector();
         vsel.identifier = methodName;
         vsel.setProperty("arguments", new SemanticNodeList(0));

         // ((IComponent) variableName).init()
         initStatement = SelectorExpression.create(ParenExpression.create(CastExpression.create(useAltComponent ? "sc.obj.IAltComponent" : "sc.obj.IComponent",
                                                                                                IdentifierExpression.create(variableName))), vsel);
      }
      initStatement.fromStatement = this;
      return initStatement;
   }

   public void addFieldInitCalls(MethodDefinition initMethod, List<FieldDefinition> fieldsToInit, String methodName, boolean useAltComponent) {
      int i = 0;
      for (FieldDefinition field:fieldsToInit) {
         for (VariableDefinition v:field.variableDefinitions) {
            Statement initStatement;
            JavaType fieldType = field.type;
            if (fieldType.arrayDimensions == null && !ModelUtil.isAssignableFrom(Collection.class, fieldType.getTypeDeclaration())) {
               initStatement = createCallToInitMethod(fieldType, v.variableName, methodName, useAltComponent);
            }
            else {
               // for (fieldType _elem:variableName)
               //    _elem.methodName();

               Statement initElem = createCallToInitMethod(fieldType, "_elem", methodName, useAltComponent);

               int ndims = fieldType.arrayDimensions == null ? 1 : fieldType.arrayDimensions.length() >> 1;
               initStatement = null;
               for (int d = 0; d < ndims; d++) {
                  boolean innerMost = d == 0;
                  boolean last = d == ndims - 1;
                  IdentifierExpression loopVar = IdentifierExpression.create(last ? v.variableName : "_nestedCollection" + String.valueOf(d+1));
                  JavaType elemType = (JavaType) field.type.deepCopy(ISemanticNode.CopyNormal, null);
                  elemType.arrayDimensions = null;
                  if (innerMost)
                     initStatement = ForVarStatement.create(elemType, "_elem", loopVar, initElem);
                  else
                     initStatement = ForVarStatement.create(elemType, "_nestedCollection" + d, loopVar,
                                                            initStatement);
               }
            }

            Expression ce = ConditionalExpression.create(IdentifierExpression.create(v.variableName),"!=", new NullLiteral());

            IfStatement ifStatement = new IfStatement();
            ifStatement.setProperty("expression", ParenExpression.create(ce));
            ifStatement.setProperty("trueStatement", initStatement);

            initMethod.addStatementAt(i++, ifStatement);
         }
      }
   }

   private List<FieldDefinition> getComponentFields() {
      if (body == null) return null;

      List<FieldDefinition> result = null;

      for (Statement s:body) {
         if (s instanceof FieldDefinition) {
            FieldDefinition fd = (FieldDefinition) s;

            Object fieldType = fd.type.getTypeDeclaration();
            if (ModelUtil.isComponentType(fieldType)) {
               if (result == null) result = new ArrayList<FieldDefinition>();
               result.add(fd);
            }
            if (ModelUtil.isAssignableFrom(Collection.class, fieldType)) {
               List typeArguments = fd.type.getResolvedTypeArguments();
               if (typeArguments != null && typeArguments.size() == 1 && ModelUtil.isComponentType(typeArguments.get(0))) {
                  if (result == null)
                     result = new ArrayList<FieldDefinition>();
                  result.add(fd);
               }
            }
         }
      }
      return result;
   }

   public boolean useDefaultModifier() {
      return true;
   }

   public List<Object> getAllMethods(String modifier, boolean hasModifier, boolean isDyn, boolean overridesComp) {
      List declProps = super.getAllMethods(modifier, hasModifier, isDyn, overridesComp);
      List modProps;
      Object extendsObj = getDerivedTypeDeclaration();
      if (extendsObj == null)
         return declProps;
      else {
         Object[] props = ModelUtil.getAllMethods(extendsObj, modifier, hasModifier, isDyn, overridesComp);
         if (props != null)
            modProps = Arrays.asList(props);
         else
            modProps = null;
      }
      return ModelUtil.mergeMethods(modProps, declProps);
   }

   public List<Object> getMethods(String methodName, String modifier, boolean includeExtends) {
      List declProps = super.getMethods(methodName, modifier, includeExtends);
      List modProps;
      Object extendsObj = includeExtends ? getDerivedTypeDeclaration() : null;
      if (extendsObj == null)
         return declProps;
      else {
         Object[] props = ModelUtil.getMethods(extendsObj, methodName, modifier);
         if (props != null)
            modProps = Arrays.asList(props);
         else
            modProps = null;
      }
      return ModelUtil.mergeMethods(modProps, declProps);
   }

   public List<Object> getAllProperties(String modifier, boolean includeAssigns) {
      List declProps = super.getAllProperties(modifier, includeAssigns);
      List modProps;
      Object extendsObj = getDerivedTypeDeclaration();
      if (extendsObj == null)
         return declProps == null ? null : removeStaticProperties(declProps.toArray());
      else {
         Object[] props = ModelUtil.getProperties(extendsObj, modifier, includeAssigns);
         if (props != null) {
            // Do not inherit the static properties from the extends type
            modProps = removeStaticProperties(props);
         }
         else
            modProps = null;
      }
      // Merge the properties removing any static properties from the inherited list
      return ModelUtil.mergeProperties(modProps, declProps, true, includeAssigns);
   }

   public List<Object> getAllFields(String modifier, boolean hasModifier, boolean dynamicOnly, boolean includeObjs, boolean includeAssigns, boolean includeModified) {
      List declProps = super.getAllFields(modifier, hasModifier, dynamicOnly, includeObjs, includeAssigns, includeModified);
      List modProps;
      Object extendsObj = getDerivedTypeDeclaration();
      if (extendsObj == null || (dynamicOnly && !ModelUtil.isDynamicNew(extendsObj))) {
         if (dynamicOnly && extendsObj != null) {
            // If this is looking for dynamic properties only and the base type is compiled, we need to remove
            // any properties in the base type which are in the dynamic list.  This might occur if someone overrode
            // type such as modifying a class or whatever in the sub-type.
            // Note that we use an exact == match here unless this is a "modify" type.
            // If someone defines a field with the same name in a new class it actually defines a separate field that shadows the previous one
            // if you define a field with the same name in a modify type, it replaces the old field however.
            Object[] extProps = ModelUtil.getFields(extendsObj, modifier, hasModifier, dynamicOnly, includeObjs, includeAssigns, includeModified);
            if (extProps != null) {
               declProps = ModelUtil.removePropertiesInList(declProps, Arrays.asList(extProps), false);
            }
         }
         return declProps;
      }
      else {
         Object[] props = ModelUtil.getFields(extendsObj, modifier, hasModifier, dynamicOnly, includeObjs, includeAssigns, includeModified);
         if (props != null)
            modProps = Arrays.asList(props);
         else
            modProps = null;
      }
      return ModelUtil.mergeProperties(modProps, declProps, true);
   }

   public List<Object> getAllInnerTypes(String modifier, boolean thisClassOnly) {
      List declProps = super.getAllInnerTypes(modifier, thisClassOnly);
      List modProps;
      Object extendsObj = thisClassOnly ? null : getDerivedTypeDeclaration();
      if (extendsObj == null)
         return declProps;
      else {
         Object[] props = ModelUtil.getAllInnerTypes(extendsObj, modifier, thisClassOnly);
         if (props != null)
            modProps = Arrays.asList(props);
         else
            modProps = null;
      }
      return ModelUtil.mergeInnerTypes(modProps, declProps);
   }

   public void refreshBoundTypes(int flags) {
      super.refreshBoundTypes(flags);
      if (extendsType != null)
         extendsType.refreshBoundType(flags);
   }

   public int suggestCompletions(String prefix, Object currentType, ExecutionContext ctx, String command, int cursor, Set<String> candidates, Object continuation) {
      if (extendsType != null) {
         String extName = extendsType.getFullTypeName();
         // We may have completed a fragment inside of the body so extName is not the proper completion to use.  Instead we bail on completing this node
         // and do a more primitive way to do the completion.
         if (parseNode != null && parseNode.toString().endsWith(extName)) {
            JavaModel model = getJavaModel();
            if (currentType != null)
               ModelUtil.suggestMembers(model, currentType, extName, candidates, true, false, false, false);
            ModelUtil.suggestTypes(model, extName, "", candidates, true);
            ModelUtil.suggestTypes(model, prefix, extName, candidates, true);
            if (extName.equals(""))
               return command.length();
            else
               return command.lastIndexOf(extName);
         }
      }
      return -1;
   }

   public boolean updateExtendsType(BodyTypeDeclaration newExtType, boolean modifyOnly, boolean extOnly) {
      if (extendsType != null && !modifyOnly) {
         if (ModelUtil.sameTypes(newExtType, extendsType.getTypeDeclaration())) {
            // TODO: check if the old types match?
            extendsType.setTypeDeclaration(newExtType);
            return true;
         }
      }
      return false;
   }

   public boolean isEnumeratedType() {
      return getInheritedAnnotation("sc.obj.Enumerated") != null;
   }

   public void addDependentTypes(Set<Object> types) {
      super.addDependentTypes(types);

      if (extendsType != null)
         extendsType.addDependentTypes(types);
   }


   public ClassDeclaration deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      ClassDeclaration res = (ClassDeclaration) super.deepCopy(options, oldNewMap);

      if ((options & CopyInitLevels) != 0) {
         res.declarationType = declarationType;
      }
      return res;
   }

   public boolean applyPartialValue(Object value) {
      if (value instanceof JavaType) {
         if (extendsType != null)
            if (extendsType.applyPartialValue(value))
               return true;
         if (implementsTypes != null) {
            for (JavaType implType:implementsTypes)
               if (implType.applyPartialValue(value))
                  return true;
         }
      }
      else if (value instanceof List) {
         List listVal = (List) value;
         if (listVal.size() > 0) {
            Object elem = listVal.get(0);
            if (elem instanceof ClassType) {
               if (extendsType != null)
                  if (extendsType.applyPartialValue(value))
                     return true;
            }
         }
      }
      return false;
   }

   public String getOperatorString() {
      return operator;
   }
}
