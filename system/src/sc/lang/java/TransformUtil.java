/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.IBindable;
import sc.dyn.IObjChildren;
import sc.lang.sc.PropertyAssignment;
import sc.lang.template.Template;
import sc.layer.LayeredSystem;
import sc.layer.SrcEntry;
import sc.util.FileUtil;
import sc.lang.*;
import sc.parser.*;
import sc.util.PerfMon;

import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.util.*;

public class TransformUtil {
   final static String[] BINDABLE_INTERFACE = {"sc", "bind", "IBindable"};
   final static String BINDABLE_ANNOTATION = "sc.bind.Bindable";

   final static String[] COMPONENT_INTERFACE = {"sc", "obj", "IComponent"};
   final static String[] ALT_COMPONENT_INTERFACE = {"sc", "obj", "IAltComponent"};

   static final String[] OBJ_CHILDREN_INTERFACE = {"sc", "dyn", "IObjChildren"};

   static final ArrayList<Object> objChildrenParameters = new ArrayList<Object>(1);
   static {
      objChildrenParameters.add(Boolean.class);
   }

   /**
    * When we move a field initializer to become a regular statement in the preInit method
    * we need to find any x = {a, b} and turn them into x = new T(x)[] = {a, b}
    */
   public static void convertArrayInitializersToNewExpressions(List<Statement> refInitializers) {
      for (int i = 0; i < refInitializers.size(); i++) {
         Statement st = refInitializers.get(i);
         convertArrayInitializerToNewExpression(st);
      }
   }

   public static NewExpression convertArrayInitializerToNewCollection(JavaSemanticNode st, Object arrayType, ArrayInitializer arrInit) {
      String containerTypeName = null;
      Object componentType;
      if (ModelUtil.hasTypeParameters(arrayType)) {
         // Need a new util method for this?  We are getting the closest approximation to the type we can get from the first type parameter.
         // It might be a type variable in which case we turn in into the default type.
         if (arrayType instanceof ParamTypeDeclaration) {
            componentType = ((ParamTypeDeclaration) arrayType).getDefaultType(0);
         }
         else if (arrayType instanceof ParameterizedType) {
            componentType = ((ParameterizedType) arrayType).getActualTypeArguments()[0];
            if (ModelUtil.isTypeVariable(componentType))
               componentType = ModelUtil.getTypeParameterDefault(componentType);
         }
         else
            throw new UnsupportedOperationException();
      }
      else
         componentType = Object.class;

      // If array type is not abstract and has a constructor which takes an array of objects, use that
      // constructor to construct the type.  List's default class is ArrayList.
      if (!ModelUtil.hasModifier(arrayType, "abstract")) {
         Object[] constructors = ModelUtil.getConstructors(arrayType, st.getEnclosingType());
         if (constructors != null) {
            for (Object constructor:constructors) {
               Object[] paramTypes = ModelUtil.getParameterTypes(constructor);
               if (paramTypes != null && paramTypes.length == 1 && ModelUtil.isAssignableFrom(paramTypes[0], List.class)) {
                  containerTypeName = ModelUtil.getTypeName(arrayType);
                  break;
               }
            }
         }
         else {
            if (arrayType instanceof ParamTypeDeclaration && ModelUtil.getTypeName(((ParamTypeDeclaration) arrayType).getBaseType()).equals("java.util.List"))
               containerTypeName = "java.util.ArrayList";
         }
      }
      if (containerTypeName == null) {
         if (ModelUtil.getTypeName(arrayType).equals("java.util.List")) {
            containerTypeName = "java.util.ArrayList";
         }
      }
      if (containerTypeName == null)
         st.displayError("Invalid collection type for array initialization.  No default concrete class registered for type: " + ModelUtil.getTypeName(arrayType) + " for expr: ");
      Expression arrayArg = convertToNewExpression(ModelUtil.getTypeName(componentType), arrInit, "[]");
      SemanticNodeList<Expression> asListArgs = new SemanticNodeList<Expression>(1);
      asListArgs.add(arrayArg);
      IdentifierExpression asListExpr = IdentifierExpression.create("java.util.Arrays.asList");
      asListExpr.setProperty("arguments", asListArgs);

      SemanticNodeList<Expression> newArgs = new SemanticNodeList<Expression>(1);
      newArgs.add(asListExpr);
      return NewExpression.create(containerTypeName, newArgs);
   }

   /** This handles the case where we are converting from a field to a setX or something like that.  It also converts {} into the proper collection creation. */
   public static void convertArrayInitializerToNewExpression(Statement st) {
      if (st instanceof AssignmentExpression) {
         AssignmentExpression ae = (AssignmentExpression) st;
         if (ae.rhs instanceof ArrayInitializer) {
            ArrayInitializer arrInit = (ArrayInitializer) ae.rhs;
            Object arrayType = ((ITypedObject)ae.fromDefinition).getTypeDeclaration();
            if (!ModelUtil.isArray(arrayType) && ModelUtil.isAssignableFrom(Collection.class, arrayType)) {
               NewExpression newContainerExpr = convertArrayInitializerToNewCollection(st, arrayType, arrInit);
               ae.setProperty("rhs", newContainerExpr);
            }
            else if (ModelUtil.isArray(arrayType)) {
               // Note: ae is not started here but its fromDefinition is so we get the type from that
               String typeName = ModelUtil.getTypeName(arrayType);
               int dimIx = typeName.indexOf("[");
               String arrayDims = null;
               if (dimIx != -1) {
                  arrayDims = typeName.substring(dimIx);
                  typeName = typeName.substring(0,dimIx);
               }
               ae.setProperty("rhs", convertToNewExpression(typeName, (ArrayInitializer) ae.rhs, arrayDims));
            }
         }
         else if (ae.fromDefinition instanceof VariableDefinition) {
            VariableDefinition def = (VariableDefinition) ae.fromDefinition;
            if (def.needsCastOnConvert) {
               ae.setProperty("rhs", CastExpression.create(ModelUtil.getTypeName(def.getTypeDeclaration()), ae.rhs));
            }
         }
         else if (ae.fromDefinition instanceof PropertyAssignment) {
            PropertyAssignment def = (PropertyAssignment) ae.fromDefinition;
            if (def.needsCastOnConvert) {
               // Turn this off so it does not happen twice
               def.needsCastOnConvert = false;
               ae.setProperty("rhs", CastExpression.create(ModelUtil.getTypeName(def.getTypeDeclaration()), ae.rhs));
            }
         }
      }
   }

   private final static String OBJECT_DEFINITION =
           "<% if (!overrideField) { %><%=fieldModifiers%> <%=variableTypeName%> <%=lowerClassName%>;<% } %>\n" +
                   "<%= typeSettingsAnnotation %>" +
                   "<%=getModifiers%> <%=variableTypeName%> get<%=upperClassName%>() {\n" +
                   "   return (<%=variableTypeName%>) (<%=lowerClassName%> == null ? <%=lowerClassName%> = new <%=typeName%>() : <%=lowerClassName%>);\n" +
                   "}\n";

   private static Template objectDefinitionTemplate;

   static Template getObjectDefinitionTemplate() {
      if (objectDefinitionTemplate != null)
         return objectDefinitionTemplate;
      return objectDefinitionTemplate = parseTemplate(OBJECT_DEFINITION, ObjectDefinitionParameters.class, false);
   }

   // TODO: move this into LayeredSystem so we don't rely on static things that could theoretically change between layered systems
   private static final HashMap<String,Template> templateCache = new HashMap<String, Template>();

   public static void clearTemplateCache() {
      templateCache.clear();
      templateResourceCache.clear();

      complexObjectDefinitionTemplate = null;
      objectDefinitionTemplate = null;
      componentObjectDefinitionTemplate = null;
      componentClassDefinitionTemplate = null;
      objChildrenDefinitionTemplate = null;
      propertyDefinitionTemplate = null;
      interfaceGetSetTemplate = null;
      propertyDefinitionStaticTemplate = null;
      dynTypePropDefStaticTemplate = null;
      initedDefinition = altInitedDefinition = null;
      bindableDefinitions = null;
   }

