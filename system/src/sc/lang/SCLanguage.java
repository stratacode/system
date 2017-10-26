/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.lang.java.JavaModel;
import sc.layer.Layer;
import sc.obj.Constant;
import sc.util.ExtensionFilenameFilter;
import sc.parser.*;
import sc.util.FileUtil;

import java.io.File;
import java.io.FilenameFilter;
import java.io.StringReader;
import java.util.*;

/**
 * This class defines the grammar modifications to Java for the V language.  Essentially:
 *    - adds "object" as a class definition operator for defining instances
 *    - support Component { for modifying an existing class.
 *    - add a propertyAssignment option to the class body
 */
public class SCLanguage extends JavaLanguage {

   public static final String DEFAULT_EXTENSION = "sc";

   static Set<IString> SC_KEYWORD_SET = new HashSet<IString>(Arrays.asList(PString.toPString(JAVA_KEYWORDS)));
   static Set<IString> SC_VARNAME_KEYWORD_SET = new HashSet<IString>(JAVA_VARNAME_KEYWORD_SET);

   static {
      SC_KEYWORD_SET.add(PString.toPString("object"));
      SC_KEYWORD_SET.add(PString.toPString("override"));
      SC_KEYWORD_SET.add(PString.toPString("scope"));
      SC_VARNAME_KEYWORD_SET.add(PString.toPString("object"));
      SC_VARNAME_KEYWORD_SET.add(PString.toPString("override"));
      SC_VARNAME_KEYWORD_SET.add(PString.toPString("scope"));
   }

   protected static final List<String> SC_CLASS_LEVEL_KEYWORDS = new ArrayList<String>(Arrays.asList("object", "override", "scope"));
   static {
      SC_CLASS_LEVEL_KEYWORDS.addAll(JavaLanguage.CLASS_LEVEL_KEYWORDS);
   }

   public Set getKeywords() {
      return SC_KEYWORD_SET;
   }

   public Set getVarNameKeywords() {
      return SC_VARNAME_KEYWORD_SET;
   }

   KeywordSpace scopeKeyword = new KeywordSpace("scope");
   public Sequence scopeModifier = new Sequence("ScopeModifier(,,scopeName,)", scopeKeyword, lessThan, identifier, greaterThan);

   Sequence modifyDeclarationWithoutModifiers = new Sequence("ModifyDeclaration(typeName,typeParameters,extendsTypes,implementsTypes,,body,)",
            qualifiedIdentifier, optTypeParameters, extendsTypes, implementsTypes, openBraceEOL, classBodyDeclarations, closeBraceEOL);
   {
      // This prevents just "typeName" from parsing - we should need at least an open-brace after that.
      modifyDeclarationWithoutModifiers.minContentSlot = 4;
   }

   Sequence modifyDeclaration = new Sequence("(modifiers,.)", modifiers, modifyDeclarationWithoutModifiers);

   {
      assignmentOperator.add(":=:");
      assignmentOperator.add("=:");
      assignmentOperator.add(":=");

      variableInitializerOperators.add(":=:");
      variableInitializerOperators.add("=:");
      variableInitializerOperators.add(":=");

      modifiers.add(scopeModifier);
      modifiers.setName("<modifiers>([],[],[])");

      classModifiers.add(scopeModifier);
      classModifiers.setName("<classModifiers>([],[],[])");

      // In StrataCode, the package name is optional in the package definition, so you can override a package with no package in a subsequent layer in the layer definition file.
      packageDeclaration.set(annotations, new KeywordSpace("package"), optQualifiedIdentifier, semicolonEOL);
   }

   Sequence propertyAssignment = new Sequence("PropertyAssignment(propertyName,operator,initializer,)", identifierSp, assignmentOperator, variableInitializer, semicolonEOL);

   // This definition lets you override a field/property to attach annotations, change modifiers etc.
   Sequence overrideProperty = new Sequence("OverrideAssignment(, modifiers, propertyName,*,)", new SymbolSpace("override"), modifiers, identifierSp,
                                             new Sequence("(operator,initializer)", OPTIONAL, assignmentOperator, variableInitializer), semicolonEOL);

   // This definition lets you override a method's definition to attach annotations, and change modifiers on it without redefining the method.
   Sequence overrideMethod = new Sequence("MethodDefinition(override, modifiers,name,parameters,)", new KeywordSpace("override"), modifiers, identifierSp,
                                          formalParameters, semicolonEOL);

   Sequence mapEntry = new Sequence("MapEntry(key,,value)", expression, colon, expression);

   public OrderedChoice arrayVariableInitializer = new OrderedChoice("<arrayVariableInitializer>", variableInitializer, mapEntry);

