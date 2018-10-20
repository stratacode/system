/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.layer.Layer;
import sc.layer.SrcEntry;
import sc.sync.SyncPropOptions;
import sc.sync.SyncProperties;
import sc.type.CTypeUtil;
import sc.util.FileUtil;
import sc.util.StringUtil;

import java.util.*;

/**
 * This class is passed to the object and new templates as specified by the objTemplate and newTemplate fields in
 * CompilerSettings.  It exposes the properties and methods these templates can use
 * for generating the code snippet to insert into the object definition.
 */
public class ObjectDefinitionParameters extends AbstractTemplateParameters {
   public String fieldModifiers;         // Modifiers to be inserted for the field
   public String getModifiers;           // Modifiers to be inserted to the getX
   public String typeName;               // The full class name being instantiate with parameters
   public String typeBaseName;           // Excludes the package part of the typeName
   public String typeClassName;          // Excludes type parameters
   public String templatePathName;       // For templates or types with @URL, the default URL pattern which is /relDir/TypeName/InnerType.templateSuffix
   public String variableTypeName;       // Type of the variable (often typeName)
   public String lowerClassName;         // The lower class version of the identifier name
   public String upperClassName;         // The upper class version of the identifier name
   public String propertyAssignments;    // Text string of any property assignments required for this definition
   public int numChildren;               // Number of children in the childrenList
   public String childrenNames;          // Names of the children objects in a form suitable for use in an array initializer in the default scope
   public String childrenNamesNoPrefix;  // Like above but names are in the scope of the object, not the constructor (for getChildren method)
   public String childrenFieldNames;      // Like above but names are surrounded by () so that they do not have the getX conversion performed on them.
   public String childObjNames;          // String object names only of the children in parallel array to the above
   public boolean overrideField = false; // True if this is overriding a property of the same name
   public boolean overrideGet = false;   // True if this is overriding a property of the same name
   public boolean needsMemberCast = false;// True if overriding a property and the overriden type is a sub-class of the type we are overriding.  Needs the cast to return the member variable.
   public boolean typeIsComponent = false; // True if the type we are creating is a component in any way
   public boolean typeIsComponentClass = false; // True if the type we are creating itself is a component class (call newX instead of new X)
   public boolean typeIsCompiledComponent = false; // True if the type we is a component class and not a dynamic one
   public boolean isAbstract = false;    // True if the class being generated is abstract
   public String childTypeName;          // If known, specifies the type name of the objects in the childrenNames list
   public String rootName;               // Variable name that refers to the root object in the type tree
   public String parentName;             // Variable name that refers to the enclosing type's instance
   public Object currentConstructor;     // When we are processing a constructor, refers to the constructor we are processing.
   public String constructorDecls;       // Specifies variable declarations (e.g. "int foo, float bar") for each constructor parameter
   public String constructorParams;      // Specifies constructor method call parameters (e.g. "foo, bar")
   public Map<String,StringBuilder> childNamesByScope;  // A map storing the child-init string for each scope's childGroupName
   public String typePathName;            // The dotted path name of the type, including its name and any parent names
   public boolean useAltComponent;

   public String customResolver;         // Plug in your own code to resolve the existing object - maybe it's not stored in a field but in a context object of some kind
   public String customSetter;           // Plug in your own code to set the existing object in the current context.

   public boolean customNeedsField;      // Annotation processors can indicate that this type does not need a field - scopes and the like

   public List<String> preAssignments;   // Code run after the object is created before any assignments
   public List<String> postAssignments;  // Code run after the object has had it's property assignments applied

   public boolean noCreationTemplate;    // True for object definitions which are applied without a creation template

   public boolean typeIsDynamic;          // Set to true when the type is dynamic.

   protected TypeDeclaration objType;
   
   protected Object compiledClass;

   protected TypeDeclaration accessClass;

   protected String constrModifiers;

   public ObjectDefinitionParameters() {
   }