   public static Template parseTemplate(String templateCode, Class defaultExtendsType, boolean cache) {
      if (cache) {
         Template t = templateCache.get(templateCode);
         if (t != null)
            return t;
      }
      Object res = TemplateLanguage.INSTANCE.parseString(templateCode);
      if (res instanceof ParseError)
         throw new IllegalArgumentException(((ParseError) res).errorStringWithLineNumbers(templateCode));
      Template template = (Template) ParseUtil.nodeToSemanticValue(res);
      template.defaultExtendsType = defaultExtendsType;
      template.setLayeredSystem(LayeredSystem.getCurrent());
      ParseUtil.initAndStartComponent(template);
      if (cache) {
         templateCache.put(templateCode, template);
      }
      return template;
   }

   private final static String COMPLEX_OBJECT_DEFINITION =
       "<% if (needsField) { %><%=fieldModifiers%> <%=variableTypeName%> <%=lowerClassName%>;\n<% } %>" +
       "<%= typeSettingsAnnotation %>" +
       "<%=getModifiers%> <%=variableTypeName%> get<%=upperClassName%>() {\n" +
       "<% if (needsCustomResolver) { %>\n" +
          "<%= customResolver %>" +
       "   if (_<%=lowerClassName%> == null) {\n" +
       "      _<%=lowerClassName%> = new <%=typeName%>();\n" +
              "<%= customSetter %>" +
              "<%= preAssignment %>" +
       "      <%=getDynamicTypeDefinition('_' + lowerClassName, 2)%>\n<%=propertyAssignments%>\n" +
              "<%= postAssignment %>" +
       "      return _<%=lowerClassName%>;\n" +
       "   } \n" +
       "   else return _<%=lowerClassName%>;\n" +
       "<% } else { %>" +
       "   if (<%=lowerClassName%> == null) {\n" +
       "      <%=variableTypeName%> _<%=lowerClassName%> = new <%=typeName%>();\n" +
       "      <%=lowerClassName%> = _<%=lowerClassName%>;\n" +
             "<%= preAssignment %>" +
       "      <%=getDynamicTypeDefinition('_' + lowerClassName, 2)%>\n<%=propertyAssignments%>\n" +
             "<%= postAssignment %>" +
       "      return _<%=lowerClassName%>;\n" +
       "   } \n" +
       "   else return <%=returnCast%><%=lowerClassName%>;\n" +
       "<% } %>" +
       "}\n";

   private static Template complexObjectDefinitionTemplate;

   static Template getComplexObjectDefinitionTemplate() {
      if (complexObjectDefinitionTemplate != null)
         return complexObjectDefinitionTemplate;
      return complexObjectDefinitionTemplate = parseTemplate(COMPLEX_OBJECT_DEFINITION, ObjectDefinitionParameters.class, false);
   }

   private final static String COMPONENT_OBJECT_DEFINITION =
                   "<% if (needsField) { %><%=fieldModifiers%> <%=variableTypeName%> <%=lowerClassName%>;<% } %>\n" +
                   "<%=getModifiers%> <%=variableTypeName%> get<%=upperClassName%>(boolean doInit) {\n" +
                   "<% if (needsCustomResolver) { %>\n" +
                      "<%= customResolver %>" +
                   "<% } %> \n" +
                   "   if (<%=lowerClassName%> == null) {\n" +
                   "      <%=lowerClassName%> = new <%=typeName%>();\n" +
                         "<%= customSetter %>" +
                         "<%= preAssignment %>" +
                   "      <%=lowerClassName%>.<%=altPrefix%>preInit();\n" +
                   "      <%=getDynamicTypeDefinition(lowerClassName, 2)%>\n<%=propertyAssignments%>\n" +
                         "<%= postAssignment %>" +
                   "      if (doInit) {\n" +
                   "         <%=lowerClassName%>.<%=altPrefix%>init();\n" +
                   "         <%=lowerClassName%>.<%=altPrefix%>start();\n" +
                   "      }\n" +
                   "      return <%=returnCast%><%=lowerClassName%>;\n" +
                   "   } \n" +
                   "   else return <%=returnCast%><%=lowerClassName%>;\n" +
                   "}\n" +
                   "\n" +
                   "<%= typeSettingsAnnotation %>" +
                   "<%=getModifiers%> <%=variableTypeName%> get<%=upperClassName%>() { return get<%=upperClassName%>(true); }\n";

   private static Template componentObjectDefinitionTemplate;

   static Template getComponentObjectDefinitionTemplate() {
      if (componentObjectDefinitionTemplate != null)
         return componentObjectDefinitionTemplate;
      return componentObjectDefinitionTemplate = parseTemplate(COMPONENT_OBJECT_DEFINITION, ObjectDefinitionParameters.class, false);
   }

   private final static String OBJ_CHILDREN_DEFINITION =
           "<% if (numChildren > 0) { %>"+
                "   public Object[] getObjChildren(boolean create) {\n" +
                "      if (create) {\n" +
                "         return new Object[] {<%=childrenNamesNoPrefix%>};\n" +
                "      }\n" +
                "      else {\n" +
                "         return new Object[] {<%=childrenFieldNames%>};\n" +
                "      }\n" +
                "   }\n" +
           "<% } %>";

   private static Template objChildrenDefinitionTemplate;

   static Template getObjChildrenDefinitionTemplate() {
      if (objChildrenDefinitionTemplate != null)
         return objChildrenDefinitionTemplate;
      return objChildrenDefinitionTemplate = parseTemplate(OBJ_CHILDREN_DEFINITION, ObjectDefinitionParameters.class, false);
   }

   private final static String COMPONENT_CLASS_DEFINITION =
           "<% if (!isAbstract) { %>"+
           "<%=getModifiers%> <%=variableTypeName%> new<%=upperClassName%>(boolean doInit<%=nextConstructorDecls%>) {\n" +
                   "   <%=variableTypeName%> <%=lowerClassName%> = " +
                      "<% if (typeIsCompiledComponent) { %>" +
                         "<%=typeClassName%>.new<%=typeBaseName%>(false<%=nextConstructorParams%>)" +
                      "<% } else { %> " +
                         "new <%=typeName%>(<%=constructorParams%>) " +
                      "<% } %>;\n" +
                      "<%= preAssignment %>" +
                   "   <%=lowerClassName%>.<%=altPrefix%>preInit();\n" +
                   "   <%=getDynamicTypeDefinition(lowerClassName, 1)%>\n<%=propertyAssignments%>\n" +
                      "<%= postAssignment %>" +
                   "   if (doInit) {\n" +
                   "      <%=lowerClassName%>.<%=altPrefix%>init();\n" +
                   "      <%=lowerClassName%>.<%=altPrefix%>start();\n" +
                   "   }\n" +
                   "   return <%=returnCast%><%=lowerClassName%>;\n" +
                   "}\n" +
                   "\n" +
          "<%=getModifiers%> <%=variableTypeName%> new<%=upperClassName%>(<%=constructorDecls%>) { return new<%=upperClassName%>(true<%=nextConstructorParams%>); }\n" +
          "<% } %>";

   private static Template componentClassDefinitionTemplate;

   static Template getComponentClassDefinitionTemplate() {
      if (componentClassDefinitionTemplate != null)
         return componentClassDefinitionTemplate;
      return componentClassDefinitionTemplate = parseTemplate(COMPONENT_CLASS_DEFINITION, ObjectDefinitionParameters.class, false);
   }

   // Code snippet for injecting bindability into a class

   private final static String BINDABLE_DEFINITIONS =
           "private BindingListener[] _bindingListeners;" +
                   "   public BindingListener[] getBindingListeners() {" +
                   "      return _bindingListeners;" +
                   "   }"+
                   "   public void setBindingListeners(BindingListener [] bindings) {" +
                   "     _bindingListeners = bindings;" +
                   "   }";


   static List<Statement> bindableDefinitions;

   static List<Statement> getBindableDefinitions() {
      if (bindableDefinitions != null)
         return bindableDefinitions;

      Object res = SCLanguage.INSTANCE.parseString(BINDABLE_DEFINITIONS, SCLanguage.INSTANCE.classBodySnippet);
      if (res instanceof ParseError)
         throw new IllegalArgumentException(res.toString());
      return bindableDefinitions = (List<Statement>) ParseUtil.nodeToSemanticValue(res);
   }

