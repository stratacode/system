/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.db.*;
import sc.lang.sc.IScopeProcessor;
import sc.lang.sql.DBProvider;
import sc.lang.template.Template;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.sync.SyncPropOptions;
import sc.sync.SyncProperties;
import sc.type.CTypeUtil;
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
   public boolean needsMemberCast = false;// True if overriding a property and the overridden type is a sub-class of the type we are overriding.  Needs the cast to return the member variable.
   public boolean typeIsComponent = false; // True if the type we are creating is a component in any way
   public boolean typeIsComponentClass = false; // True if the type we are creating itself is a component class (call newX instead of new X)
   public boolean typeIsCompiledComponent = false; // True if the type we is a component class and not a dynamic one
   public boolean isAbstract = false;    // True if the class being generated is abstract
   public boolean isAbstractElement = false;// True if this is a tag object with the abstract='true' attribute
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
   public Template customResolverTemplate;
   public String customSetter;           // Plug in your own code to set the existing object in the current context.
   public Template customSetterTemplate;           // Plug in your own code to set the existing object in the current context.

   public boolean customNeedsField;      // Annotation processors can indicate that this type does not need a field - scopes and the like

   public List<String> preAssignments;   // Code run after the object is created before any assignments
   public List<String> postAssignments;  // Code run after the object has had it's property assignments applied
   public List<String> accessHooks;  // Code run before returning an existing instance

   public boolean noCreationTemplate;    // True for object definitions which are applied without a creation template

   public boolean typeIsDynamic;          // Set to true when the type is dynamic.

   public String beforeNewObject = "";        // Code to define variables for the constructor

   protected TypeDeclaration objType;
   
   protected Object compiledClass;

   protected TypeDeclaration accessClass;

   protected String constrModifiers;

   DBEnumDescriptor dbEnumDescriptor;
   DBTypeDescriptor dbTypeDescriptor;

   DBProvider dbProvider;

   public ObjectDefinitionParameters() {
   }

   public void init() {
      isAbstract = objType.hasModifier("abstract");
      isAbstractElement = objType.element != null && objType.element.isAbstract();
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
      ArrayList<Object> objTypes = new ArrayList<Object>();
      objType.addChildNames(childNames, tmpChildNamesByScope, null, false, false,
                 false, objNames, objTypes);
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
            if (i >= objTypes.size()) {
               System.err.println("*** Obj types does not match objNames");
               /* debug code to trace problems where we fail to add an objType when we add an objName
               objTypes.clear();
               objNames.clear();
               tmpChildNamesByScope.clear();
               objType.addChildNames(childNames, tmpChildNamesByScope, null, false, false,
                       false, objNames, objTypes);
               */
            }
            Object innerObjType = objTypes.get(i);
            if (innerObjType == null)
               System.err.println("*** No inner object!");
            IScopeProcessor sp;
            if (innerObjType instanceof TypeDeclaration && ((sp = ((TypeDeclaration) innerObjType).getScopeProcessor()) != null && !sp.getDefinesTypeField()))
               fsb.append(CTypeUtil.decapitalizePropertyName(objName));
            else {
               fsb.append("(");
               fsb.append(CTypeUtil.decapitalizePropertyName(objName));
               fsb.append(")");
            }
            i++;
         }
         childrenFieldNames = fsb.toString();
      }

      if (customResolverTemplate != null)
         customResolver = TransformUtil.evalTemplate(this, customResolverTemplate);
      else if (customResolver != null)
         customResolver = TransformUtil.evalTemplate(this, customResolver, true, false, null);

      if (customSetterTemplate != null)
         customSetter = TransformUtil.evalTemplate(this, customSetterTemplate);
      else if (customResolver != null)
         customSetter = TransformUtil.evalTemplate(this, customSetter, true, false, null);

      BodyTypeDeclaration dbType;
      if (objType.isStarted())
         dbType = objType.resolve(true);
      else
         dbType = objType;
      LayeredSystem sys = dbType.getLayeredSystem();
      Layer typeLayer = dbType.getLayer();
      BaseTypeDescriptor baseTypeDesc = DBProvider.getDBTypeDescriptor(sys, typeLayer, dbType, false);
      if (baseTypeDesc instanceof DBTypeDescriptor)
         dbTypeDescriptor = (DBTypeDescriptor) baseTypeDesc;
      else
         dbEnumDescriptor = (DBEnumDescriptor) baseTypeDesc;

      if (dbTypeDescriptor != null || dbEnumDescriptor != null)
         dbProvider = DBProvider.getDBProviderForType(sys, typeLayer, dbType);
   }

   public ObjectDefinitionParameters(Object compiledClass, String objectClassName, String variableTypeName,
                              TypeDeclaration objType, String modifiers,
                              StringBuilder childrenNames, int numChildren, Map<String,StringBuilder> childNamesByScope,
                              StringBuilder childObjNames,
                              boolean overrideField, boolean overrideGet, boolean needsMemberCast, boolean isComponent,
                              boolean typeIsComponentClass, String childTypeName, String parentName, String rootName, Object currentConstructor,
                              String constDecls, String constParams, TypeDeclaration accessClass, boolean useAltComponent,
                              String customResolver, Template customResolverTemplate, String customSetter, Template customSetterTemplate,
                              boolean needsField, List<String> preAssignments, List<String> postAssignments, List<String> accessHooks) {
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
      this.accessHooks = accessHooks;
      this.customResolver = customResolver;
      this.customResolverTemplate = customResolverTemplate;
      this.customSetter = customSetter;
      this.customSetterTemplate = customSetterTemplate;

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

   public boolean getNeedsAddSyncInst() {
      return objType.needsSync() && dbTypeDescriptor == null && !isAbstract;
   }

   public boolean getNeedsSyncAccessHook() {
      if (!objType.needsSync())
         return false;

      IScopeProcessor scopeProc = objType.getScopeProcessor();
      if (scopeProc != null)
         return scopeProc.getNeedsSyncAccessHook();
      return false;
   }

   public boolean getSyncOnDemand() {
      return objType.getSyncOnDemand();
   }

   public boolean getSyncInitDefault() {
      return objType.getSyncInitDefault();
   }


   public boolean getSyncResetState() {
      // Note: not returning the Sync(resetState=true/false) because
      // we turn this resetState flag for the addSyncInst call at runtime for persistent instances. This goes into
      // the compile time addSyncInst call that's not made for persistent instances.
      return true;
   }

   public int getSyncFlags() {
      return objType.getSyncFlags();
   }

   /**
    * When referring to the instance variable, when the instance is created.  Either in the 'init' code section if there
    * is no constructor, the constructor, or a chunk of code in the getX method.
    *
    * If there's no creation template the code
    * gets placed into the constructor or as init code and so uses 'this'.  If it's in a getX or newX method we generate
    * the local variable name is used.
    *
    * For accessHooks, the lowerClassName should be used or maybe the getX method.  This logic only applies for the create.
    */
   public String getInstanceVariableName() {
      return noCreationTemplate ? "this" : "_" + lowerClassName;
   }

   public String getPreAssignment() {
      String preAssignment = "";
      if (preAssignments != null) {
         for (String preTemplate:preAssignments) {
            try {
               preAssignment += TransformUtil.evalTemplate(this, preTemplate, true, false, null);
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
               postAssignment += TransformUtil.evalTemplate(this, postTemplate, true, false, null);
            }
            catch (IllegalArgumentException exc) {
               objType.displayError("Invalid postAssignment template: " + postTemplate + ": " + exc.toString() + " for type: ");
            }
         }
      }
      return postAssignment;
   }

   public String getAccessHook() {
      String accessHookStr = "";
      if (accessHooks != null) {
         for (String accessHookTemplate:accessHooks) {
            try {
               accessHookStr += TransformUtil.evalTemplate(this, accessHookTemplate, true, false, null);
            }
            catch (IllegalArgumentException exc) {
               objType.displayError("Invalid accessHook template: " + accessHookTemplate + ": " + exc.toString() + " for type: ");
            }
         }
      }
      return accessHookStr;
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

   public String formatIdParams(boolean argMode) {
      if (dbTypeDescriptor == null)
         return "";
      StringBuilder sb = new StringBuilder();

      List<IdPropertyDescriptor> idCols = dbTypeDescriptor.primaryTable.idColumns;
      for (int i = 0; i < idCols.size(); i++) {
         if (i != 0)
            sb.append(", ");
         IdPropertyDescriptor idCol = idCols.get(i);
         if (!argMode) {
            // The property may be inserted and so has no mapper yet - use the SQL type to get the Java type
            String javaType = DBUtil.getJavaTypeFromSQLType(idCol.columnType);
            sb.append(javaType);
            sb.append(" ");
         }
         sb.append(idCol.propertyName);
      }
      return sb.toString();
   }

   public String formatQueries() {
      if (dbTypeDescriptor == null || dbTypeDescriptor.queries == null || (dbProvider != null && !dbProvider.needsQueryMethods))
         return "";

      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < dbTypeDescriptor.queries.size(); i++) {
         BaseQueryDescriptor queryDesc = dbTypeDescriptor.queries.get(i);
         if (queryDesc instanceof FindByDescriptor) {
            FindByDescriptor fbDesc = (FindByDescriptor) queryDesc;
            sb.append("public static ");
            if (fbDesc.multiRow && !fbDesc.findOne) {
               sb.append("java.util.List<");
               sb.append(typeBaseName);
               sb.append(">");
            }
            else {
               sb.append(typeBaseName);
            }
            sb.append(" findBy");
            sb.append(CTypeUtil.capitalizePropertyName(fbDesc.queryName));
            sb.append("(");
            int numProps = fbDesc.propNames.size();
            int numOpts = fbDesc.optionNames == null ? 0 : fbDesc.optionNames.size();
            int argCt = 0;
            ArrayList<String> propVarNames = new ArrayList<String>(numProps);
            for (int j = 0; j < numProps; j++) {
               if (j != 0)
                  sb.append(", ");
               String propPathName = fbDesc.propNames.get(j);
               String propVarName = propPathName.replace(".", "_");
               propVarNames.add(propVarName);

               // The IDE during index init had propTypes of size 0 here - just want to avoid the exception
               Object propType = j < fbDesc.propTypes.size() ? fbDesc.propTypes.get(j) : Object.class;
               sb.append(getTypeName(propType));
               sb.append(" ");
               sb.append(propVarName);
               argCt++;
            }
            ArrayList<String> optVarNames = new ArrayList<String>(numOpts);
            if (numOpts > 0) {
               for (int j = 0; j < numOpts; j++) {
                  if (argCt != 0)
                     sb.append(", ");
                  String optName = fbDesc.optionNames.get(j);
                  String optVarName = optName.replace(".","_");
                  optVarNames.add(optVarName);
                  Object propType = j < fbDesc.optionTypes.size() ? fbDesc.optionTypes.get(j) : Object.class;
                  sb.append("boolean _opt");
                  sb.append(j);
                  sb.append("_");
                  sb.append(CTypeUtil.getClassName(optName));
                  sb.append(", ");
                  sb.append(getTypeName(propType));
                  sb.append(" ");
                  sb.append(optVarName);
                  argCt++;
               }
            }
            if (fbDesc.multiRow) {
               if (fbDesc.orderByOption) {
                  if (argCt > 0)
                     sb.append(", ");
                  sb.append("java.util.List<String> orderBy");
                  argCt++;
               }
               if (fbDesc.paged) {
                  if (argCt > 0)
                     sb.append(", ");
                  sb.append("int startIndex, int maxResults");
               }
            }
            sb.append(") {\n");

            if (!objType.isStarted()) {
               sb.append("  return null;\n");
               sb.append("}\n\n");
               continue;
            }

            sb.append("      ");
            sb.append(typeBaseName);
            sb.append(" proto = new ");
            sb.append(typeBaseName);
            sb.append("();\n");
            sb.append("      sc.db.DBObject dbObj = proto.getDBObject();\n");
            sb.append("      dbObj.setPrototype(true);\n");
            if (fbDesc.protoProps != null) {
               sb.append("      dbObj.initProtoProperties(");
               appendStringArgs(sb, fbDesc.protoProps);
               sb.append(");\n");
            }

            for (int j = 0; j < numProps; j++) {
               String propName = fbDesc.propNames.get(j);
               sb.append("      proto." + propName + " = " + propVarNames.get(j) + ";\n");
               /*
               sb.append("      proto.set");
               sb.append(CTypeUtil.capitalizePropertyName(propName));
               sb.append("(");
               sb.append(propName);
               sb.append(");\n");
               */
            }
            sb.append("      java.util.List<String> propList = ");
            if (numOpts > 0)
               sb.append("new java.util.ArrayList<String>(");
            if (fbDesc.propNames.size() > 0)
               appendStringList(sb, fbDesc.propNames);
            if (numOpts > 0)
               sb.append(")");
            sb.append(";\n");
            for (int j = 0; j < numOpts; j++) {
               String propName = fbDesc.optionNames.get(j);
               String propVarName = optVarNames.get(j);
               String capPropName = CTypeUtil.capitalizePropertyName(propVarName);
               sb.append("      if (_opt");
               sb.append(j);
               sb.append("_");
               sb.append(CTypeUtil.getClassName(propName));
               sb.append(") {\n");
               //sb.append("         proto.set");
               //sb.append(capPropName);
               //sb.append("(");
               //sb.append(propName);
               //sb.append(");\n");
               sb.append("         proto." + propName + " = " + propVarName + ";\n");

               sb.append("         propList.add(");
               appendString(sb, propName, false);
               sb.append(");\n      }\n");
            }
            if (fbDesc.orderByProps != null) {
               sb.append("      java.util.List<String> orderBy = new java.util.ArrayList<String>(");
               if (fbDesc.orderByProps.size() > 0)
                  appendStringList(sb, fbDesc.orderByProps);
               sb.append(");\n");
            }
            sb.append("      return (");
            if (fbDesc.multiRow && !fbDesc.findOne) {
               sb.append("java.util.List<");
               sb.append(typeBaseName);
               sb.append(">");
            }
            else {
               sb.append(typeBaseName);
            }
            sb.append(") dbTypeDesc.");
            if (fbDesc.multiRow && !fbDesc.findOne)
               sb.append("matchQuery(");
            else
               sb.append("matchOne(");
            sb.append("proto.getDBObject(), propList, ");
            appendString(sb, fbDesc.selectGroup, false);
            if (fbDesc.multiRow && !fbDesc.findOne) {
               if (fbDesc.orderByOption || fbDesc.orderByProps != null)
                  sb.append(", orderBy");
               else
                  sb.append(", null");
               if (fbDesc.paged)
                  sb.append(", startIndex, maxResults");
               else
                  sb.append(", 0, 0");
            }
            sb.append(");\n");
            sb.append("   }\n\n");
         }
         else if (queryDesc instanceof NamedQueryDescriptor) {
            NamedQueryDescriptor namedQuery = (NamedQueryDescriptor) queryDesc;
            sb.append("   public static ");
            StringBuilder retTypeName = new StringBuilder();
            if (namedQuery.multiRow) {
               retTypeName.append("java.util.List<");
               retTypeName.append(namedQuery.returnTypeName);
               retTypeName.append(">");
            }
            else {
               retTypeName.append(namedQuery.returnTypeName);
            }
            sb.append(retTypeName);
            sb.append(" ");
            sb.append(namedQuery.queryName);
            sb.append("(");
            int numParams = namedQuery.paramNames.size();
            for (int j = 0; j < numParams; j++) {
               if (j != 0)
                  sb.append(", ");
               String paramName = namedQuery.paramNames.get(j);
               String paramTypeName = namedQuery.paramTypeNames.get(j);
               sb.append(paramTypeName);
               sb.append(" ");
               sb.append(paramName);
            }
            sb.append(") {\n");
            sb.append("      return ");
            sb.append("(");
            sb.append(retTypeName);
            sb.append(") ");
            sb.append("dbTypeDesc.namedQuery(");
            appendString(sb, namedQuery.queryName, false);
            for (int j = 0; j < numParams; j++) {
               appendIdent(sb, namedQuery.paramNames.get(j), true);
            }
            sb.append(");\n");
            sb.append("   }\n");
         }
      }
      return sb.toString();
   }

   private String getTypeName(Object type) {
      String typeName = ModelUtil.getTypeName(type);
      if (typeName.startsWith("java.lang"))
         typeName = CTypeUtil.getClassName(typeName);
      return typeName;
   }

   public String formatDBEnumDescriptorDefinition() {
      if (dbEnumDescriptor == null || (dbProvider != null && !dbProvider.needsMetadata))
         return "";
      dbEnumDescriptor.init();
      dbEnumDescriptor.start();
      StringBuilder sb = new StringBuilder();
      sb.append("\n\n   static sc.db.DBEnumDescriptor dbEnumDesc = sc.db.DBEnumDescriptor.create(");
      appendTypeDecl(sb);
      appendString(sb, dbEnumDescriptor.sqlTypeName, true);
      appendString(sb, dbEnumDescriptor.dataSourceName, true);
      sb.append(", ");
      sb.append("java.util.Arrays.asList(");
      List<String> enumConsts = dbEnumDescriptor.enumConstants;
      for (int i = 0; i < enumConsts.size(); i++) {
         appendString(sb, enumConsts.get(i), i != 0);
      }
      sb.append("));\n\n");
      return sb.toString();
   }

   private void appendTypeDecl(StringBuilder sb) {
      if (typeIsDynamic)
         sb.append("sc.dyn.DynUtil.findType(\"" + typeName + "\")");
      else {
         sb.append(objType.getTypeName());
         sb.append(".class");
      }
   }

   public String formatDBTypeDescriptorDefinition() {
      if (dbTypeDescriptor == null || (dbProvider != null && !dbProvider.needsMetadata))
         return "";

      if (!dbTypeDescriptor.tablesInitialized) {
         Layer layer = getLayer();
         DBProvider.completeDBTypeDescriptor(dbTypeDescriptor, layer.getLayeredSystem(), layer, objType);
      }

      dbTypeDescriptor.init();
      dbTypeDescriptor.start();

      StringBuilder sb = new StringBuilder();
      sb.append("dbTypeDesc = sc.db.DBTypeDescriptor.create(");

      appendTypeDecl(sb);

      sb.append(", ");

      DBTypeDescriptor baseType = dbTypeDescriptor.baseType;
      if (baseType != null) {
         sb.append("sc.db.DBTypeDescriptor.getByName(\"");
         sb.append(ModelUtil.getTypeName(dbTypeDescriptor.baseType.typeDecl));
         sb.append("\", false)");
      }
      else
         sb.append("null");

      sb.append(",");
      sb.append(dbTypeDescriptor.typeId);
      sb.append(", \"");
      sb.append(dbTypeDescriptor.dataSourceName);

      sb.append("\", ");

      // If there's a base type, we inherit the base type's primary table and don't specific it here
      appendTable(sb, baseType == null ? dbTypeDescriptor.primaryTable : null, false);
      sb.append(", ");
      appendTableList(sb, dbTypeDescriptor.auxTables, false);
      sb.append(", ");
      appendTableList(sb, dbTypeDescriptor.multiTables, true);
      sb.append(", ");
      appendQueries(sb, dbTypeDescriptor.queries);
      sb.append(", ");
      DBPropertyDescriptor versionProp = dbTypeDescriptor.versionProperty;
      if (versionProp == null)
         sb.append("null");
      else {
         sb.append('"');
         sb.append(versionProp.columnName);
         sb.append('"');
      }
      appendString(sb, dbTypeDescriptor.schemaSQL, true);
      sb.append(", ");
      sb.append(dbTypeDescriptor.needsAutoId);

      sb.append(");");

      return sb.toString();
   }

   private void appendQueries(StringBuilder sb, List<BaseQueryDescriptor> queries) {
      if (queries == null || queries.size() == 0) {
         sb.append("null");
         return;
      }
      sb.append("java.util.Arrays.asList(");

      for (int i = 0; i < queries.size(); i++) {
         if (i != 0)
            sb.append(", ");
         BaseQueryDescriptor query = queries.get(i);
         if (query instanceof FindByDescriptor) {
            sb.append("new sc.db.FindByDescriptor(");
            FindByDescriptor fbDesc = (FindByDescriptor) query;
            appendString(sb, fbDesc.queryName, false);
            sb.append(", ");
            appendStringList(sb, fbDesc.propNames);
            sb.append(", ");
            appendStringList(sb, fbDesc.optionNames);
            sb.append(", ");
            appendStringList(sb, fbDesc.orderByProps);
            sb.append(", ");
            sb.append(fbDesc.orderByOption);
            sb.append(", ");
            sb.append(fbDesc.multiRow);
            appendString(sb, fbDesc.selectGroup, true);
            sb.append(", ");
            sb.append(fbDesc.paged);
            sb.append(", ");
            sb.append(fbDesc.findOne);
            sb.append(")");
         }
         else if (query instanceof NamedQueryDescriptor) {
            NamedQueryDescriptor namedQuery = (NamedQueryDescriptor) query;
            sb.append("new sc.db.NamedQueryDescriptor(");
            appendString(sb, namedQuery.queryName, false);
            appendString(sb, namedQuery.dbQueryName, true);
            sb.append(", ");
            appendStringList(sb, namedQuery.paramNames);
            sb.append(", ");
            appendStringList(sb, namedQuery.paramDBTypeNames);
            sb.append(", ");
            sb.append(namedQuery.multiRow);
            appendString(sb, namedQuery.returnTypeName, true);
            appendString(sb, namedQuery.returnDBTypeName, true);
            sb.append(")");
         }
      }
      sb.append(")");
   }

   private void appendStringList(StringBuilder sb, List<String> properties) {
      if (properties == null) {
         sb.append("null");
         return;
      }
      sb.append("java.util.Arrays.asList(");
      appendStringArgs(sb, properties);
      sb.append(")");
   }

   private void appendStringArgs(StringBuilder sb, List<String> properties) {
      for (int j = 0; j < properties.size(); j++) {
         appendString(sb, properties.get(j), j != 0);
      }
   }

   private void appendTableList(StringBuilder sb, List<? extends TableDescriptor> tableList, boolean multi) {
      if (tableList == null || tableList.size() == 0) {
         sb.append("null");
         return;
      }
      String className = "TableDescriptor";
      // TODO: simplify this to just use Arrays.asList
      sb.append("java.util.Arrays.asList(");

      boolean first = true;
      for (TableDescriptor tableDesc:tableList) {
         if (first)
            first = false;
         else
            sb.append(", ");
         appendTable(sb, tableDesc, multi);
      }
      sb.append(")");
   }

   private void appendTable(StringBuilder sb, TableDescriptor tableDesc, boolean multi) {
      if (tableDesc == null) {
         sb.append("null");
         return;
      }
      sb.append("new sc.db.TableDescriptor(\"");

      sb.append(tableDesc.tableName);
      sb.append("\", ");
      appendPropertyList(sb, tableDesc.idColumns);
      sb.append(", ");
      appendPropertyList(sb, tableDesc.columns);
      sb.append(", ");
      if (tableDesc.reverseProperty == null)
         sb.append("null");
      else
         appendProperty(sb, tableDesc.reverseProperty, false, "sc.db.DBPropertyDescriptor");
      sb.append(", ");
      sb.append(tableDesc.hasDynColumns);

      sb.append(")");
   }

   private void appendPropertyList(StringBuilder sb, List<? extends DBPropertyDescriptor> propList) {
      if (propList == null) {
         sb.append("null");
         return;
      }
      sb.append("java.util.Arrays.asList(");
      boolean first = true;
      for (DBPropertyDescriptor propDesc:propList) {
         if (first)
            first = false;
         else
            sb.append(", ");
         boolean isIdProperty = propDesc instanceof IdPropertyDescriptor;
         String descClassName = isIdProperty ? "sc.db.IdPropertyDescriptor" : "sc.db.DBPropertyDescriptor";
         appendProperty(sb, propDesc, isIdProperty, descClassName);
      }
      sb.append(")");
   }

   private void appendProperty(StringBuilder sb, DBPropertyDescriptor propDesc, boolean isIdProperty, String descName) {
      sb.append("new ");
      if (propDesc instanceof MultiColPropertyDescriptor)
         sb.append("sc.db.MultiColPropertyDescriptor");
      else
         sb.append(descName);
      sb.append("(");
      appendString(sb, propDesc.propertyName, false);
      appendString(sb, propDesc.columnName, true);
      appendString(sb, propDesc.columnType, true);
      if (!isIdProperty) {
         appendString(sb, propDesc.tableName, true);
         sb.append(", ");
         sb.append(propDesc.required);
         sb.append(", ");
         sb.append(propDesc.unique);
         sb.append(", ");
         sb.append(propDesc.onDemand);
         sb.append(", ");
         sb.append(propDesc.indexed);
         if (!(propDesc instanceof MultiColPropertyDescriptor)) {
            sb.append(", ");
            sb.append(propDesc.dynColumn);
         }
         appendString(sb, propDesc.dataSourceName, true);
         appendString(sb, propDesc.selectGroup, true);
         appendString(sb, propDesc.refTypeName, true);
         sb.append(", ");
         sb.append(propDesc.multiRow);
         appendString(sb, propDesc.reverseProperty, true);
         appendString(sb, propDesc.dbDefault, true);
         appendString(sb, propDesc.ownerTypeName, true);
      }
      else {
         sb.append(", ");
         sb.append(((IdPropertyDescriptor) propDesc).definedByDB);
         if (propDesc.dbTypeDesc != dbTypeDescriptor && propDesc.dbTypeDesc != null)
            appendString(sb, propDesc.dbTypeDesc.getTypeName(), true);
         else
            appendString(sb, null, true);
      }
      sb.append(")");
   }

   private void appendIdent(StringBuilder sb, String str, boolean commaPre) {
      if (commaPre)
         sb.append(", ");
      if (str == null)
         sb.append("null");
      else {
         sb.append(str);
      }
   }

   private void appendString(StringBuilder sb, String str, boolean commaPre) {
      if (commaPre)
         sb.append(", ");
      if (str == null)
         sb.append("null");
      else {
         sb.append('"');
         sb.append(CTypeUtil.escapeJavaString(str, '"', false));
         sb.append('"');
      }
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

   public String getSyncTypeNames() {
      LayeredSystem sys = objType.getLayeredSystem();
      Set<String> syncTypeNames = sys.options.syncTypeFilter ? ModelUtil.getJSSyncTypes(sys, objType) : null;
      if (syncTypeNames == null)
         return "null";
      else {
         StringBuilder sb = new StringBuilder();
         if (syncTypeNames.size() == 0) {
            sb.append("java.util.Collections.emptySet()");
         }
         else {
            sb.append("new java.util.HashSet<String>(java.util.Arrays.asList(");
            int i = 0;
            for (String syncTypeName:syncTypeNames) {
               if (i != 0)
                  sb.append(", ");
               sb.append('"');
               sb.append(syncTypeName);
               sb.append('"');
               i++;
            }
            sb.append("))");
         }
         return sb.toString();
      }
   }

   public String getResetSyncTypeNames() {
      LayeredSystem sys = objType.getLayeredSystem();
      Set<String> syncTypeNames = sys.options.syncTypeFilter ? ModelUtil.getResetSyncTypes(sys, objType) : null;
      if (syncTypeNames == null)
         return "null";
      else {
         StringBuilder sb = new StringBuilder();
         if (syncTypeNames.size() == 0) {
            sb.append("java.util.Collections.emptySet()");
         }
         else {
            sb.append("new java.util.HashSet<String>(java.util.Arrays.asList(");
            int i = 0;
            for (String syncTypeName:syncTypeNames) {
               if (i != 0)
                  sb.append(", ");
               sb.append('"');
               sb.append(syncTypeName);
               sb.append('"');
               i++;
            }
            sb.append("))");
         }
         return sb.toString();
      }
   }

   public String getBeforeNewObject() {
      return beforeNewObject;
   }

   public String getObjectVariableSetter() {
      if (customSetter != null && customSetter.length() > 0)
         return customSetter;
      else
         return "      " + lowerClassName + " = _" + lowerClassName + ";\n";
   }

   public List<String> getConstructorProperties() {
      LayeredSystem sys = objType.getLayeredSystem();
      Layer typeLayer = objType.getLayer();
      return ClassDeclaration.getConstructorPropNamesForType(sys, objType, typeLayer);
   }

   public String getConstructorPropInit() {
      List<String> constructorProps = getConstructorProperties();
      if (constructorProps != null && constructorProps.size() > 0) {
         StringBuilder sb = new StringBuilder();
         appendStringList(sb, constructorProps);
         return sb.toString();
      }
      return "null";
   }
}
