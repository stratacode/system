/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.lang.java.JavaModel;
import sc.lang.java.TransformUtil;
import sc.lang.js.JSLanguage;
import sc.layer.LayeredSystem;
import sc.layer.SrcEntry;
import sc.parser.*;
import sc.util.ExtensionFilenameFilter;
import sc.util.FileUtil;
import sc.util.StringUtil;

import java.io.File;
import java.io.FilenameFilter;
import java.io.StringReader;
import java.util.*;

public class TestUtil {

   static String [] inputs = {
           //"class TestClass {  @SuppressWarnings({\"unchecked\", \"deprecation\"}) int testMethod() { return 0; }}",
           //"class TestClass { @Author(name = \"friedo\", date=\"6/6/96\") int testMethod() { return 0; }}"
           //"class TestClass { @SuppressWarnings(\"deprecation\") int testMethod() { return 0; }}"
           //"class TestClass { @SuppressWarnings(value = \"unchecked\") int testMethod() { return 0; }}"

           //"class TestClass { int testMethod() { String esc = \"\\n\\t\\rnormal\"; }}"
           //"import java.util.HashMap; class TestClass { HashMap<String,String> testMap; }"
           //"class TestClass { int testMethod() { int fum = (var < 5 ? 2 : 3); }}"
           //"class TestClass { int testMethod() { int var = ((3 + 4) * (5 / 6) - (7 % (8 ^ 9))); }}"
           //"class TestClass { int testMethod() { do { for (int i = 0; i < 100; i++) System.out.println(\"foo\"); } " +
           //        " while (true); } }",
           //"class TestClass { int testMethod() { int var = 3; Object fum = new Object(); synchronized(fum) { var += 6; } } }"
           //"package testPackage; public class TestClass { char testMember = 'c'; }",
           //"/* comment1 */ class TestClass { String testMember = \"str\"; }",
           //"class TestClass { String[] strArray = {\"foo\", \"bar\" }; }",
           //"class TestClass { long expr = 27l; }",
           //"class TestClass { double expr = 10 * 5.3 + 27l / -93.5; }",
           //"class TestClass { void testMethod() { if (5 == 4) System.out.println(\"wow\"); } }",
           //"package a.b;  import imppackage.impclass; public interface InterfaceName { public void methodName(); }"
           /*
     "package a.b.c;  import imppackage.impack2.impclass; public class ClassName implements InterfaceName " +
             "{ public void methodName(int param1, float param2) {" +
             "     int locVar1; " +
            "     String foo = 'c' + \"str\"; " +
             "     for (int i = 0; i < 100; i++) System.out.println(i*25+32/7);" +
             " }}"
             */
   };

   static String [] inputFileNames = {
           //"/jjv/openjdk/jdk-src/java/"

           "/jjv/test/style.css"

           /*
           "t1.java",
           "g1.java",
           "g2.java",
           "g3.java",
           "t3.java",
           "t0.java",
           "t9.java",
           "t8.java",
           "t7.java",
           "t0.java",
           "t3.java",
           "t2.java",
           "t5.java",
           "typedMethodExpression.java",
           "g4.java",
           "g5.java",
           "g6.java",
           "g7.java",
           "t6.java",
           "g8.java",
           "CalcLR.java",
           "IParse.java",
           "g9.java",
           "Parselet.java",
           "g10.java",
           "g11.java",
           "g12.java",
           "g13.java",
           "g14.java",
           "g15.java",
           "g16.java",
           "g17.java",
           "g18.java",
           "extdep1.java",
           "extdep2.java",
           "Parser.java",
           "identtest.java",
           "BindableTest.java",


           "SymbolChoice.java",
           "OrderedChoice.java",
           "NestedParselet.java",
           "IParserConstants.java",
           "Sequence.java",
           "ParseNode.java",
           "Language.java",
           "Symbol.java",
           "Formatter.java",
           "g20.java",
           "ParseError.java",
           "HashMap.java",
           "Collections.java",
           "Arrays.java",
           "TreeMap.java",
           "GregorianCalendar.java",
           "ResourceBundle.java",
           "System.java",
           "Character.java",
           "Thread.java",
           "Scanner.java",
           "BigComment.java"
           */
   };