   public void init() {
      isAbstract = objType.hasModifier("abstract");
      fieldModifiers = TransformUtil.removeClassOnlyModifiers(constrModifiers);
      getModifiers = TransformUtil.removeModifiers(fieldModifiers, TransformUtil.fieldOnlyModifiers);
      typeBaseName = CTypeUtil.getClassName(typeName);
      typeClassName = ModelUtil.getTypeName(ModelUtil.getAccessClass(compiledClass));
      templatePathName = objType.getTemplatePathName();

      Object enclInstType = ModelUtil.getEnclosingInstType(compiledClass);

      /* Weird Java thing.  If we're resolving a reference to an inner instance type, like "new X", it won't let us
       * use the full type name of X.  It says we're not in scope when we are.  Either removing the entire type prefix
       * in this case or inserting a "this" fixes the problem.
       */
      if (enclInstType != null) {
         BodyTypeDeclaration accessParent = accessClass;
         while (accessParent != null) {
            if (ModelUtil.isAssignableFrom(accessParent, enclInstType)) {
               typeClassName = typeClassName + ".this";
               break;
            }
            else {
               accessParent = accessParent.getEnclosingInstType();
            }
         }
      }
      this.typeIsDynamic = ModelUtil.isDynamicType(compiledClass);
      this.typeIsCompiledComponent = typeIsComponentClass && !this.typeIsDynamic;
      String propName = objType.typeName; // Allow this to be either upper or lower, but we still generate
      upperClassName = CTypeUtil.capitalizePropertyName(propName);
      lowerClassName = CTypeUtil.decapitalizePropertyName(propName);
      this.typePathName = objType.getTypePathName();
      StringBuilder childNames = new StringBuilder();
      Map<String,StringBuilder> tmpChildNamesByScope = new HashMap<String,StringBuilder>();
      LinkedHashSet<String> objNames = new LinkedHashSet<String>();
      objType.addChildNames(childNames, tmpChildNamesByScope, null, false, false, false, objNames);
      childrenNamesNoPrefix = childNames.toString();
      String tmp = childrenNamesNoPrefix.trim();
      if (objNames.size() == 0)
         childrenFieldNames = "";
      else {
         StringBuilder fsb = new StringBuilder();
         int i = 0;
         for (String objName:objNames) {
            if (i != 0)
               fsb.append(", ");
            fsb.append("(");
            fsb.append(CTypeUtil.decapitalizePropertyName(objName));
            fsb.append(")");
            i++;
         }
         childrenFieldNames = fsb.toString();
      }

      if (customResolver != null)
         this.customResolver = TransformUtil.evalTemplate(this, customResolver, true);
      if (customSetter != null)
         this.customSetter = TransformUtil.evalTemplate(this, customSetter, true);

   }

   public ObjectDefinitionParameters(Object compiledClass, String objectClassName, String variableTypeName,
                              TypeDeclaration objType, String modifiers,
                              StringBuilder childrenNames, int numChildren, Map<String,StringBuilder> childNamesByScope,
                              StringBuilder childObjNames,
                              boolean overrideField, boolean overrideGet, boolean needsMemberCast, boolean isComponent,
                              boolean typeIsComponentClass, String childTypeName, String parentName, String rootName, Object currentConstructor,
                              String constDecls, String constParams, TypeDeclaration accessClass, boolean useAltComponent,
                              String customResolver, String customSetter, boolean needsField, List<String> preAssignments, List<String> postAssignments) {
      this.objType = objType;
      this.compiledClass = compiledClass;
      this.accessClass = accessClass;
      this.constrModifiers = modifiers;
      this.typeName = objectClassName;
      this.useAltComponent = useAltComponent;

      this.variableTypeName = variableTypeName;
      this.childrenNames = childrenNames.toString();
      this.childObjNames = childObjNames.toString();
      this.numChildren = numChildren;
      this.overrideField = overrideField;
      this.overrideGet = overrideGet;
      this.needsMemberCast = needsMemberCast;
      this.typeIsComponent = isComponent;
      this.typeIsComponentClass = typeIsComponentClass;
      // We use the existing "new X" for the dynamic case since that gets transformed..  Need to use the compiledClass here for the dyn type check as it is the thing which is getting new'd
      this.childTypeName = childTypeName;
      this.parentName = parentName;
      this.rootName = rootName;
      this.currentConstructor = currentConstructor;
      this.constructorDecls = constDecls;
      this.constructorParams = constParams;
      this.childNamesByScope = childNamesByScope;
      this.customNeedsField = needsField;
      this.preAssignments = preAssignments;
      this.postAssignments = postAssignments;
      this.customResolver = customResolver;
      this.customSetter = customSetter;

      init();
   }