   // TODO: This is not used currently but has the basic logic to add a bindable interface at the class level, so we do not have to use
   // a weak hash-table to store the bindings for a given instance.
   public static void makeClassesBindable(JavaModel model)
   {
      if (model.types != null) {
         for (Definition def:model.types)
            if (def instanceof ClassDeclaration && def.getAnnotation(BINDABLE_ANNOTATION) != null)
               makeClassBindable((ClassDeclaration) def);
      }
   }

   public static void makeClassBindable(ClassDeclaration cd)
   {
      if (!ModelUtil.isAssignableFrom(IBindable.class, cd)) {
         ClassType bindableType = (ClassType) ClassType.create(BINDABLE_INTERFACE);
         cd.addImplements(bindableType);
         cd.body.addAll(getBindableDefinitions());
      }
   }

   /*
   private final static String OBJECT_MAIN_TEMPLATE =
           "   public static void main(String[] args) {" +
           "      <%=typeName%>.main(args);\n" +
           "   }";

   private static Template objectMainTemplate;

   static Template getObjectMainTemplate() {
      if (objectMainTemplate != null)
         return objectMainTemplate;
      return objectMainTemplate = parseTemplate(OBJECT_MAIN_TEMPLATE, ObjectDefinitionParameters.class);
   }
   */

   public static int parseClassBodySnippet(TypeDeclaration accessClass, String codeToInsert, boolean applyToHiddenBody, int insertPos, SemanticNodeList<Statement> fromStatements, Statement globalFromStatement, String templateFromStr) {
      if (codeToInsert == null || codeToInsert.trim().length() == 0)
          return 0;
      Object result = SCLanguage.INSTANCE.parseString(codeToInsert, SCLanguage.INSTANCE.classBodySnippet);
      if (result instanceof ParseError) {
         System.err.println("Error parsing template: " + templateFromStr + " for type: " + accessClass.typeName +  ": " + ((ParseError) result).errorStringWithLineNumbers(codeToInsert));
         return -1;
      }
      else {
         IParseNode node = (IParseNode) result;
         // If the previous node is not generated, we need to stick in some indentation since that is the
         // responsibility of the previous node.
         if (!applyToHiddenBody)
            appendIndentIfNecessary(accessClass.body);
         SemanticNodeList list = (SemanticNodeList) ParseUtil.nodeToSemanticValue(node);
         // Sometimes the template does not actually add anything, like abstract classes
         if (list != null) {
            if (!applyToHiddenBody) {
               accessClass.initBody();
               if (insertPos == -1)
                  accessClass.body.addAll(list);
               else
                  accessClass.body.addAll(insertPos, list);
            }
            else {
               accessClass.initHiddenBody();
               if (insertPos == -1)
                  accessClass.hiddenBody.addAll(list);
               else
                  accessClass.hiddenBody.addAll(insertPos, list);
            }

            if (globalFromStatement != null) {
               for (Object st:list) {
                  if (st instanceof Statement)
                     ((Statement) st).fromStatement = globalFromStatement;
               }
            }

            // For debugging registration, we need to walk the generated code and re-establish the links to the assignment expressions from which these were generated.
            if (fromStatements != null || globalFromStatement != null)
               ModelUtil.updateFromStatementRefs(list, fromStatements, globalFromStatement);

            // Need to init/start these after they have been added to the hierarchy so they can resolve etc.
            ParseUtil.initAndStartComponent(list);

            return list.size();
         }
         return 0;
      }
   }

   public static void addObjectDefinition(TypeDeclaration accessClass, TypeDeclaration objType, ObjectDefinitionParameters parameters,
                                          SemanticNodeList<Statement> assignments, Template customTemplate,
                                          boolean isObject, boolean isComponent, boolean applyToHiddenBody, boolean mainDef) {
      PerfMon.start("addObjectDefinition");
      Template template = customTemplate;
      String templateFromStr = null;
      if (assignments != null || parameters.overrideGet || ModelUtil.getCompileLiveDynamicTypes(objType) || parameters.getNeedsCustomResolver() || objType.needsSync()) {
         if (assignments != null)
            parameters.propertyAssignments = ParseUtil.toLanguageString(SCLanguage.INSTANCE.blockStatements, assignments);
         if (template == null && isObject) {
            template = getComplexObjectDefinitionTemplate();
            templateFromStr = "<default complex object template>";
         }
      }
      // Simpler template for smaller code when there is nothing else to do in there
      else if (template == null && isObject) {
         template = getObjectDefinitionTemplate();
         templateFromStr = "<default object template>";
      }

      if (customTemplate == null) {
         if (isComponent) {
            if (isObject) {
               template = getComponentObjectDefinitionTemplate();
               templateFromStr = "<default component object template>";
            }
            else {
               template = getComponentClassDefinitionTemplate();
               templateFromStr = "<default component class template>";
            }
         }
      }
      else {
         SrcEntry srcFile = customTemplate.getSrcFile();
         if (srcFile == null)
            templateFromStr = "huh?";
         else
            templateFromStr = customTemplate.getSrcFile().toString();
      }

      // Nothing to do for simple classes but which have inner objects.
      if (template != null) {
         ExecutionContext ctx = new ExecutionContext();
         ctx.pushCurrentObject(parameters);
         String codeToInsert;
         try {
            PerfMon.start("evalObjectTemplate");
            codeToInsert = (String) template.eval(String.class, ctx);
            PerfMon.end("evalObjectTemplate");
         }
         catch (IllegalArgumentException exc) {
            objType.displayError("Template: ", templateFromStr, " for: ", template.getModelTypeName(), ":  reports: ", exc.toString(), " ");
            return;
         }

         objType.bodyChanged();
         PerfMon.start("parseClassSnippet");
         parseClassBodySnippet(accessClass, codeToInsert, applyToHiddenBody, -1, assignments, objType, templateFromStr);
         PerfMon.end("parseClassSnippet");
      }
      // This is the case where it's not a component or an object so we do not use a template to redefine the creation semantics of the type.
      // But we do have pre or post assignments.  They either get inserted into each constructor or
      else if (parameters.postAssignments != null || parameters.preAssignments != null) {
         parameters.noCreationTemplate = true;

         StringBuilder initString = null;

         String preAssignment = parameters.getPreAssignment();
         if (preAssignment != null && preAssignment.length() > 0) {
            initString = new StringBuilder();
            initString.append(preAssignment);
         }

         String postAssignment = parameters.getPostAssignment();
         if (postAssignment != null && postAssignment.length() > 0) {
            Object[] constrs = objType.getConstructors(null);
            if (constrs == null || constrs.length == 0) {
               if (initString == null)
                  initString = new StringBuilder();
               initString.append(postAssignment);
            }
            else {
               if (parameters.currentConstructor instanceof ConstructorDefinition) {
                  SemanticNodeList<Statement> toInsert = (SemanticNodeList<Statement>) parseCodeTemplate(parameters, postAssignment, SCLanguage.getSCLanguage().blockStatements, true);
                  ConstructorDefinition cdef = (ConstructorDefinition) parameters.currentConstructor;
                  // Only add it to the first constructor if there's a chain
                  if (!cdef.callsThis())
                     cdef.body.addAllStatementsAt(cdef.body.getNumStatements(), toInsert);
               }
            }
         }

         // If this is the first constructor only, we may need to add the addDynInnerObject or addDynInnerInstance call.
         if (mainDef && parameters.getLiveDynamicTypes()) {
            if (initString == null)
               initString = new StringBuilder();
            initString.append(parameters.getDynamicTypeDefinition("this", 2));
         }

         if (initString != null && initString.length() > 0) {
            addTemplateToTypeBody(objType, parameters, initString.toString(), "postAssignment", false);
         }
      }

      if (parameters.getDefinesInnerObjects() && !parameters.typeIsDynamic) {
         // The scope template may have already be generating this... do we need more hooks to coordinate between these two implementations?
         if (!ModelUtil.isAssignableFrom(IObjChildren.class, objType)) {
            // Scopes etc' can also add this - need to be sure not to add it twice
            objType.addImplements(ClassType.create(OBJ_CHILDREN_INTERFACE));
         }

         // Check if this method is already implemented in this type.  If it's implemented in a base type, we still generate the method to pick up new children
         if (objType.declaresMethod("getObjChildren", objChildrenParameters, null, null, false, false, null, null, false) == null) {
            String getObjCodeToInsert = evalTemplate(parameters, getObjChildrenDefinitionTemplate());

            parseClassBodySnippet(objType, getObjCodeToInsert, applyToHiddenBody, -1, null, objType, "<default obj children template>");
         }
      }

      // for top-level object definitions, where the class has a main method, we add our own static main
      // that redirects to that one.  This lets object definitions inherit main behavior from their supertype.
      /*
        but there's no way for the inherited main class to instantiate the right object and so this is not helpful!
      MethodDefinition mainDef;
      if (isObject && accessClass == objType &&
         (mainDef = (MethodDefinition) accessClass.definesMethod("main", MethodDefinition.MAIN_ARGS)) != null && mainDef.isMain) {
         Template mainTemplate = getObjectMainTemplate();
         String mainCode = (String) mainTemplate.eval(String.class, ctx);
         Object mainResult = JavaLanguage.INSTANCE.parseString(mainCode, JavaLanguage.INSTANCE.classBodySnippet);
         if (mainResult instanceof ParseError)
            System.err.println("*** error parsing: " + ((ParseError) result).errorStringWithLineNumbers(mainCode));
         else {
            IParseNode node = (IParseNode) mainResult;
            // If the previous node is not generated, we need to stick in some indentation since that is the
            // responsibility of the previous node.
            appendIndentIfNecessary((SemanticNodeList) accessClass.body);
            SemanticNodeList list = (SemanticNodeList) ParseUtil.nodeToSemanticValue(node);
            accessClass.body.addAll(list);
            // Need to init/start these after they have been added to the hierarchy so they can resolve etc.
            ParseUtil.initAndStartComponent(list);
         }
      }
      */
      objType.dependentTypes = null;
      PerfMon.end("addObjectDefinition");
   }