   public static void main(String[] args) {

      TestOptions opts = new TestOptions();

      List<String> buildList = null;

      for (int i = 0; i < args.length; i++) {
         if (args[i].length() == 0)
            usage(args);
         if (args[i].charAt(0) == '-') {
            if (args[i].length() == 1)
               usage(args);

            String opt = args[i].substring(1);
            switch (opt.charAt(0)) {
               case 'm':
                  if (opt.length() > 1)
                     usage(args);
                  opts.enableModelToString = true;
                  break;
               case 'c':
                  if (opt.equals("cp")) {
                     if (args.length < i + 1)
                        System.err.println("*** No classpath argument to -cp option. ");
                     else {
                        i++;
                        opts.classPath = args[i];
                     }
                  }
                  else {
                     System.err.println("*** Unrecognized option: " + opt);
                  }
                  break;
               case 'e':
                  if (opt.equals("ep")) {
                     if (args.length < i + 1)
                        System.err.println("*** No classpath argument to -ep option. ");
                     else {
                        i++;
                        opts.externalClassPath = args[i];
                     }
                  }
                  else {
                     System.err.println("*** Unrecognized option: " + opt);
                  }
                  break;
               case 's':
                  if (opt.equals("sp")) {
                     if (args.length < i + 1)
                        System.err.println("*** No srcPath argument to -sp option. ");
                     else {
                        i++;
                        opts.srcPath = args[i];
                     }
                  }
                  else if (opt.equals("s")) {
                     opts.enableStyle = true;
                  }
                  else {
                     System.err.println("*** Unrecognized option: " + opt);
                  }
                  break;
               case 'd':
                  if (opt.length() > 1)
                     usage(args);
                  dumpStats = true;
                  break;
               case 'n':
                  if (opt.length() > 1)
                     usage(args);
                  verifyResults = false;
                  break;
               case 'f':
                  if (opt.length() > 1)
                     usage(args);
                  opts.finalGenerate = false;
                  break;
               case 'p':
                  if (opt.length() > 1)
                     usage(args);
                  opts.enablePartialValues = true;
                  break;
               case 'l':
                  if (opt.length() > 1)
                     usage(args);
                  opts.layerMode = true;
                  break;
               case 'r':
                  if (opt.length() > 1)
                     usage(args);
                  try {
                     if (args.length < i + 1)
                        System.err.println("*** No count argument to -r option for repeating the parse <n> times. ");
                     else {
                        i++;
                        opts.repeatCount = Integer.parseInt(args[i]);
                     }
                  }
                  catch (NumberFormatException exc) {
                     System.err.println("*** bad value to repeat count");
                  }
                  break;
               default:
                  System.err.println("*** Unrecognized option: " + opt);
                  usage(args);
                  break;
            }
         }
         else {
            if (buildList == null)
               buildList = new ArrayList<String>(args.length-1);
            buildList.add(args[i]);
         }
      }

      // In layer mode, we pick up the LayeredSystem's languages
      if (!opts.layerMode) {
         Language.registerLanguage(JavaLanguage.INSTANCE, "java");
         Language.registerLanguage(SCLanguage.INSTANCE, "sc");
         // This one is just for templates we process from Java code, i.e. as part of framework definitions.
         Language.registerLanguage(TemplateLanguage.INSTANCE, "sctd");
         // This is a real template, treated as a regular type in the system, either dynamic or compiled
         Language.registerLanguage(TemplateLanguage.INSTANCE, "sct");
         Language.registerLanguage(JSLanguage.INSTANCE, "js");
         Language.registerLanguage(HTMLLanguage.INSTANCE, "schtml");
         Language.registerLanguage(HTMLLanguage.INSTANCE, "html");
         Language.registerLanguage(HTMLLanguage.INSTANCE, "scsp");
         Language.registerLanguage(CSSLanguage.INSTANCE, "scss");
         Language.registerLanguage(CSSLanguage.INSTANCE, "css");
      }
      else {
         if (opts.srcPath == null && buildList != null) {
            LinkedHashSet<String> srcDirs = new LinkedHashSet<String>();
            for (String buildFile:buildList) {
               String parentDir = FileUtil.getParentPath(buildFile);
               if (parentDir == null)
                  srcDirs.add(".");
               else
                  srcDirs.add(parentDir);
            }
            opts.srcPath = StringUtil.arrayToPath(srcDirs.toArray());
         }
      }

      parseTestFiles(buildList == null ? inputFileNames : buildList.toArray(new String[0]), opts);

      if (dumpStats) {
         System.out.println("*** Stats:");
         System.out.println(Parser.getStatInfo(JavaLanguage.INSTANCE.compilationUnit));
      }
   }