   {
      // Redefine Java's type declaration parameter mapping and children to add the "modifyDeclaration".  Just like Java use semicolon not semicolonEOL so we do not get skipOnError
      typeDeclaration.set("(.,.,.,)", classDeclaration, interfaceDeclaration, modifyDeclaration, semicolon);
      typeDeclarations.set("([],[],[],)", classDeclaration, interfaceDeclaration, modifyDeclaration, semicolon);

      // The operator is now a valid object.  We are overriding the class declaration for now even though objects don't
      // support implements
      classOperators.add("object");
      modifierKeywords.add("dynamic");
      classModifierKeywords.add("dynamic");

      // Add this to the index to the indexed choice for the new "object" keyword
      memberDeclaration.put("object", classDeclarationWithoutModifiers);
      // modify is a new default choice since there is no prefix to uniquely identify it
      memberDeclaration.addDefault(modifyDeclarationWithoutModifiers);

      classBodyDeclarations.setName("<classBodyDeclarations>([],[],,[],[],[])");
      // Method should be ahead of property here for partial results to work properly since a property is a subset of a method when you take away the ;
      classBodyDeclarations.add(overrideMethod, overrideProperty, propertyAssignment);

      // Overrides the result class name used for this grammar.
      compilationUnit.setResultClassName("SCModel");
      languageModel.setResultClassName("SCModel");

      arrayInitializer.set(
              openBrace,
              new Sequence("([],[],)", OPTIONAL, arrayVariableInitializer,
                      new Sequence("(,[])", REPEAT | OPTIONAL, comma, arrayVariableInitializer),
                      new Sequence(OPTIONAL, comma)),
              closeBrace);
   }

   @Constant
   public static SCLanguage INSTANCE = new SCLanguage();

   public static SCLanguage getSCLanguage() {
      return INSTANCE;
   }

   public SCLanguage() {
      this(null);
   }

   public SCLanguage(Layer layer) {
      super(layer);
      addToSemanticValueClassPath("sc.lang.sc");
      languageName = "StrataCode";
      defaultExtension = DEFAULT_EXTENSION;
   }

   public static void main(String[] args)
   {
      SCLanguage c = (SCLanguage) Language.getLanguageByExtension(DEFAULT_EXTENSION);
      c.debug = false;

      parseFiles(c, inputFileNames, false);
   }

   public static final String STRATACODE_SUFFIX = "sc";
   
   static final FilenameFilter STRATACODE_FILTER = new ExtensionFilenameFilter("." + STRATACODE_SUFFIX, true);

   static String [] inputFileNames = { "test1.sc" };

   public static void parseFiles(SCLanguage c, Object[] inputFiles, boolean finalGenerate)
   {
      for (Object fileObj : inputFiles)
      {
         String fileName;
         File file;
         if (fileObj instanceof File)
         {
            file = (File) fileObj;
            fileName = file.toString();
         }
         else
         {
            fileName = (String) fileObj;
            file = new File(fileName);
         }
         if (file.isDirectory())
         {
            File[] files = file.listFiles(STRATACODE_FILTER);
            if (files == null)
               System.err.println("**** Unable to read directory: " + file);
            else
               parseFiles(c, files, finalGenerate);
            continue;
         }
         String input = ParseUtil.readFileString(file);
         if (input == null)
         {
            System.out.println("*** Can't open file: " + fileName);
            continue;
         }
         Object result = c.parse(fileName, new StringReader(input));
         if (result == null || !input.equals(result.toString()))
         {
            if (result instanceof ParseError)
               System.out.println("File: " + fileName + ": " + ((ParseError) result).errorStringWithLineNumbers(file));
            else
            {
               System.out.println("*** INPUTS DO NOT MATCH for file: " + fileName);
               System.out.println(input + " => " + result);
            }
         }
         else
         {
            IParseNode node = (IParseNode) result;
            System.out.println("inputs match for: " + fileName);

            System.out.println();
            //if (result instanceof IParseNode)
            //   System.out.println(((IParseNode)result).toDebugString());

            //System.out.println("Model:" + node.getSemanticValue());

            JavaModel m = (JavaModel) ParseUtil.getParseResult(node);
            System.out.println("Parsed model: " + m.toString());

            /*
            ModelUtil.makeClassesBindable(m);

            System.out.println("External references: " + m.getExternalReferences());
            */

            //System.out.println("Bindable result: " + result);

            // Clear out the old parse-tree
            node.setSemanticValue(null, true);
            System.out.println();

            // For debugging the generation rpocess only
            c.debug = true;

            // Generate a completely new one
            String genFileName = FileUtil.replaceExtension(fileName, "gen");
            String genResult = c.generate(m, finalGenerate).toString();
            FileUtil.saveStringAsFile(genFileName, genResult, true);

            System.out.println("*** processed: " + fileName);
         }
      }
   }

   public List<String> getClassLevelKeywords() {
      return SC_CLASS_LEVEL_KEYWORDS;
   }
}