   private final static String INITED_DEFINITION = "protected byte _initState; public byte getInitState() { return _initState; }";

   static SemanticNodeList<Statement> initedDefinition;

   private final static String ALT_INITED_DEFINITION = "protected byte _initState; public byte _getInitState() { return _initState; }";

   static SemanticNodeList<Statement> altInitedDefinition;

   public static void addInitedDefinition(ClassDeclaration classDeclaration, boolean useAltComponent) {
      List<Statement> sts = useAltComponent ? TransformUtil.getAltInitedDefinition() : TransformUtil.getInitedDefinition();
      for (Statement s:sts) {
         classDeclaration.addBodyStatementIndent(s);
         s.setFromStatement(classDeclaration.fromStatement);
      }
   }

   static SemanticNodeList<Statement> getInitedDefinition() {
      if (initedDefinition == null) {
         Object res = SCLanguage.INSTANCE.parseString(INITED_DEFINITION, SCLanguage.INSTANCE.classBodySnippet);
         if (res instanceof ParseError)
            throw new IllegalArgumentException(res.toString());
         initedDefinition = ((SemanticNodeList<Statement>) ParseUtil.nodeToSemanticValue(res));
      }
      return (SemanticNodeList<Statement>) initedDefinition.deepCopy(ISemanticNode.CopyNormal, null);
   }

   static SemanticNodeList<Statement> getAltInitedDefinition() {
      if (altInitedDefinition == null) {
         Object res = SCLanguage.INSTANCE.parseString(ALT_INITED_DEFINITION, SCLanguage.INSTANCE.classBodySnippet);
         if (res instanceof ParseError)
            throw new IllegalArgumentException(res.toString());
         altInitedDefinition = (SemanticNodeList<Statement>) ParseUtil.nodeToSemanticValue(res);
      }
      return (SemanticNodeList<Statement>) altInitedDefinition.deepCopy(ISemanticNode.CopyNormal, null);
   }

   /**
    * This is a bit of a tricky situation.  When we are adding a new language construct, if the construct was
    * parsed, it will not add indentation properly to the first node.  We will not format the previous node
    * in that case and it is responsible for inserting the indent for the next node.  In this case, we just need
    * to stick in indentation.  But if the previous node is generated, we can just skip that.
    */
   public static void appendIndentIfNecessary(SemanticNodeList node) {
      if (node == null)
         return;
      if (node.size() == 0) {
         IParseNode listPN = node.getParseNode();
         if (listPN instanceof ParentParseNode) {
            ParentParseNode listPPN = (ParentParseNode) listPN;
            if (listPPN.children != null && listPPN.children.size() > 0) {
               Object symbolPN = listPPN.children.get(0);
               if (symbolPN instanceof ParentParseNode) {
                  ParentParseNode symbolPPN = (ParentParseNode) symbolPN;
                  if (symbolPPN.children != null && symbolPPN.children.size() == 2) {
                     Object spacePN = symbolPPN.children.get(1);
                     if (spacePN instanceof ParentParseNode) {
                        ParentParseNode spacePPN = (ParentParseNode) spacePN;
                        if (spacePPN.children != null && spacePPN.children.size() == 1) {
                           Object spaceChild = spacePPN.children.get(0);
                           if (spaceChild == null || spaceChild.toString().equals("\n"))
                              spacePPN.children.set(0, "\n" + FormatContext.INDENT_STR);
                        }
                     }
                  }
               }
            }
         }
         return;
      }
      int lastIx = node.size() - 1;
      appendIndentIfNecessary(node, lastIx);
   }

   public static void appendIndentIfNecessary(SemanticNodeList node, int ix) {
      if (ix >= node.size())
         return;
      Object lastNode = node.get(ix);
      if (lastNode instanceof ISemanticNode) {
         ISemanticNode semNode = (ISemanticNode) lastNode;
         IParseNode p = semNode.getParseNode();
         if (p instanceof ParentParseNode) {
            ParentParseNode ppnode = (ParentParseNode) p;
            if (!ppnode.isGenerated() && ppnode.children != null) {
               ppnode.children.add(FormatContext.INDENT_STR);
            }
         }
      }
   }

   private final static HashMap<String,Template> templateResourceCache = new HashMap<String, Template>();

   private final static String[] templateResourceExtensions = {"sctp", "sctd", "sctdynt"};

   public static Template parseTemplateResource(String templateResourceTypeName, Class params, ClassLoader loader) {
      Template t = templateResourceCache.get(templateResourceTypeName);
      if (t != null)
         return t;
      InputStream is = null;
      String resourcePath = null;
      for (int i = 0; i < templateResourceExtensions.length; i++) {
         String ext = templateResourceExtensions[i];
         resourcePath = FileUtil.addExtension(templateResourceTypeName.replace('.','/'), ext);
         is = loader.getResourceAsStream(resourcePath);
         if (is != null) {
            break;
         }
      }
      if (is == null)
         return null;
      StringBuilder templateBuf = FileUtil.readInputStream(is);
      if (templateBuf == null) {
         System.err.println("*** Failed to read: " + templateResourceTypeName + " in system class path");
         return null;
      }

      Template res = TransformUtil.parseTemplate(templateBuf.toString(), params, false);
      res.setSrcFile(new SrcEntry(null, resourcePath, resourcePath, resourcePath));
      templateResourceCache.put(templateResourceTypeName, res);
      return res;
   }

   public static String evalTemplateResource(String templateResourceTypeName, Object paramObj, ClassLoader loader) {
      Template template = parseTemplateResource(templateResourceTypeName, paramObj.getClass(), loader);
      return TransformUtil.evalTemplate(paramObj, template);
   }

   public static int parseClassBodySnippetTemplate(TypeDeclaration typeDeclaration, String templateResourcePath, Object params, boolean hiddenBody, int insertPos, String templateFromStr) {
      String res = evalTemplateResource(templateResourcePath, params, typeDeclaration.getLayeredSystem().getSysClassLoader());
      return TransformUtil.parseClassBodySnippet(typeDeclaration, res, hiddenBody, insertPos, null, null, templateFromStr);
   }

