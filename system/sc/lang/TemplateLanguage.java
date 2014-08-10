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
import java.io.Reader;
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
   SymbolSpace startCodeDelimiter = new SymbolSpace(START_CODE_DELIMITER);
   SymbolSpace startExpDelimiter = new SymbolSpace(START_EXP_DELIMITER);

   SymbolSpace startDeclDelimiter = new SymbolSpace(START_DECL_DELIMITER);

   // End does not consume space since that space should be part of the template string
   Symbol endDelimiter = new Symbol(END_DELIMITER);

   OrderedChoice htmlCommentBody = new OrderedChoice("('','','')", REPEAT | OPTIONAL,
                   new Sequence("('',)", new Symbol("--"), new Symbol(NOT | LOOKAHEAD, ">")),
                   new Sequence("('',)", new Symbol("-"), new Symbol(NOT | LOOKAHEAD, "-")),
                   new Sequence("('',)", new Symbol(NOT, "-"), new Symbol(LOOKAHEAD, Symbol.ANYCHAR)));
   Sequence htmlComment = new Sequence("HTMLComment(,commentBody,)", new Symbol(START_HTML_COMMENT), htmlCommentBody, new Symbol(END_HTML_COMMENT));
   SymbolChoice templateString = new SymbolChoice(NOT | REPEAT, START_HTML_COMMENT, END_HTML_COMMENT, START_EXP_DELIMITER, START_CODE_DELIMITER, END_DELIMITER, EOF);
   {
      templateString.styleName = "templateString";
   }
   Sequence templateExpression = new Sequence("(,.,)", startExpDelimiter, expression, endDelimiter);
   Sequence templateStatement = new Sequence("TemplateStatement(,statements,)", startCodeDelimiter,
                                             blockStatements, endDelimiter);
   Sequence templateDeclaration = new Sequence("TemplateDeclaration(,body,)", startDeclDelimiter, classBodyDeclarations, endDelimiter);
   public OrderedChoice simpleTemplateDeclarations = new OrderedChoice("([],[])", OPTIONAL | REPEAT, templateExpression, templateString);
   Sequence glueExpression = new Sequence("GlueExpression(,expressions,)", endDelimiter, simpleTemplateDeclarations, startCodeDelimiter);
   Sequence glueStatement = new Sequence("GlueStatement(,declarations,)", endDelimiter, simpleTemplateDeclarations, startCodeDelimiter);
   // In terms of order here - need templateDeclaration <%! ahead of templateStatement <% - otherwise risk matching <% !foo := bar %> before we match <%! foo := bar %>
   public IndexedChoice templateBodyDeclarations = new IndexedChoice("([],[],[],[],[])", OPTIONAL | REPEAT);
   {
      templateBodyDeclarations.put(START_HTML_COMMENT, htmlComment);
      templateBodyDeclarations.put(START_EXP_DELIMITER, templateExpression);
      templateBodyDeclarations.put(START_DECL_DELIMITER, templateDeclaration);
      templateBodyDeclarations.put(START_CODE_DELIMITER, templateStatement);
      templateBodyDeclarations.addDefault(templateString);
   }
   Sequence glueDeclaration = new Sequence("GlueDeclaration(,declarations,)", endDelimiter, templateBodyDeclarations, startCodeDelimiter);
   Sequence templateAnnotations = new Sequence("(,,imports, templateModifiers,)", OPTIONAL, new Symbol(START_IMPORT_DELIMITER), spacing, imports, modifiers, endDelimiter);
   Sequence template = new Sequence("Template(, *, templateDeclarations,)", spacing, templateAnnotations, templateBodyDeclarations, new Symbol(EOF));
   {
      // Add this to the regular Java grammar.  It recognizes the %> followed by text or <%= %> statements
      statement.put(END_DELIMITER, glueStatement);
      statement.setName("<statement>(.,.,.,.,.,.,.,.,.,.,.,.,.,,.,.,.)"); // Forward

      classBodyDeclarations.setName("<classBodyDeclarations>([],[],,[],[],[],[])");
      classBodyDeclarations.add(glueDeclaration);

      primary.put(END_DELIMITER, glueExpression);

      // During transform of a compiled template, we'll transform the JavaModel back through compilationUnit.  In this case, the types have to match exactly so we need to redefine the grammar by just replacing the type name: JavaModel -> Template
      compilationUnit.setResultClassName("Template");
   }

   public TemplateLanguage(boolean compiled) {
      this();
      compiledTemplate = compiled;
   }

   public TemplateLanguage() {
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
    * Two types of templates: those evaluated during the build procesds to generate the source and
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

   public Object parse(String fileName, Reader reader, Parselet start, boolean enablePartialValues, boolean matchOnly, Object toPopulateInst, int bufSize) {
      Object templateObj = super.parse(fileName, reader, start, enablePartialValues, matchOnly, toPopulateInst, bufSize);
      if (templateObj instanceof ParseError)
         return templateObj;
      Object semValue = ParseUtil.nodeToSemanticValue(templateObj);
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
         temp.templateProcessor = new TemplateResultProcessor(temp, fileName, null);

         return templateObj;
      }
      else {
         // We might not be parsing the startParselet
         return templateObj;
      }
   }

   public class TemplateResultProcessor implements ITemplateProcessor, INameContext {
      String file, result;
      Template template;

      public TemplateResultProcessor(Template temp, String f, String res) {
         template = temp;
         file = f;
         result = res;
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

            // When both compiling and generating the template, use the alternate flag
            boolean prependPackage = compiledTemplate ? prependLayerPackageOnProcess : prependLayerPackage;
            String prefixToUse = processPrefix != null ? processPrefix : templatePrefix;
            String prefix = FileUtil.concat(prefixToUse, prependPackage ? template.getPackagePrefixDir() : null);
            String newFile = FileUtil.concat(
                    outputDir == null ? (useSrcDir ? buildSrcDir : buildLayer.buildDir) :  outputDir,
                    FileUtil.concat(prefix, src.relFileName));
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
                     if (processOnlyURLs && (hasURL = ModelUtil.getInheritedAnnotation(sys, rootTypeObj, "sc.html.URL") == null) && (hasMainInit = ModelUtil.getInheritedAnnotation(buildLayer.layeredSystem, rootTypeObj, "sc.html.MainInit") == null)) {
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
         return new TemplateResultProcessor(templ, file, result);
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