   public String getNextConstructorDecls() {
      return emptyString(constructorDecls) ? "" : "," + constructorDecls;
   }

   public String getNextConstructorParams() {
      return emptyString(constructorParams) ? "" : "," + constructorParams;
   }

   public String getPrevConstructorDecls() {
      return emptyString(constructorDecls) ? "" : constructorDecls + ",";
   }

   public String getPrevConstructorParams() {
      return emptyString(constructorParams) ? "" : constructorParams + ",";
   }

   public String getEnclosingParamType(String paramTypeName) {
      TypeDeclaration encType = objType.getEnclosingType();
      if (encType == null)
         return paramTypeName;
      return encType.mapTypeParameterNameToTypeName(paramTypeName);
   }

   public boolean getLiveDynamicTypes() {
      return ModelUtil.getCompileLiveDynamicTypes(objType);
   }

   public String getDynamicTypeDefinition(String varName, int indent) {
      String instOrObj = objType.getDeclarationType() == DeclarationType.OBJECT ? "Object" : "Instance";
      if (getLiveDynamicTypes()) {
         if (objType.getEnclosingType() == null || objType.isStaticInnerClass() || parentName == null)
            return StringUtil.indent(indent) + "sc.dyn.DynUtil.addDyn" + instOrObj + "(\"" + objType.getFullTypeName() + "\", " + varName + ");\n";
         else
            return StringUtil.indent(indent) + "sc.dyn.DynUtil.addDynInner" + instOrObj + "(\"" + objType.getFullTypeName() + "\", " + varName + "," + parentName + ");\n";
      }
      else
         return "";
   }

   /** In some cases, the type of the property is a sub-type of the definition, like when you refine it. */
   public String getReturnCast() {
      if (needsMemberCast)
         return "(" + variableTypeName + ") ";
      return "";
   }

   public String getAltPrefix() {
      return useAltComponent ? "_" : "";
   }

   public boolean getDefinesInnerObjects() {
      String[] res = objType.getObjChildrenNames(null, false, true, false);
      return res != null && res.length > 0;
   }

   public Object getAnnotation(String annotName) {
      return ModelUtil.getAnnotation(objType, annotName);
   }

   public List<Object> getPropertiesWithAnnotation(String annotName) {
      return ModelUtil.getPropertiesWithAnnotation(objType, annotName);
   }

   public Object getAnnotationValue(String annotName, String valueName) {
      return ModelUtil.getAnnotationValue(objType, annotName, valueName);
   }

   public boolean getNeedsCustomResolver() {
      return customResolver != null || overrideGet;
   }

   /** Returns the code fragment which retrieves the value.  Must call needsCustomResolver before using this value. */
   public String getCustomResolver() {
      return customResolver != null ? customResolver : overrideGet ? "   " + variableTypeName + " _" + lowerClassName + " = (" + variableTypeName + ") super.get" + upperClassName + "();\n" : "";
   }

   /** Returns the complete setX statement (if anything) */
   public String getCustomSetter() {
      return customSetter != null ? customSetter : overrideGet ?  "      set" + upperClassName + "(_" + lowerClassName + ");\n" : "";
   }

   public boolean getNeedsSet() {
      return overrideGet;
   }

   public boolean getNeedsField() {
      return !overrideField && !overrideGet && customNeedsField;
   }

   public List<SyncProperties> getSyncProperties() {
      return objType.resolve(true).getSyncProperties();
   }