   private final static String PROPERTY_DEFINITION =
           "<% if (!omitField) { %>\n" +
           "   private <%=fieldModifiers%> <%=propertyTypeName%><%=arrayDimensions%> <%=lowerPropertyName%>" +
                                       "<% if (initializer.length() > 0 && !bindable) { %> = <%=initializer%>; <% } else { %>;<% } %> \n" +
           "<% } %>\n" +
           "   <%=getModifiers%> <%=propertyTypeName%><%=arrayDimensions%> <%=getOrIs%><%=upperPropertyName%>() {\n" +
                   /*
               Turning off for now... this is not tested and causes a lot of overhead so only should be turned on
               if someone specifies an explicit annotation for that property to do lazy evaluation of the binding.
           "<% if (bindable) { %>\n" +
           "      sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_REQUESTED, <%=!isStatic ? \"this\" : enclosingTypeName + \".class\"%>, _<%=lowerPropertyName%>Prop);\n" +
           "<% } %>\n" +
                   */
           "<% if (overrideGetSet) { %>\n" +
           "      return <%=superGetName%>;\n" +
           "<% } \n" +
           "   else { %>\n" +
           "      return <%=lowerPropertyName%>;\n" +
           "<% } %>\n" +
           "   }\n" +
           "   <%=setModifiers%> void set<%=upperPropertyName%>(<%=setTypeName%><%=arrayDimensions%> _<%=lowerPropertyName%>) {\n" +
           "<% if (overrideGetSet) { %>\n" +
           "      <%=superSetName%>(_<%=lowerPropertyName%>);\n" +
           "<% } \n" +
           "   else { %>\n" +
           "      <%=lowerPropertyName%> = _<%=lowerPropertyName%>;\n" +
           "<% } \n" +
           "   if (bindable && sendEvent) { %>\n" +
           "      sc.bind.Bind.sendChange(<%=!isStatic ? \"this\" : enclosingTypeName + \".class\"%>, <%=propertyMappingName%>, _<%=lowerPropertyName%>);\n" +
           "<% } %>\n" +
           "   }\n" +
           "<% if (initializer.length() > 0) { %>\n" +
           "   <% if (!bindable && overrideField) { %>\n" +
           "      <%=isStatic ? \"static \" : null%>{ <%=lowerPropertyName%> = <%=initializer%> }\n" +
           "   <% } \n" +
           "      else if (bindable || overrideGetSet) { %>\n" +
           "      <%=isStatic ? \"static \" : null%>{ set<%=upperPropertyName%>(<%=initializer%>); }\n" +
           "   <% } %>\n" +
           "<% } %> " +
           "<% if (needsIndexedSetter) { %>\n" +
           "   <%=setIndexedModifiers%> void set<%=upperPropertyName%>(int index, <%=setTypeName%> _<%=lowerPropertyName%>Elem) {\n" +
           "      <%=lowerPropertyName%>[index] = _<%=lowerPropertyName%>Elem;\n" +
           "      sc.bind.Bind.sendEvent(sc.bind.IListener.ARRAY_ELEMENT_CHANGED, <%=!isStatic ? \"this\" : enclosingTypeName + \".class\"%>, <%=propertyMappingName%>, index);\n" +
           "   }\n" +
           "<% } %>";

   private final static String PROPERTY_DEFINITION_STATIC =
           "<% if (bindable) { %>\n" +
           "   public final static sc.type.IBeanMapper <%=propertyMappingName%> = sc.dyn.DynUtil.resolvePropertyMapping(<%=enclosingTypeName%>.class, \"<%=lowerPropertyName%>\");\n" +
           "<% } %>\n";

   private final static String DYN_TYPE_PROP_DEF_STATIC_TEMPLATE =
           "<% if (bindable) { %>\n" +
           "   public final static sc.type.IBeanMapper <%=propertyMappingName%> = <%=enclosingOuterTypeName%>.resolvePropertyMapping<%= innerName %>(\"<%=lowerPropertyName%>\");\n" +
           "<% } %>\n";

   private final static String INTERFACE_GETSET =
           "   <%=setModifiers%> void set<%=upperPropertyName%>(<%=setTypeName%><%=arrayDimensions%> _<%=lowerPropertyName%>);\n" +
           "   <%=getModifiers%> <%=propertyTypeName%><%=arrayDimensions%> <%=getOrIs%><%=upperPropertyName%>();\n";


   private static Template propertyDefinitionTemplate;

   static Template getPropertyDefinitionTemplate() {
      if (propertyDefinitionTemplate != null)
         return propertyDefinitionTemplate;

      return propertyDefinitionTemplate = parseTemplate(PROPERTY_DEFINITION,  PropertyDefinitionParameters.class, false);
   }

   private static Template interfaceGetSetTemplate;
   private static Template propertyDefinitionStaticTemplate;
   private static Template dynTypePropDefStaticTemplate;

   static Template getDynTypePropDefStaticTemplate() {
      if (dynTypePropDefStaticTemplate != null)
         return dynTypePropDefStaticTemplate;
      return dynTypePropDefStaticTemplate = parseTemplate(DYN_TYPE_PROP_DEF_STATIC_TEMPLATE, PropertyDefinitionParameters.class, false);
   }

   static Template getInterfaceGetSetTemplate() {
      if (interfaceGetSetTemplate != null)
         return interfaceGetSetTemplate;
      return interfaceGetSetTemplate = parseTemplate(INTERFACE_GETSET, PropertyDefinitionParameters.class, false);
   }

   static Template getPropertyDefinitionStaticTemplate(TypeDeclaration fieldType) {
      if (fieldType.needsDynType())
         return getDynTypePropDefStaticTemplate();
      if (propertyDefinitionStaticTemplate != null)
         return propertyDefinitionStaticTemplate;
      return propertyDefinitionStaticTemplate = parseTemplate(PROPERTY_DEFINITION_STATIC, PropertyDefinitionParameters.class, false);
   }

   private static void setPropertyMappingName(PropertyDefinitionParameters params, TypeDeclaration fieldType) {
      params.propertyMappingName = getPropertyMappingName(fieldType, params.lowerPropertyName, false);
   }

   public static String getPropertyMappingName(TypeDeclaration typeDeclaration, String lowerPropertyName, boolean remap) {
      Object typeObj = null, rootType = null;
      String propertyMappingName;

      if (typeDeclaration != null) {
         // Just use the string if property mappers are totally disabled
         if (!typeDeclaration.getLayeredSystem().usePropertyMappers) {
            return "\"" + lowerPropertyName + "\"";
         }
         if (remap) {
            // Need to find the version of the member which corresponds to where we make this object bindable.  If there's a set method
            // with @Bindable, it will be the set method.   Choosing the most recent definition but not sure this is right.
            Object member = typeDeclaration.definesMember(lowerPropertyName, JavaSemanticNode.MemberType.PropertyAnySet, null, null);
            if (member instanceof PropertyAssignment) {
               member = ((PropertyAssignment) member).assignedProperty;
               if (member == null)
                  throw new UnsupportedOperationException(); // Should not get here!
            }
            typeObj = ModelUtil.getEnclosingType(member);
         }
         else
            typeObj = typeDeclaration;
         rootType = ModelUtil.getRootType(typeObj);
      }
      if (rootType == null || ModelUtil.sameTypes(rootType, typeObj)) {
         if (typeObj == typeDeclaration)
            propertyMappingName = "_" + lowerPropertyName + "Prop";
         else
            propertyMappingName = ModelUtil.getTypeName(typeObj) + "._" + lowerPropertyName + "Prop";
      }
      else {
         String rootTypeName = ModelUtil.getTypeName(rootType);
         String innerTypeName = ModelUtil.getTypeName(typeObj);
         if (rootTypeName.length() >= innerTypeName.length()) {
            System.out.println("*** Error - invalid root type name!");
            innerTypeName = ModelUtil.getTypeName(typeObj); // debug only
         }
         String innerPath = innerTypeName.substring(rootTypeName.length()+1);
         innerPath = innerPath.replace(".","_");
         propertyMappingName = "_" + innerPath + "_" + lowerPropertyName + "Prop";
      }
      return propertyMappingName;
   }

