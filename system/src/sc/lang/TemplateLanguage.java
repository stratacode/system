/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.dyn.INameContext;
import sc.lang.html.Element;
import sc.lang.html.Window;
import sc.lang.java.*;
import sc.lang.template.ITemplateProcessor;
import sc.lang.template.Template;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.layer.SrcEntry;
import sc.parser.*;
import sc.util.FileUtil;
import sc.util.StringUtil;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * The template language companion to SCLanguage.  It is implemented as an extension to StrataCode but with
 * a different starting point.  Like other template languages, you start out in "text mode", then break into Java
 * with the START_CODE_DELIMINTER.  Unlike other template languages, you can break out back out again into text
 * anyplace Java expects statements or expressions (or class body declarations though that's more of an edge case).
 *
 * Lots of advantages for doing it this way, instead of the more traditional "mixing languages" approach.  The syntax
 * is cleaner and less flexible but just as powerful.  It may not be easy to read but the code is easier to maintain
 * and simpler structurally than two interwoven languages.  It has some cool features like you can pass template code
 * as a parameter to a method or initialize a variable with a long string.
 *
 * It will take a bit of getting used to and is not intended for non-programmers.  Purely declarative approaches are
 * the best way to maintain content but they are not powerful enough for general purpose template things that you
 * need to build frameworks.  Additionally any declarative markup should be backed up by a powerful programming language,
 * not the few features they decided to "mix in" with JSP.  Tag libraries and extensions mechanisms get in the way of
 * development and do not enable it.  With StrataCode's TemplateLanguage you write a Java method and just call it passing
 * template values as parameters.  Of course you get the full benefit of being able to modify and override methods etc.
 * like in a regular language.
 *
 * Perhaps the best way for a programmer to understand the template language is to think of there being a new String operator
 * which opens with %> and closes with <% (yes, it looks backwards when you look at it from the StrataCode perspective).
 * The only other change to the language is that we start out by parsing an unescaped string have
 * to break into Java.  There's a special <%! which you can put at the top of the file to control the Class, fields, methods etc.
 * available to the page.
 *
 * The new %> <% operators are more powerful than typical string deliminaters.  You can nest Java statements inside with <% %> or
 * expressions with <%= %>.
 *
 * The template language can either be interpreted and evaluated as is, converted into StrataCode and interpreted, or transformed into a
 * Java file and compiled.  The syntax is the same.  The framework developer controls the default extends class, a "this" object,
 * and sets various parameters which control whether the template uses a Java package name or lives in the web/WEB-INF folder.
 */
public class TemplateLanguage extends SCLanguage implements IParserConstants {
   public final static String START_CODE_DELIMITER = "<%";
   public final static String END_DELIMITER = "%>";

   public final static String START_EXP_DELIMITER = "<%=";

   public final static String START_DECL_DELIMITER = "<%!";

   public final static String START_IMPORT_DELIMITER = "<%@";

   public final static String START_HTML_COMMENT = "<!--";

   public final static String END_HTML_COMMENT = "-->";

   public final static TemplateLanguage INSTANCE = new TemplateLanguage();

   // Start must chew up space following the delimiter since it is the start of a code construct.
   public SymbolSpace startCodeDelimiter = new SymbolSpace(START_CODE_DELIMITER);
   {
      // This constraints the symbol match so it will not inaccurately match these more specific tokens.
      // We don't require this for the grammar but it's helpful for reparsing - so we never match a code segment because it has
      // better error consumption than the declaration statement
      startCodeDelimiter.addExcludedValues("<%@", "<%!", "<%=");
   }
   public SymbolSpace startExpDelimiter = new KeywordSymbolSpace(START_EXP_DELIMITER);
   public SymbolSpace startDeclDelimiter = new KeywordSymbolSpace(START_DECL_DELIMITER);
   public SymbolSpace startImportDelimiter = new KeywordSymbolSpace(START_IMPORT_DELIMITER);

   // End does not consume space since that space should be part of the template string
   public Symbol endDelimiter = new KeywordSymbol(END_DELIMITER);

   // Yes, start/end are swapped in these next two.  It's nice to have separate tokens for brace matching purposes in the lexer, or maybe separate styles?
   public Symbol startGlueDelimiter = new KeywordSymbol(END_DELIMITER);
   public Symbol endGlueDelimiter = new KeywordSymbol(START_CODE_DELIMITER);

   // Creating these so we have different tokens to match up against the start token - not really needed here but the lexer/highlighting in intelliJ requires separate tokens
   public Symbol endExpDelimiter = new KeywordSymbol(END_DELIMITER);
   public Symbol endImportDelimiter = new KeywordSymbol(END_DELIMITER);
   public Symbol endDeclDelimiter = new KeywordSymbol(END_DELIMITER);

   OrderedChoice htmlCommentBody = new OrderedChoice("('','','')", REPEAT | OPTIONAL,
                   new Sequence("('',)", new Symbol("--"), new Symbol(NOT | LOOKAHEAD, ">")),
                   new Sequence("('',)", new Symbol("-"), new Symbol(NOT | LOOKAHEAD, "-")),
                   new Sequence("('',)", new Symbol(NOT, "-"), new Symbol(LOOKAHEAD, Symbol.ANYCHAR)));
   public Sequence tagComment = new Sequence("HTMLComment(,commentBody,)", new Symbol(START_HTML_COMMENT), htmlCommentBody, new Symbol(END_HTML_COMMENT));
   public SymbolChoice templateString = new SymbolChoice(NOT | REPEAT, START_HTML_COMMENT, END_HTML_COMMENT, START_EXP_DELIMITER, START_CODE_DELIMITER, END_DELIMITER, EOF);
   {
      templateString.styleName = "templateString";
   }
   Sequence templateExpression = new Sequence("(,.,)", startExpDelimiter, expression, endExpDelimiter);
   OrderedChoice templateBlockStatements = (OrderedChoice) blockStatements.clone();
   // For Java uses of blockStatement, we also match glueStatement (added below to statement because we need that in all normal Java uses of statement).  But for templateStatement
   // we do not want to match the glue (or it will sometimes match the template statement's end token).  Making a copy of statement before glue is added below and using the copy
   // for the TemplateStatement's blockStatements copy.
   IndexedChoice noGlueStatement = (IndexedChoice) statement.clone();
   {
      noGlueStatement.changeParseletName("noGlueStatement");
      templateBlockStatements.set(2, noGlueStatement);
   }
   Sequence templateStatement = new Sequence("TemplateStatement(,statements,)", startCodeDelimiter, templateBlockStatements, endDelimiter);
   Sequence templateDeclaration = new Sequence("TemplateDeclaration(,body,)", startDeclDelimiter, classBodyDeclarations, endDeclDelimiter);
   public OrderedChoice simpleTemplateDeclarations = new OrderedChoice("([],[])", OPTIONAL | REPEAT, templateExpression, templateString);
   Sequence glueExpression = new Sequence("GlueExpression(,expressions,)", endDelimiter, simpleTemplateDeclarations, startCodeDelimiter);
   Sequence glueStatement = new Sequence("GlueStatement(,declarations,)", endDelimiter, simpleTemplateDeclarations, startCodeDelimiter);
   {
      // We don't want to match %>content in the partialValues case for glue expressions.  They need to have the <% as well
      glueExpression.minContentSlot = 2;
      glueStatement.minContentSlot = 2;
   }
   // In terms of order here - need templateDeclaration <%! ahead of templateStatement <% - otherwise risk matching <% !foo := bar %> before we match <%! foo := bar %>
   public IndexedChoice templateBodyDeclarations = new IndexedChoice("([],[],[],[],[])", OPTIONAL | REPEAT);
   {
      templateBodyDeclarations.put(START_HTML_COMMENT, tagComment);
      templateBodyDeclarations.put(START_EXP_DELIMITER, templateExpression);
      templateBodyDeclarations.put(START_DECL_DELIMITER, templateDeclaration);
      templateBodyDeclarations.put(START_CODE_DELIMITER, templateStatement);
      templateBodyDeclarations.addDefault(templateString);
      templateBodyDeclarations.skipOnErrorParselet = skipTypeDeclError;
   }
   Sequence glueDeclaration = new Sequence("GlueDeclaration(,declarations,)", endDelimiter, templateBodyDeclarations, startCodeDelimiter);
   {
      // Do not parse %>bodyText by itself.   We need to match a the open <% as well in partial values mode or else we consume the %>bodyText as part of 'classBodyDeclarations' so it's not there for the close template
      glueDeclaration.minContentSlot = 2;
   }
   Sequence templateAnnotations = new Sequence("(,imports, templateModifiers,)", OPTIONAL, startImportDelimiter, imports, modifiers, endImportDelimiter);
   Sequence template = new Sequence("Template(, *, templateDeclarations,)", spacing, templateAnnotations, templateBodyDeclarations, new Symbol(EOF));
   {
      // Disable partial values on exprStatement because of problems matching glueExpression in the template language in partial values mode.   In general though it seems like we may not want
      // to match an expression by itself since it might be a part of something larger?  We're doing this for now in TemplateLanguage.  To really fix the bug, ideally glueExpression would not be
      // the expression used by templateStatement since the end of the templatestatement gets missparsed as the start of the glue.
      exprStatement.skipOnErrorSlot = 2;

      // Add this to the regular Java grammar.  It recognizes the %> followed by text or <%= %> statements
      statement.put(END_DELIMITER, glueStatement);
      statement.setName("<statement>(.,.,.,.,.,.,.,.,.,.,.,.,.,,.,.,.)"); // Forward

      classBodyDeclarations.setName("<classBodyDeclarations>([],[],,[],[],[],[])");
      classBodyDeclarations.add(glueDeclaration);

      primary.put(END_DELIMITER, glueExpression);

      binaryOperators.addExcludedValues("%>", "</");

      // During transform of a compiled template, we'll transform the JavaModel back through compilationUnit.  In this case, the types have to match exactly so we need to redefine the grammar by just replacing the type name: JavaModel -> Template
      compilationUnit.setResultClassName("Template");
   }

   public TemplateLanguage(boolean compiled) {
      this(null);
      compiledTemplate = compiled;
   }

   public TemplateLanguage() {
      this(null);
   }

   public TemplateLanguage(Layer layer) {
      super(layer);
      setStartParselet(template);
      addToSemanticValueClassPath("sc.lang.template");
      languageName = "SCTemplate";
      defaultExtension = "sctp";
   }

   public static TemplateLanguage getTemplateLanguage() {
      return INSTANCE;
   }

   /** Suffix to use for the results when compiledTemplate = false */
   public String resultSuffix;

   /** Filter command to run for evalled templates */
   public List<String> filterCommand;

   /** Store results here instead of buildDir */
   public String outputDir;

   /** Use buildSrcDir instead of buildDir */
   public boolean useSrcDir;

   /** The Template should implement toString by evaluating itself.  Useful for cases where there are no parameters needed to evaluate and you want something like <%= templateName %> to work.  */
   public boolean evalToString = false;

   /**
    * Two types of templates: those evaluated during the build process to generate the source and
    * those which are compiled into Java files and compiled into the system as objects.
    */
   public boolean compiledTemplate = false;

   /** Set this to true for templates types which need to run after the system has been built */
   public boolean postBuildTemplate = false;

   /** Use this as the default extends type for any template without an explicit type definition */
   public String defaultExtendsType;

   /** If a template file does not have an explicit <%! TypeName %> statement it will either replace or modify the previous type based on this setting.  For now, static file processors will simply replace the previous type for simplicity.  But schtml and other more sophisticated template types probably should make the default be to modify...*/
   public boolean defaultModify;

   /** Should templates define classes or objects by default */
   public boolean isDefaultObjectType = true;

   /** Add templates of this type to a specific type group - i.e. servlets, so you can add mappings for these types to some config file */
   public String typeGroupName;

   /** An optional prefix to prepend onto paths of template files in the build or buildSrc directory */
   public String templatePrefix;

   /** An optional prefix to prepend onto just the files produced during template processing (i.e. the html file, not the .java file) */
   public String processPrefix;

   /** Should templates use static invocation or create an instance of the template type for dynamic mode.  For compiled mode, this will be determined by the code generated by the compiled class */
   public boolean staticTemplate = false;

   /** Should we treat this file format as something we run through the template process - i.e. evaluate the template and store it in a file using the result suffix */
   public boolean processTemplate = false;

   /** Is this a template that's only evaluated explicitly by Java code (usually to generate a snippet of code) - e.g. the objectTemplate in CompilerSettings */
   public boolean runtimeTemplate = false;

   /** The HTML language disables processing of types which do not have URL or MainInit */
   public boolean processOnlyURLs = false;

   /** Should the different suffixes override in each other from one layer to the next.  e.g. with sc and schtml it is false and .properties and .xml it is true */
   public boolean processByUniqueSuffix = true;

   /** Only used when compiledTemplate is true and you need to prepend the suffix on the .java file and do not on the generated html file */
   public boolean prependLayerPackageOnProcess = false;

   /** If the template has only one element and no content, when this is true, the rootType for the template is the type generated by that element.  Eliminates the annoying "html" sub-object and supports 'extends' and 'implements' templates */
   public boolean compressSingleElementTemplates = false;

   /** When true, make the resulting file executable (i.e. for scsh) */
   public boolean makeExecutable = false;

   /** Set this to true to force the generation of an output method */
   public boolean needsOutputMethod = false;

   public boolean needsJavascript = true;

   /**
    * When true, the template engine will generate stateful template descriptions - which can detect changes and update incrementally using data binding.  For you to have a stateful page you need to set the default extends type to a type which implements the
    * invalidate methods appropriate for your tmeplate language (e.g. invalidate() for genertic templates and invalidateBody/Start for HTML variants
    */
   public boolean statefulPages = true;

   /** Use configuration in the template language to configure properties in the result */
   public void postProcessSemanticValue(Object semValue, String fileName) {
      if (semValue instanceof Template) {
         Template temp = (Template) semValue;
         if (processTemplate) {
            if (!staticTemplate)
               temp.createInstance = true;
            if (!compiledTemplate)
               temp.dynamicType = true;
            // Some interpreted templates, such as web.scxml depend on the output method template etc.
            temp.generateOutputMethod = true;
            if (!prependLayerPackage)
               temp.prependLayerPackage = false;
         }

         if (postBuildTemplate)
            temp.generateOutputMethod = true;

         if (needsOutputMethod)
            temp.generateOutputMethod = true;

         // We need some type of base-type whih implements the invalidate method to be a stateful page.
         Object defaultExtendsClass;
         if (defaultExtendsType == null || statefulPages == false)
            temp.statefulPage = false;

         // Lots of properties are propagated through the template processor from the template so we need to set it even if not processing the template
         temp.templateProcessor = createResultProcessor(temp, fileName);
      }
   }

   /** The ResultProcessor is a customization point for TemplateLanguages.  This hook lets you override the TemplateResultProcessor hook point in a sub-language of TemplateLanguage. */
   public TemplateResultProcessor createResultProcessor(Template template, String fileName) {
      return new TemplateResultProcessor(template, fileName);
   }

   public class TemplateResultProcessor implements ITemplateProcessor, INameContext {
      String file, result;
      Template template;

      public TemplateResultProcessor(Template temp, String f) {
         template = temp;
         file = f;
      }

      public List<SrcEntry> getDependentFiles() {
         return null;
      }

      public boolean hasErrors() {
         return false;
      }

      public boolean needsCompile() {
         return compiledTemplate;
      }

      @Override
      public boolean isRuntimeTemplate() {
         return runtimeTemplate;
      }

      /**
       * This is called during the build process, when we need the files generated by this template.  If we are generating a .java file we return that.  If we are returning a generated file from the template
       * we return that.
       */
      public List<SrcEntry> getProcessedFiles(Layer buildLayer, String buildSrcDir, boolean generate) {
         String result = null;
         SrcEntry src = template.getSrcFiles().get(0);
         SrcEntry processedSrcEnt = null;

         // Evaluate the template at compile time to store the "static" content - unless the template itself is a compiled type and we cannot create that class
         if (processTemplate && template.isConcreteTemplate()) {
            try {
               // If we are both processing and compiling need to make a copy here
               Template templateCopy = !compiledTemplate ? template : (Template) template.deepCopy(ISemanticNode.CopyNormal, null);
               if (compiledTemplate) {
                  templateCopy.templateProcessor = template.templateProcessor;
                  templateCopy.generateOutputMethod = true;
                  templateCopy.types.clear();
                  // Temporarily override the exec mode for the runtime.  When processing the templates on the server, we are only taking the server elements.
                  templateCopy.execMode = Element.ExecProcess;
               }

               ParseUtil.initAndStartComponent(templateCopy);
               if (!templateCopy.hasErrors()) {
                  templateCopy.transformTemplate(0, true);
                  Object rootTypeObj = templateCopy.rootType;
                  // Even though the template may be a compiled type, since we are evaluating it and creating the instance we need to allow it to be dynamic.  The "canProcess" method above should ensure it's a type that we can create dynamically.
                  if (rootTypeObj instanceof BodyTypeDeclaration) {
                     ((BodyTypeDeclaration) rootTypeObj).setDynamicType(true);
                  }
                  ExecutionContext ctx = new ExecutionContext();
                  ctx.resolver = this;
                  if (!staticTemplate) {
                     templateCopy.createInstance = true;
                  }
                  result = (String) templateCopy.eval(String.class, ctx);
                  if (filterCommand != null)
                     result = FileUtil.execCommand(filterCommand, result);
               }
            }
            catch (Exception exc) {
               System.err.println("Processing template: " + exc);
               exc.printStackTrace();
            }

            String pathTypeName = src.layer.getSrcPathTypeName(src.absFileName, true);
            String prefixToUse = templatePrefix == null ? buildLayer.getSrcPathBuildPrefix(pathTypeName) : templatePrefix;

            String relFileName = src.relFileName;

            if (pathTypeName != null && src.layer != null) {
               String remPrefix = src.layer.getSrcPathBuildPrefix(pathTypeName);
               if (remPrefix != null && relFileName != null && relFileName.startsWith(remPrefix))
                  relFileName = relFileName.substring(remPrefix.length() + 1);
            }

            // When both compiling and generating the template, use the alternate flag
            boolean prependPackage = compiledTemplate ? prependLayerPackageOnProcess : prependLayerPackage;
            prefixToUse = processPrefix != null ? processPrefix : prefixToUse;
            String prefix = FileUtil.concat(prefixToUse, prependPackage ? template.getPackagePrefixDir() : null);
            String newFile = FileUtil.concat(
                    outputDir == null ? (useSrcDir ? buildSrcDir : buildLayer.buildDir) :  outputDir,
                    FileUtil.concat(prefix, relFileName));
            newFile = FileUtil.replaceExtension(newFile, resultSuffix);

            // The layered system processes hidden layer files backwards.  So generate will be true the for the
            // final layer's objects but an overriden component comes in afterwards... don't overwrite the new file
            // with the previous one.  We really don't need to transform this but I think it is moot because it will
            // have been transformed anyway.
            if (generate && result != null) {
               FileUtil.saveStringAsFile(newFile, result, true);
            }
            if (makeExecutable)
               new File(newFile).setExecutable(true, true);
            SrcEntry srcEnt = new SrcEntry(src.layer, newFile, FileUtil.replaceExtension(src.relFileName, resultSuffix));

            if (result != null)
               srcEnt.hash = StringUtil.computeHash(result);
            else
               srcEnt.hash = StringUtil.computeHash(new byte[0]);

            if (!compiledTemplate)
               return Collections.singletonList(srcEnt);
            else
               processedSrcEnt = srcEnt;
         }
         // Compiled the template into a .java class for execution
         if (compiledTemplate) {
            // Not using the useSrcDir property here since that is used for the html file.  Here we do want to put the java file in the buildSrcDirectory always.
            String outDir = outputDir == null ? buildSrcDir :  outputDir;
            List<SrcEntry> res = template.getCompiledProcessedFiles(buildLayer, outDir, generate);
            //if (processedSrcEnt == null)
               return res;
            /*
            else {
               ArrayList<SrcEntry> newRes = new ArrayList<SrcEntry>(res);
               newRes.add(processedSrcEnt);
               res = newRes;
            }

            return res;
            */
         }
         return template.getProcessedFiles(buildLayer, buildSrcDir, generate);
      }

      public void postBuild(Layer buildLayer, String buildSrcDir) {
         if (!postBuildTemplate)
            System.err.println("*** postBuild called on template where it's not enabled");

         String result = null;
         SrcEntry src = template.getSrcFiles().get(0);
         // Evaluate the template at compile time to store the "static" content - unless the template itself is a compiled type and we cannot create that class
         try {
            ParseUtil.initAndStartComponent(template);
            template.execMode = Element.ExecServer;
            if (!template.hasErrors()) {
               Object rootTypeObj = template.rootType;
               ExecutionContext ctx = new ExecutionContext();
               ctx.resolver = this;
               if (!staticTemplate) {
                  template.createInstance = true;
               }
               LayeredSystem sys = buildLayer.layeredSystem;
               // Do not pre-build session scope templates.  They may depend on the session so generating the .html file just messes things up.
               if (rootTypeObj != null) {
                  if (!ModelUtil.isGlobalScope(sys, rootTypeObj)) {
                     if (sys.options.verbose)
                        System.out.println("No file for template: " + src.relFileName + ". type: " + ModelUtil.getTypeName(rootTypeObj) + ": not global scope");
                     return;
                  }
                  else {
                     // Also skip fragment pages.  Not sure these are exactly the right tests but on the server there will be a URL
                     boolean hasURL = false;
                     boolean hasMainInit = false;
                     if (processOnlyURLs && (hasURL = ModelUtil.getInheritedAnnotation(sys, rootTypeObj, "sc.html.URL", false, null, false) == null) && (hasMainInit = ModelUtil.getInheritedAnnotation(buildLayer.layeredSystem, rootTypeObj, "sc.html.MainInit", false, null, false) == null)) {
                        if (buildLayer.layeredSystem.options.verbose)
                           System.out.println("No file for template: " + src.relFileName + " type: " + ModelUtil.getTypeName(rootTypeObj) + ": no @URL or @MainInit");
                        return;
                     }

                     if (buildLayer.layeredSystem.options.verbose)
                        System.out.println("File for template: " + src.relFileName + " type: " + ModelUtil.getTypeName(rootTypeObj) + " - global " + (hasURL ? "@URL" : (hasMainInit ? "with: @MainInit" : "file type")));
                  }

                  //ScopeDefinition scope = ScopeDefinition.getScopeByName(scopeName);
                  //if (scope != null && !scope.isGlobal())
                  //   return;
                  // TODO: scopes will not be defined here because we have not run the initOnStartup code yet.
                  // Probably this whole process should be code-generated and run during server startup as part of
                  // the initOnStartup code?  But then web.xml would not be generated soon enough?
               }

               String pathInfo = src.relFileName; // TODO - should this use the pathName in the URL annotation above?  What if it has parameters?
               if (resultSuffix != null)
                  pathInfo = FileUtil.replaceExtension(pathInfo, resultSuffix);

               // This lets templates find their URL using the Window.location object
               // TODO: maybe this should be moved into a hook added by the HTML language?  I don't see it hurting anything in other frameworks but it really belongs in an HTML specific class.
               Window.setWindow(Window.createNewWindow("http://" + sys.serverName + (sys.serverPort == 80 ? "" : (":" + sys.serverPort)) + "/" + pathInfo,
                                                       sys.serverName, sys.serverPort, pathInfo, pathInfo));

               result = (String) template.eval(String.class, ctx);
               if (filterCommand != null)
                  result = FileUtil.execCommand(filterCommand, result);
            }
         }
         catch (Exception exc) {
            System.err.println("Processing template: " + exc);
            exc.printStackTrace();
         }

         // When both compiling and generating the template, use the alternate flag
         boolean prependPackage = getPrependPackageOnFile();
         String prefixToUse = processPrefix != null ? processPrefix : templatePrefix;
         String prefix = FileUtil.concat(prefixToUse, prependPackage ? template.getPackagePrefixDir() : null);
         String newFile = FileUtil.concat(
                 outputDir == null ? (useSrcDir ? buildSrcDir : src.layer.getLayeredSystem().buildDir) :  outputDir,
                 FileUtil.concat(prefix, src.relFileName));
         newFile = FileUtil.replaceExtension(newFile, resultSuffix);

         if (result != null) {
            FileUtil.saveStringAsFile(newFile, result, true);
         }
      }

      public boolean getPrependPackageOnFile() {
         return compiledTemplate ? prependLayerPackageOnProcess : prependLayerPackage;
      }

      private String getFileName(boolean prepend, boolean fileSuffix) {
         SrcEntry src = template.getSrcFiles().get(0);
         String uniqueSuffix = fileSuffix ? resultSuffix : getUniqueSuffix();
         if (uniqueSuffix == null)
            uniqueSuffix = "";
         else
            uniqueSuffix = "." + uniqueSuffix;
         if (prepend)
            return FileUtil.concat(templatePrefix, src.getTypeName() + uniqueSuffix);
         else
            return FileUtil.concat(templatePrefix, src.getRelTypeName() + uniqueSuffix);
      }

      public String getProcessedFileName() {
         return getFileName(prependLayerPackage, false);
      }

      public String getPostBuildFileName() {
         // Always use the result suffix - e.g. .html, not .java for the post-build phase.  That's so we can override an schtml file with an .html file.
         return getFileName(getPrependPackageOnFile(), true);
      }

      public String getUniqueSuffix() {
         // Need to use .java for the unique suffix when this template is compiled and processed.  In that case, we use the resultSuffix for the template processing suffix.
         return processByUniqueSuffix ? (compiledTemplate && (processTemplate || postBuildTemplate) ? "java" : resultSuffix) : null;
      }

      public String getDefaultExtendsType() {
         return defaultExtendsType;
      }

      public boolean getIsDefaultObjectType() {
         return isDefaultObjectType;
      }

      public String getTypeGroupName() {
         return typeGroupName;
      }

      public boolean getDefaultModify() {
         return defaultModify;
      }

      public boolean getCompressSingleElementTemplates() {
         return compressSingleElementTemplates;
      }

      public boolean needsProcessing() {
         return compiledTemplate || processTemplate;
      }

      public boolean needsPostBuild() {
         return postBuildTemplate;
      }

      public ITemplateProcessor deepCopy(Template templ) {
         return new TemplateResultProcessor(templ, file);
      }

      /** By default, template languages do not escape the body.  The HTML language does though */
      public String escapeBodyMethod() {
         return TemplateLanguage.this.escapeBodyMethod();
      }

      public String getResultSuffix() {
         return resultSuffix;
      }

      public boolean evalToString() {
         return TemplateLanguage.this.evalToString;
      }

      public Object resolveName(String name, boolean create) {
         Object t = template.findTypeDeclaration(name, false);
         if (t instanceof ClassDeclaration) {
            JavaModel model = ((ClassDeclaration) t).getJavaModel();
            if (model instanceof Template && staticTemplate) {
               return model;
            }
         }
         return template.resolveName(name, create);
      }
   }

   public boolean getNeedsCompile() {
      return false;
   }

   public String getOutputDir() {
      return outputDir;
   }

   public String escapeBodyMethod() {
      return null;
   }
}