   public String getScopeName() {
      return objType.getScopeName();
   }

   public String getDerivedScopeName() {
      return objType.getDerivedScopeName();
   }

   public boolean getNeedsSync() {
      return objType.needsSync();
   }

   public boolean getSyncOnDemand() {
      return objType.getSyncOnDemand();
   }

   public boolean getSyncInitDefault() {
      return objType.getSyncInitDefault();
   }

   public int getSyncFlags() {
      return objType.getSyncFlags();
   }

   /**
    * When referring to the instance variable defined there are two cases.  If there's no creation template the code
    * gets placed into the constructor or as init code and so uses 'this'.  If it's in a getX or newX method we generate
    * the local variable name is used.
    *
    * For some reason components use just the lowerClassName, without the "_".
    */
   public String getInstanceVariableName() {
      return noCreationTemplate ? "this" : (typeIsComponent ? lowerClassName : "_" + lowerClassName);
   }

   public String getPreAssignment() {
      String preAssignment = "";
      if (preAssignments != null) {
         for (String preTemplate:preAssignments) {
            try {
               preAssignment += TransformUtil.evalTemplate(this, preTemplate, true);
            }
            catch (IllegalArgumentException exc) {
               objType.displayError("Invalid preAssignment template: " + preTemplate + ": " + exc.toString() + " for type: ");
            }
         }
      }
      return preAssignment;
   }

   public String getPostAssignment() {
      String postAssignment = "";
      if (postAssignments != null) {
         for (String postTemplate:postAssignments) {
            try {
               postAssignment += TransformUtil.evalTemplate(this, postTemplate, true);
            }
            catch (IllegalArgumentException exc) {
               objType.displayError("Invalid postAssignment template: " + postTemplate + ": " + exc.toString() + " for type: ");
            }
         }
      }
      return postAssignment;
   }

   public Layer getLayer() {
      return objType.getLayer();
   }

   public static String formatSyncPropsArray(Object[] strarr) {
      StringBuilder sb = new StringBuilder();
      sb.append("new Object[] {");
      int i = 0;
      for (Object obj:strarr) {
         if (i != 0)
            sb.append(",");
         if (obj instanceof String) {
            sb.append(formatString((String) obj));
         }
         else if (obj instanceof SyncPropOptions) {
            SyncPropOptions opt = (SyncPropOptions) obj;
            sb.append("new sc.sync.SyncPropOptions(");
            sb.append(formatString(opt.propName));
            sb.append(", ");
            sb.append(opt.flags);
            sb.append(")");
         }
         i++;
      }
      sb.append("}");
      return sb.toString();
   }

   /** Do we need the type settings at all?  Even if we inherit it from a base type, we still want to include it in the code-gen. */
   public boolean getNeedsTypeSettings() {
      return ModelUtil.isObjectType(objType) || objType.propertiesAlreadyBindable != null;
      //return objType.getAnnotation("sc.obj.TypeSettings") == null;
   }

   public String getTypeSettingsAnnotation() {
      boolean isObject = ModelUtil.isObjectType(objType);
      ArrayList<String> bindProps = objType.propertiesAlreadyBindable;
      StringBuilder sb = null;
      if (isObject || bindProps != null) {
         sb = new StringBuilder();
         sb.append("@sc.obj.TypeSettings(");
         if (isObject) {
            sb.append("objectType=true");
         }
         if (bindProps != null) {
            if (isObject)
               sb.append(",");
            sb.append("bindableProps={");
            for (String bp:bindProps) {
               sb.append('"');
               sb.append(bp);
               sb.append('"');
            }
            sb.append("}");
         }
         sb.append(")\n");
      }
      return sb == null ? "" : sb.toString();
   }

   public String getTemplateSuffix() {
      JavaModel model = objType.getJavaModel();
      return model.resultSuffix;
   }

   public ArrayList<String> getPropertiesAlreadyBindable() {
      ArrayList<String> res = objType.propertiesAlreadyBindable;
      return res == null ? StringUtil.EMPTY_STRING_LIST : res;
   }
}