   public static String getPropertyMappingRootName(Object typeObj) {
      Object rootType = ModelUtil.getRootType(typeObj);
      if (rootType == null || rootType == typeObj)
         return ModelUtil.getCompiledClassName(typeObj);
      else {
         String rootTypeName = ModelUtil.getTypeName(rootType);
         String innerTypeName = ModelUtil.getTypeName(typeObj);
         String innerPath = innerTypeName.substring(rootTypeName.length()+1);
         innerPath = innerPath.replace(".","_");
         return ModelUtil.getCompiledClassName(typeObj) + "_" + innerPath;
      }
   }

   static final String[] fieldOnlyModifiers = {"transient", "volatile"};

   static final String[] nonTopLevelTypeModifiers = {"transient", "volatile", "static", "private"};

   // Like the above only adding "abstract".  Even if we generate a stub type which is abstract, we need to generate the class as physical since we may not generate subclasses.
   static final String[] nonStubTypeModifiers = {"transient", "volatile", "static", "private", "abstract"};

   static final String[] typeToConstrModifiers = {"abstract", "static"};

   static final String[] bindableModifiers = {"@Bindable", "@sc.bind.Bindable"};

   static final String[] getSetOnlyModifiers = bindableModifiers;

   public static String removeModifiers(String m, String[] toRemove) {
      for (String toRem:toRemove) {
         int ix = m.indexOf(toRem);
         if (ix != -1) {
            m = m.substring(0,ix) + m.substring(ix+toRem.length());
         }
      }
      return m;
   }

   private static final String[] classOnlyModifiers = {"abstract"};

   public static String removeClassOnlyModifiers(String m) {
      for (String classOnlyMod:classOnlyModifiers) {
         int ix = m.indexOf(classOnlyMod);
         if (ix != -1) {
            m = m.substring(0,ix) + m.substring(ix+classOnlyMod.length());
         }
      }
      return m;
   }

   public static void convertFieldToGetSetMethods(VariableDefinition variableDefinition, boolean bindable, boolean toInterface, ILanguageModel.RuntimeType runtime) {
      String convertedPropName = variableDefinition.getRealVariableName();
      String origPropName = variableDefinition.variableName;

      PropertyDefinitionParameters params = PropertyDefinitionParameters.create(convertedPropName);
      FieldDefinition field = (FieldDefinition) variableDefinition.getDefinition();
      TypeDeclaration typeDeclaration = field.getEnclosingType();

      if (typeDeclaration.propertiesToMakeBindable != null && typeDeclaration.propertiesToMakeBindable.get(origPropName) != null)
         return;

      LayeredSystem sys = typeDeclaration.getLayeredSystem();

      if (sys != null)
         params.useIndexSetForArrays = sys.useIndexSetForArrays;

      // Skipping interfaces here.  We want to only find implementation methods that we have to rename or contend with if we are converting a field and there are already get/set methods in the same type.
      // This is like the "makeBindable" case but we use declares not defines cause we only want to modify get/set methods if they are defined in this same type.  In the makeBindable case we are really
      // remapping the existing property.   For the field case, the code is declaring a new property that should hide the base classes property based on Java's rules.  If however they define a field and get/set methods
      // in the same type, we need to remap them.  This layers on binding on top of the existing get/set methods.
      // TODO: as an optimization we should recognize trivial get/set methods and just insert the sendEvent call instead of adding all of that code.
      params.getMethod = typeDeclaration.declaresMember(origPropName, JavaSemanticNode.MemberType.GetMethodSet, null, null);
      params.setMethod = typeDeclaration.declaresMember(origPropName, JavaSemanticNode.MemberType.SetMethodSet, null, null);

      params.arrayDimensions = field.type.arrayDimensions;

      // TODO: Right now we cannot do static bindable properties on interfaces.  Not sure how that will work
      if (toInterface && params.isStatic) {
         System.err.println("*** can't make static interface properties bindable");
         return;
      }

      params.sendEvent = !ModelUtil.isManualBindable(variableDefinition);
      setPropertyMappingName(params, typeDeclaration);

      int ix = typeDeclaration.body.indexOf(field);

      params.propertyTypeName = field.type.getGenericTypeName(typeDeclaration, false);
      params.overrideField = false;
      params.omitField = false; // Always generate the field here since we replace this one with the generated get/set call

      if (variableDefinition.initializer == null)
         params.initializer = "";
      else {
         Expression varInit;
         if (variableDefinition.initializer instanceof ArrayInitializer) {
            if (params.arrayDimensions != null)
               varInit = convertToNewExpression(field.type.getFullBaseTypeName(), (ArrayInitializer)variableDefinition.initializer, params.arrayDimensions);
            else {
               Object fieldVarType = field.type.getTypeDeclaration();
               if (ModelUtil.isAssignableFrom(Collection.class, fieldVarType)) {
                  varInit = convertArrayInitializerToNewCollection(typeDeclaration, fieldVarType, (ArrayInitializer) variableDefinition.initializer);
               }
               else {
                  typeDeclaration.displayError("Invalid use of array initializer: ");
                  varInit = variableDefinition.initializer;
               }
            }
         }
         // For short x = 3, we need setX((short) 3)
         else if (variableDefinition.needsCastOnConvert)
            varInit = CastExpression.create(field.type.getFullTypeName(), variableDefinition.initializer);
         else
            varInit = variableDefinition.initializer;
         params.initializer = ParseUtil.toLanguageString(SCLanguage.INSTANCE.variableInitializer, varInit);
      }

      makePropertyBindableInType(field, typeDeclaration, convertedPropName, params, true, bindable);

      int vix = -1;
      if (field.variableDefinitions.size() == 1)
         field.parentNode.removeChild(field);
      else {
         vix = field.variableDefinitions.removeChild(variableDefinition);
         if (field.variableDefinitions.size() <= vix)
            vix = -1;
      }

      applyPropertyTemplate(typeDeclaration, ix, params, typeDeclaration, ModelUtil.isInterface(typeDeclaration), variableDefinition);

      // Need to transform the next guy in the list if there is one since the parent call won't anymore
      if (vix != -1)
         field.variableDefinitions.get(vix).transform(runtime);
   }

   public static Expression convertToNewExpression(String arrayTypeName, ArrayInitializer arrayInit, String arrayDimensions) {
      NewExpression paramBindings = new NewExpression();
      paramBindings.typeIdentifier = arrayTypeName;
      SemanticNodeList list = (SemanticNodeList) ParseUtil.nodeToSemanticValue(JavaLanguage.INSTANCE.parseString(arrayDimensions, JavaLanguage.INSTANCE.arrayDims));
      paramBindings.setProperty("arrayDimensions", list);
      paramBindings.setProperty("arrayInitializer", arrayInit);
      return paramBindings;
   }