   static void usage(String[] args) {
      System.out.println("*** bad args to testUtil: " + Arrays.asList(args));
      System.exit(1);
   }

   static final FilenameFilter LANG_FILTER = new ExtensionFilenameFilter(Language.getLanguageExtensions(), true);

   static boolean verifyResults = true;
   static boolean dumpStats = false;

   public static class TestOptions {
      boolean enableModelToString = false;
      boolean enableStyle = false;
      boolean finalGenerate = true;
      boolean enablePartialValues = false;
      int repeatCount = 1;
      boolean layerMode = false;
      String classPath;
      String externalClassPath;
      String srcPath;
   }

   public static void parseTestFiles(Object[] inputFiles, TestOptions opts) {
      JavaLanguage.register();
      SCLanguage.register();
      
      for (Object fileObj : inputFiles) {
         String fileName;
         File file;
         if (fileObj instanceof File) {
            file = (File) fileObj;
            fileName = file.toString();
         }
         else {
            fileName = (String) fileObj;
            if (fileName.startsWith("#"))
               continue;
            else if (fileName.equals("-")) // stop processing files when we hit a -
               return;
            file = new File(fileName);
         }
         if (file.isDirectory()) {
            Object[] files;
            files = FileUtil.getLinesInFile(new File(file, "testFiles.txt"));
            if (files == null || (files.length > 0 && files[0].equals("*")))
               files = file.listFiles(LANG_FILTER);
            if (files == null)
               System.err.println("**** Unable to read directory: " + file);
            else
               parseTestFiles(files, opts);
            continue;
         }

         Object result;
         String input = ParseUtil.readFileString(file);
         if (input == null) {
            System.out.println("*** Can't open file: " + fileName);
            continue;
         }
         Language lang;

         String ext = FileUtil.getExtension(file.getPath());

         long startTime = System.currentTimeMillis();
         int ct = opts.repeatCount;
         do {
            // Parsing using the language directly - no layered system involved so we can only validate the grammar
            if (!opts.layerMode) {
               lang = Language.getLanguageByExtension(ext);
               if (lang == null)
                  throw new IllegalArgumentException("No language for: " + file.getPath());
               result = lang.parse(fileName, new StringReader(input), lang.getStartParselet(), opts.enablePartialValues);
            }
            else {
               LayeredSystem sys = ParseUtil.createSimpleParser(opts.classPath, opts.externalClassPath, opts.srcPath);
               lang = (Language) sys.getFileProcessorForExtension(ext);

               SrcEntry srcEnt = sys.getSrcEntryForPath(fileName, false);
               if (srcEnt == null) {
                  System.err.println("*** Unable to find srcFile: " + fileName + " in: " + opts.srcPath);
                  result = null;
               }
               else {
                  Object modelResult = sys.parseSrcFile(srcEnt, true);
                  if (!(modelResult instanceof ParseError)) {
                     result = ((ISemanticNode) modelResult).getParseNode();
                     // Passed the parse phase
                     if (modelResult instanceof JavaModel) {
                        JavaModel model = (JavaModel) modelResult;
                        ParseUtil.initAndStartComponent(model);
                     }
                     else {
                        System.err.println("*** Parse result not a JavaModel: " + result);
                     }
                  }
                  else // ParseError - handled below...
                     result = modelResult;
               }
            }
         } while (--ct > 0);

         long parseResultTime = System.currentTimeMillis();

         System.out.println("*** parsed: " + fileName + " " + opts.repeatCount + (opts.repeatCount == 1 ? " time" : " times") + " in: " + rangeToSecs(startTime, parseResultTime));

         if (verifyResults) {
            if (result == null || !input.equals(result.toString())) {
               if (result instanceof ParseError)
                  System.out.println("File: " + fileName + ": " + ((ParseError) result).errorStringWithLineNumbers(file));
               else
               {
                  System.err.println("*** FAILURE: Parsed results do not match for file: " + fileName);
                  System.out.println(input + " => " + result);
               }
            }
            else {
               IParseNode node = (IParseNode) result;
               System.out.println("parse results match for: " + fileName);

               //if (result instanceof IParseNode)
               //   System.out.println(((IParseNode)result).toDebugString());

               //System.out.println("Model:" + node.getSemanticValue());

               Object modelObj = getTestResult(node);

               StringBuilder sb = new StringBuilder();

               if (opts.enableModelToString)
                  System.out.println("Parsed model: " + (modelObj instanceof ISemanticNode ? ((ISemanticNode)modelObj).toModelString() : modelObj.toString()));

               //((JavaLanguage) lang).identifierExpression.trace = true;
               if (opts.enableStyle)
                  System.out.println("Styled output: " + ParseUtil.styleSemanticValue(modelObj, result));

               if (modelObj instanceof JavaModel) {
                  JavaModel m = (JavaModel) modelObj;
                  TransformUtil.makeClassesBindable(m);

                  if (lang instanceof JavaLanguage && !(lang instanceof TemplateLanguage))
                     sb.append("/* External references: " + m.getExternalReferences() + "*/" + FileUtil.LINE_SEPARATOR);
               }

               //System.out.println("Bindable result: " + result);

               // Clear out the old parse-tree
               node.setSemanticValue(null);

               // For debugging the generation rpocess only
               lang.debug = false;

               // Generate a completely new one
               String genFileName = FileUtil.replaceExtension(fileName, "result");

               long generateStartTime = System.currentTimeMillis();

               Object generateResult = lang.generate(modelObj, opts.finalGenerate);
               if (generateResult instanceof GenerateError)
                  System.err.println("**** FAILURE during generation: " + generateResult);
               else {
                  String genResult = generateResult.toString();

                  long generateResultTime = System.currentTimeMillis();
                  if ((lang instanceof JavaLanguage) && !(lang instanceof TemplateLanguage)) {
                     sb.append("/* GeneratedResult: */" + FileUtil.LINE_SEPARATOR);
                  }
                  sb.append(genResult);
                  FileUtil.saveStringAsFile(genFileName, sb.toString(), true);

                  System.out.println("*** processed: " + fileName + " bytes: " + sb.length() +
                          " generate: " + rangeToSecs(generateStartTime, generateResultTime));

                  Object reparsedResult = lang.parse(new StringReader(genResult));
                  if (reparsedResult instanceof ParseError)
                     System.err.println("FAILURE: Unable to parse result: " + genFileName + " e: " + reparsedResult);
                  else {
                     if (!reparsedResult.toString().equals(genResult))
                        System.err.println("**** FAILURE - reparsed result does not match generated result: " + fileName);

                     Object mnew = getTestResult(reparsedResult);
                     // TODO: this breaks for schtml files - Element.equals uses == cause the deep version of that implementation is too expensive for large pages
                     // TODO: Semantic node list - add deepEquals and use that here
                     if (!((ISemanticNode) mnew).deepEquals(modelObj)) {
                        System.err.println("**** FAILURE - reparsed model does not match for: " + fileName);
                        mnew.equals(modelObj);
                     }
                     else
                        System.out.println("***** SUCCESS: models match for: " + fileName);
                  }
               }
               System.out.println();
            }
         }
      }
   }
   private static Object getTestResult(Object node) {
      Object modelObj = ParseUtil.nodeToSemanticValue(node);
      if (modelObj instanceof JavaModel) {
         ((JavaModel) modelObj).disableTypeErrors = true;
      }
      ParseUtil.initAndStartComponent(modelObj);
      return modelObj;
   }

   static String rangeToSecs(long start, long end) {
      String str = Double.toString((end - start)/1000.0);
      return str.length() > 4 ? str.substring(0,4) : str;
   }
}