   private static void applyPropertyTemplate(TypeDeclaration propType, int ix, PropertyDefinitionParameters params, TypeDeclaration fieldType, boolean isInterface, ISrcStatement srcStatement) {
      PerfMon.start("applyPropertyTemplate");
      TypeDeclaration enclosingType = propType.getRootType();
      if (enclosingType == null)
         enclosingType = propType;

      LayeredSystem sys = propType.getLayeredSystem();
      ExecutionContext ctx = new ExecutionContext();
      ctx.pushCurrentObject(params);
      String codeToInsert = (String) (isInterface ? getInterfaceGetSetTemplate() : getPropertyDefinitionTemplate()).eval(String.class, ctx);
      if (sys.usePropertyMappers) {
         PerfMon.start("evalPropTemplate");
         String staticCodeToInsert = (String) getPropertyDefinitionStaticTemplate(fieldType).eval(String.class, ctx);
         PerfMon.end("evalPropTemplate");

         PerfMon.start("parsePropTemplate");
         Object staticResult = SCLanguage.INSTANCE.parseString(staticCodeToInsert, SCLanguage.INSTANCE.classBodySnippet);
         PerfMon.end("parsePropTemplate");

         if (staticResult instanceof ParseError)
            System.err.println("*** error parsing static property template: " + ((ParseError) staticResult).errorStringWithLineNumbers(codeToInsert));
         else {
            PerfMon.start("resolvePropTemplate");
            IParseNode staticNode = (IParseNode) staticResult;
            // If the previous node is not generated, we need to stick in some indentation since that is the
            // responsibility of the previous node.
            //appendIndentIfNecessary((SemanticNodeList) propType.body, ix);
            SemanticNodeList<Statement> staticList = (SemanticNodeList<Statement>) ParseUtil.nodeToSemanticValue(staticNode);

            if (staticList != null) {
               //appendIndentIfNecessary((SemanticNodeList) enclosingType.body);
               // Must put these before any property definitions so that the _Prop variable references will all have been assigned.
               // Alternatively, we could just not use _Prop in the bind call (i.e. only use it for sendEvent)
               enclosingType.addBodyStatementsAt(enclosingType.propDefInsertIndex, staticList);

               // Should we register these lines with the property for debugging purposes?  Since these are at the top of the file, they tend to
               // be the ones we navigate to since they show up first in the list.  Also, why would we need to debug the definition of the property mapper anyway?
               //for (Statement newSt:staticList)
               //   newSt.setFromStatement(srcStatement);

               // Offset ix cause we just inserted a statement ahead of the one we are about to insert it
               if (ix >= enclosingType.propDefInsertIndex && enclosingType == propType)
                  ix++;

               // Need to init/start these after they have been added to the hierarchy so they can resolve etc.
               ParseUtil.initAndStartComponent(staticList);
            }
            PerfMon.end("resolvePropTemplate");
         }
      }

      PerfMon.start("reparsePropTemplate");
      Object result = SCLanguage.INSTANCE.parseString(codeToInsert, SCLanguage.INSTANCE.classBodySnippet);
      PerfMon.end("reparsePropTemplate");
      if (result instanceof ParseError)
         System.err.println("*** error reparsing property template: " + ((ParseError) result).errorStringWithLineNumbers(codeToInsert));
      else {
         PerfMon.start("restartPropTemplate");
         IParseNode node = (IParseNode) result;
         // If the previous node is not generated, we need to stick in some indentation since that is the
         // responsibility of the previous node.
         //appendIndentIfNecessary((SemanticNodeList) propType.body, ix);
         SemanticNodeList list = (SemanticNodeList) ParseUtil.nodeToSemanticValue(node);

         if (srcStatement != null && !isInterface) {
            // Now register the debugging registration - mark statements generated here with the source statement.
            for (Object l:list) {
               // TODO: should we have an option to skip the contents of the getX method
               if (l instanceof Statement)
                  ((Statement) l).updateFromStatementRef(null, srcStatement);
            }
                           /*
            boolean found = false;
            for (Object l:list) {
               String setName = "set" + params.upperPropertyName;
               if (l instanceof MethodDefinition) {
                  MethodDefinition methDef = (MethodDefinition) l;
                  if (methDef.name.equals(setName)) {
                     BlockStatement body = methDef.body;
                     if (body != null && body.statements != null) {
                        // Now matching all statements in the setX method
                        for (Statement bodySt:body.statements) {
                           bodySt.fromStatement = srcStatement;
                           found = true;
                        }
                     }
                  }
               }
            }
            if (!found)
               System.err.println("*** Warning unable to assign registration between source and generated code for debugging");
                           */
         }

         propType.bodyChanged();
         propType.body.addAll(ix, list);

         // Need to init/start these after they have been added to the hierarchy so they can resolve etc.
         ParseUtil.initAndStartComponent(list);
         PerfMon.end("restartPropTemplate");
      }
      PerfMon.end("applyPropertyTemplate");
   }

   public static void makePropertyBindableInType(Object variableDef, TypeDeclaration typeDeclaration, String propertyName, PropertyDefinitionParameters params, boolean includeFinal, boolean bindable) {
      Object getMethod = params.getMethod;
      Object setMethod = params.setMethod;

      Object definition = setMethod == null ? variableDef : setMethod;
      boolean isPropertyIs = getMethod != null && ModelUtil.isPropertyIs(getMethod);
      params.getOrIs = isPropertyIs ? "is" : "get";

      // If getMethod and setMethod are defined in this type, we need to rename them here.
      if (getMethod instanceof MethodDefinition && ((MethodDefinition) getMethod).getEnclosingType() == typeDeclaration) {
         MethodDefinition getMethDef = (MethodDefinition) getMethod;
         params.superGetName = "_bind_" + getMethDef.name;
         getMethDef.setProperty("name", params.superGetName);
         params.superGetName = params.superGetName + "()";
      }
      else {
         if (getMethod != null)
            params.superGetName = "super." + (isPropertyIs ? "is" : "get") + params.upperPropertyName + "()";
            // No get method - just use the field
         else
            params.superGetName = params.lowerPropertyName;
      }

      if (setMethod instanceof MethodDefinition && ((MethodDefinition) setMethod).getEnclosingType() == typeDeclaration) {
         MethodDefinition setMethDef = (MethodDefinition) setMethod;
         params.superSetName = "_bind_" + setMethDef.name;
         setMethDef.setProperty("name", params.superSetName);
      }
      else
         params.superSetName = "super.set" + params.upperPropertyName;

      params.fieldModifiers = removeModifiers(ModelUtil.modifiersToString(variableDef == null ? definition : variableDef, true, false, includeFinal, false, false, JavaSemanticNode.MemberType.Field), getSetOnlyModifiers);
      params.getModifiers = removeModifiers(ModelUtil.modifiersToString(getMethod == null ? definition : getMethod, true, true, false, false, false, JavaSemanticNode.MemberType.GetMethod), fieldOnlyModifiers);
      params.setModifiers = removeModifiers(ModelUtil.modifiersToString(setMethod == null ? definition : setMethod, true, true, false, false, false, JavaSemanticNode.MemberType.SetMethod), fieldOnlyModifiers);

      if (params.getNeedsIndexedSetter()) // Not including annotations here because for JPA and datanucleus in particular, @Lob and other annotations on an array will cause it to try and enhance the setIndexed method which leads to a verify error
         params.setIndexedModifiers = removeModifiers(ModelUtil.modifiersToString(setMethod == null ? definition : setMethod, false, true, false, false, false, JavaSemanticNode.MemberType.SetMethod), fieldOnlyModifiers);

      if (bindable && ModelUtil.getBindableAnnotation(variableDef == null ? definition : variableDef) == null) {
         // TODO: eliminate two of these?
         params.setModifiers = "@sc.bind.Bindable(manual=true) " + removeModifiers(params.setModifiers, bindableModifiers);
         params.getModifiers = "@sc.bind.Bindable(manual=true) " + removeModifiers(params.getModifiers, bindableModifiers);
      }
      params.setTypeName = setMethod == null ? params.propertyTypeName : ModelUtil.getGenericSetMethodPropertyTypeName(typeDeclaration, setMethod, false);
      params.enclosingTypeName = typeDeclaration.getFullTypeName();

      TypeDeclaration rootType = typeDeclaration.getRootType();
      if (rootType == null)
         rootType = typeDeclaration;
      params.enclosingOuterTypeName = rootType.getFullTypeName();
      params.overrideGetSet = setMethod != null;
      params.isStatic = ModelUtil.hasModifier(definition, "static");
      params.bindable = bindable;
      setPropertyMappingName(params, typeDeclaration);
      params.innerName = typeDeclaration.getDynamicStubParameters().getInnerName();
   }

   public static void makePropertyBindable(TypeDeclaration typeDeclaration, String propertyName, ILanguageModel.RuntimeType runtime) {
      PropertyDefinitionParameters params = PropertyDefinitionParameters.create(propertyName);
      TypeContext tctx = new TypeContext();
      tctx.transformed = true;
      Object variableDef = typeDeclaration.definesMember(propertyName, JavaSemanticNode.MemberType.FieldSet, null, tctx);
      // Skipping interfaces here.  We want to only find implementation methods that we have to rename or contend with if we are converting a field and there are already get/set methods in the same type.
      Object getMethod = params.getMethod = typeDeclaration.definesMember(propertyName, JavaSemanticNode.MemberType.GetMethodSet, null, tctx);
      Object setMethod = params.setMethod = typeDeclaration.definesMember(propertyName, JavaSemanticNode.MemberType.SetMethodSet, null, tctx);

      if (variableDef == null) {
         if (setMethod == null) {
            System.err.println("*** Can't make property: " + propertyName + " bindable in type:" + typeDeclaration.typeName + " - no set method or field found");
            return;
         }
         if (getMethod == null) {
            System.err.println("*** Can't make property: " + propertyName + " bindable in type:" + typeDeclaration.typeName + " - no get method or field found");
            return;
         }
      }

      if ((getMethod != null && ModelUtil.isAbstractMethod(getMethod)) || (setMethod != null && ModelUtil.isAbstractMethod(setMethod))) {
         typeDeclaration.displayError("Unable to make properties implemented with abstract methods bindable: " + propertyName + ": ");
         return;
      }

      params.propertyTypeName = ModelUtil.getGenericTypeName(typeDeclaration, getMethod == null ? variableDef : getMethod, true);
      params.overrideField = variableDef != null;
      params.useIndexSetForArrays = typeDeclaration.getLayeredSystem().useIndexSetForArrays;

      int aix = params.propertyTypeName.indexOf("[");
      if (aix != -1) {
         params.arrayDimensions = params.propertyTypeName.substring(aix);
         params.propertyTypeName = params.propertyTypeName.substring(0, aix);
      }

      params.initializer = "";

      int ix = typeDeclaration.body.size();

      makePropertyBindableInType(variableDef, typeDeclaration, propertyName, params, false, true);

      params.omitField = params.overrideField || params.overrideGetSet;

      applyPropertyTemplate(typeDeclaration, ix, params, typeDeclaration, false, null);
   }

   public static AbstractMethodDefinition defineRedirectMethod(TypeDeclaration td, String name, Object meth, boolean isConstructor, boolean needsSuper) {
      AbstractMethodDefinition redirMethod = !isConstructor ? new MethodDefinition() : new ConstructorDefinition();
      AccessLevel al = ModelUtil.getAccessLevel(meth, false);
      if (al != null)
         redirMethod.addModifier(al.levelName);
      redirMethod.name = name;
      if (!isConstructor) {
         String typeName = ModelUtil.getTypeName(ModelUtil.getReturnType(meth, true));
         redirMethod.setProperty("type", ClassType.create(typeName));
      }
      Object[] ptypes = ModelUtil.getParameterTypes(meth);
      String[] pnames = null;
      int numParams;
      if (ptypes != null && ptypes.length > 0) {
         numParams = ptypes.length;
         redirMethod.setProperty("parameters", Parameter.create(td.getLayeredSystem(), ptypes, pnames = ModelUtil.getParameterNames(meth), null, td));
      }
      else
         numParams = 0;
      BlockStatement st = new BlockStatement();
      redirMethod.setProperty("body", st);
      st.setProperty("statements", new SemanticNodeList<Statement>(st, 1));
      if (needsSuper) {
         IdentifierExpression redirStatement = isConstructor ? IdentifierExpression.create("super") : IdentifierExpression.create("super", name);

         SemanticNodeList<Expression> args = new SemanticNodeList<Expression>(numParams);
         for (int i = 0; i < numParams; i++)
            args.add(IdentifierExpression.create(pnames[i]));
         redirStatement.setProperty("arguments", args);
         st.statements.add(redirStatement);
      }
      TransformUtil.appendIndentIfNecessary(td.body);
      td.addBodyStatement(redirMethod);
      return redirMethod;
   }

   public static String evalTemplate(Object paramObj, Template template) {
      PerfMon.start("evalTemplate");
      ExecutionContext ctx = new ExecutionContext();
      ctx.pushCurrentObject(paramObj);
      try {
         return (String) template.eval(String.class, ctx);
      }
      catch (IllegalArgumentException exc) {
         System.err.println("evalTemplate failed for : " + template + " parameters: " + paramObj + " exc: " + exc);
         exc.printStackTrace();
         return null;
      }
      finally {
         PerfMon.end("evalTemplate");
      }
   }

   public static String evalTemplate(Object params, String templateStr, boolean cache) {
      PerfMon.start("evalTemplate");
      Template template = parseTemplate(templateStr, params.getClass(), cache);
      String res = evalTemplate(params, template);
      PerfMon.end("evalTemplate");
      return res;
   }

   public static void addTemplateToTypeBody(BodyTypeDeclaration objType, Object parameters, String initString, String templateName, boolean hidden) {
      SemanticNodeList<Statement> toInsert;
      Object parseRes =  parseCodeTemplate(parameters, initString, SCLanguage.getSCLanguage().blockStatements, true);
      if (parseRes instanceof ParseError) {
         objType.displayError("Unable to parse code snippet from ", templateName, " error:", ((ParseError) parseRes).errorStringWithLineNumbers(initString), " for type: ");
         return;
      }
      toInsert = (SemanticNodeList<Statement>) parseRes;

      BlockStatement bst = new BlockStatement();
      bst.setProperty("statements", toInsert);

      if (hidden)
         objType.addToHiddenBody(bst);
      else
         objType.addBodyStatement(bst);
   }


   public static Object parseCodeTemplate(Object params, String templateStr, Parselet parselet, boolean cache) {
      Template template = parseTemplate(templateStr, params.getClass(), cache);
      return parseCodeTemplate(params, template, parselet);
   }

   public static Object parseCodeTemplate(Object params, Template template, Parselet parselet) {
      String codeToInsert = evalTemplate(params, template);
      PerfMon.start("parseCodeTemplate");
      Object res = parselet.getLanguage().parseString(codeToInsert, parselet);
      if (res instanceof ParseError)
         throw new IllegalArgumentException("Parsing - code evaluated during code generation from template: " + template + " code parsed:\n" + codeToInsert + "\n\nerror: " + ((ParseError) res).errorStringWithLineNumbers(codeToInsert));
      Object value = ParseUtil.nodeToSemanticValue(res);
      PerfMon.end("parseCodeTemplate");
      return value;
   }

   public static void applyTemplateStringToType(TypeDeclaration td, String templateString, String templateStringName, boolean hiddenBody) {
      ObjectDefinitionParameters params = TransformUtil.createObjectDefinitionParameters(td);
      // Need to use 'this' instead of the _varName that we'd use if we put this into the getX method.
      params.noCreationTemplate = true;
      String bodyStatementsString = evalTemplate(params, templateString, true);
      addTemplateToTypeBody(td, params, bodyStatementsString, templateStringName, hiddenBody);
   }

   public static void applyTemplateToType(TypeDeclaration td, String templatePath, String templateTypeName, boolean useNewTemplate) {
      boolean isObject = td.getDeclarationType() == DeclarationType.OBJECT;
      Template template = td.findTemplatePath(templatePath, templateTypeName, ObjectDefinitionParameters.class);
      ObjectDefinitionParameters params = TransformUtil.createObjectDefinitionParameters(td);
      TransformUtil.addObjectDefinition(td, td, params, null, template, isObject && !useNewTemplate, params.typeIsComponent, true, false);
   }

   public static ObjectDefinitionParameters createObjectDefinitionParameters(TypeDeclaration td) {
      StringBuilder locChildNames = new StringBuilder();
      Map<String,StringBuilder> locChildNamesByScope = new HashMap<String,StringBuilder>();

      // The scope template also runs in the context
      LinkedHashSet<String> objNames = new LinkedHashSet<String>();
      int locNumChildren = td.addChildNames(locChildNames, locChildNamesByScope, null, false, false, false, objNames);

      Object compiledClass = td.getClassDeclarationForType();
      String objectClassName = ModelUtil.getTypeName(compiledClass, false, true);
      boolean typeIsComponentClass = !ModelUtil.isObjectType(compiledClass) && ModelUtil.isComponentType(compiledClass);
      String newModifiers = td.modifiersToString(false, true, false, false, false, null);
      boolean isComponent = ModelUtil.isComponentType(td);
      // TODO: childTypeParameters, rootName, parentName not getting filled in here right but I don't think we'll
      // need them for scope definition.
      // TODO: the preAssignment and postAssignment hooks are also not getting filled in.  That does seem like something that might be useful for scopes.
      ObjectDefinitionParameters params = new ObjectDefinitionParameters(compiledClass,
              objectClassName, objectClassName, td, newModifiers,
              locChildNames, locNumChildren, locChildNamesByScope, ModelUtil.convertToCommaSeparatedStrings(objNames), false,
              false, false, isComponent, typeIsComponentClass, null, null, null, null, "", "", td, false, null, null, true, null, null);
      return params;
   }

}
