/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.binf.ParseInStream;
import sc.lang.java.JavaModel;
import sc.lang.java.TransformUtil;
import sc.lang.js.JSLanguage;
import sc.layer.LayeredSystem;
import sc.layer.SrcEntry;
import sc.parser.*;
import sc.util.ExtensionFilenameFilter;
import sc.util.FileUtil;
import sc.util.StringUtil;

import java.io.Console;
import java.io.File;
import java.io.FilenameFilter;
import java.io.StringReader;
import java.text.DecimalFormat;
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

   public static int numErrors = 0;

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
                  else if (opt.equals("cn")) {
                     if (args.length < i + 1)
                        System.err.println("*** No class name argument to -cn option. ");
                     else {
                        i++;
                        opts.findClassName = args[i];
                     }
                  }
                  else if (opt.equals("cc"))
                     opts.crossCompile = true;
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
               case 'q':
                  quietOutput = true;
                  break;
               case 'P':
                  diffParseNodes = true;
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
                  if (opt.length() > 1) {
                     if (opt.equals("rp")) {
                        i++;
                        if (args.length < i + 1)
                           System.err.println("*** No file argument to -rp option for reparsing files.");
                        else {
                           String rpArg = args[i];
                           opts.reparseFiles = rpArg.split(",");
                        }
                     }
                     else if (opt.equals("ri")) {
                        i++;
                        if (args.length < i + 1)
                           System.err.println("*** No file argument to -ri option for reparsing files.");
                        else {
                           String indexArg = args[i];
                           opts.testArgsId = indexArg;
                           String[] repeatArgs = indexArg.split(",");
                           ArrayList<String> reparseIndexes = opts.reparseIndexes = new ArrayList<String>(Arrays.asList(repeatArgs));
                           for (int curIx = 0; curIx < reparseIndexes.size(); curIx++) {
                              String ra = reparseIndexes.get(curIx);
                              int dashIx = ra.indexOf('-');
                              if (dashIx != -1) {
                                 reparseIndexes.remove(curIx);
                                 String[] minMaxStr = ra.split("-");
                                 int min = Integer.parseInt(minMaxStr[0]);
                                 int max = Integer.parseInt(minMaxStr[1]);

                                 for (int reparseIx = min; reparseIx <= max; reparseIx++) {
                                    reparseIndexes.add(curIx, String.valueOf(reparseIx));
                                    curIx++;
                                 }
                              }
                           }
                        }
                     }
                     else
                        usage(args);
                  }
                  else {
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
               buildList = new ArrayList<String>(args.length - 1);
            buildList.add(args[i]);
         }
      }

      // In layer mode, we pick up the LayeredSystem's languages
      if (!opts.layerMode) {
         Language.registerLanguage(JavaLanguage.INSTANCE, "java");
         Language.registerLanguage(JavaLanguage.INSTANCE, "scj");
         Language.registerLanguage(SCLanguage.INSTANCE, "sc");
         TemplateLanguage objDefTemplateLang = new TemplateLanguage();
         objDefTemplateLang.compiledTemplate = false;
         objDefTemplateLang.runtimeTemplate = true;
         objDefTemplateLang.defaultExtendsType = "sc.lang.java.ObjectDefinitionParameters";
         // This one is for object definition templates we process from Java code, i.e. as part of framework definitions from CompilerSettings, newTemplate, and objTemplate
         Language.registerLanguage(objDefTemplateLang, "sctd");
         // This is a real template, treated as a regular type in the system, either dynamic or compiled
         Language.registerLanguage(TemplateLanguage.INSTANCE, "sct");
         Language.registerLanguage(TemplateLanguage.INSTANCE, "sctjs");
         Language.registerLanguage(JSLanguage.INSTANCE, "js");
         Language.registerLanguage(HTMLLanguage.INSTANCE, "schtml");
         Language.registerLanguage(HTMLLanguage.INSTANCE, "html");
         Language.registerLanguage(HTMLLanguage.INSTANCE, "scsp");
         Language.registerLanguage(XMLLanguage.INSTANCE, "xml");
         Language.registerLanguage(CSSLanguage.INSTANCE, "sccss");
         Language.registerLanguage(CSSLanguage.INSTANCE, "css");
         Language.registerLanguage(CommandSCLanguage.INSTANCE, "scr");
      }
      else {
         if (opts.srcPath == null && buildList != null) {
            LinkedHashSet<String> srcDirs = new LinkedHashSet<String>();
            for (String buildFile : buildList) {
               String parentDir = FileUtil.getParentPath(buildFile);
               if (parentDir == null)
                  srcDirs.add(".");
               else
                  srcDirs.add(parentDir);
            }
            opts.srcPath = StringUtil.arrayToPath(srcDirs.toArray(), false);
         }
      }

      parseTestFiles(buildList == null ? inputFileNames : buildList.toArray(new String[0]), opts);

      if (opts.findClassName != null) {
         if (opts.system == null)
            System.err.println("*** -cn option only works with -l option");
         else {
            Object res = opts.system.getClass(opts.findClassName, false);
            if (res == null) {
               System.err.println("*** Find class name: " + opts.findClassName + " not found");
            }
            else {
               out("Success - found " + opts.findClassName + ": " + res);
            }
         }
      }

      if (dumpStats) {
         out("*** Stats:");
         out(Parser.getStatInfo(JavaLanguage.INSTANCE.compilationUnit));
      }

      if (numErrors != 0) {
         System.err.println("*** FAILED with " + numErrors + " errors");
         System.exit(numErrors);
      }
   }

   static void out(String message) {
      if (!quietOutput)
         System.out.println(message);
      testOutput.append(message);
      testOutput.append(FileUtil.LINE_SEPARATOR);
   }

   static void error(String message) {
      numErrors++;
      System.err.println(message);
   }

   static void usage(String[] args) {
      System.out.println("*** bad args to testUtil: " + Arrays.asList(args));
      System.exit(1);
   }

   static final FilenameFilter LANG_FILTER = new ExtensionFilenameFilter(Language.getLanguageExtensions(), true);

   static boolean verifyResults = true;
   static boolean diffParseNodes = false;
   static boolean dumpStats = false;
   static boolean quietOutput = false;

   public static class TestOptions {
      boolean enableModelToString = false;
      boolean enableStyle = false;
      boolean finalGenerate = true;
      boolean enablePartialValues = false;
      boolean crossCompile = false;
      boolean interactive = true;
      int repeatCount = 1;
      boolean layerMode = false;
      // If layerMode = true, the LayeredSystem used
      LayeredSystem system;
      String classPath;
      String externalClassPath;
      String srcPath;
      String findClassName;
      String[] reparseFiles;
      ArrayList<String> reparseIndexes;

      String testBaseName;
      String testArgsId = "";

      public String toString() {
         StringBuilder sb = new StringBuilder();
         sb.append("(");
         boolean first = true;
         if (enablePartialValues) {
            sb.append("enablePartialValues");
            first = false;
         }
         if (reparseIndexes != null) {
            if (!first)
               sb.append(" ,");
            sb.append("reparse: ");
            sb.append(reparseIndexes);
            first = false;
         }
         return sb.toString();
      }
   }

   static StringBuilder testOutput = new StringBuilder();

   public static void parseTestFiles(Object[] inputFiles, TestOptions opts) {
      JavaLanguage.register();
      SCLanguage.register();

      out("Running language test: " + StringUtil.arrayToString(inputFiles) + ": options: " + opts.toString() + " in dir: " + System.getProperty("user.dir"));

      ArrayList<String> reparseStats = new ArrayList<String>();

      StringBuilder testVerifyOutput = new StringBuilder();
      ArrayList<String> newModelErrors = new ArrayList<String>();

      for (Object fileObj : inputFiles) {
         String fileName;
         File file;
         if (fileObj instanceof File) {
            file = (File) fileObj;
            fileName = file.toString();

            if (opts.testBaseName == null)
               opts.testBaseName = FileUtil.removeExtension(fileName);
         }
         else {
            fileName = (String) fileObj;
            if (fileName.startsWith("#"))
               continue;
            else if (fileName.equals("-")) // stop processing files when we hit a -
               return;
            file = new File(fileName);

            if (opts.testBaseName == null)
               opts.testBaseName = FileUtil.removeExtension(fileName);
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
               lang.debugReparse = true;

               /*
               SCLanguage.getSCLanguage().identifierExpression.trace = true;
               SCLanguage.getSCLanguage().selectorExpression.trace = true;
               */

               //JavaLanguage.getJavaLanguage().switchStatement.trace = true;

               //HTMLLanguage.getHTMLLanguage().templateBlockStatements.trace = true;

               result = lang.parse(fileName, new StringReader(input), lang.getStartParselet(), opts.enablePartialValues);
            }
            else {
               LayeredSystem sys = ParseUtil.createSimpleParser(opts.classPath, opts.externalClassPath, opts.srcPath, null);
               sys.options.crossCompile = opts.crossCompile;
               lang = (Language) sys.getFileProcessorForExtension(ext);
               opts.system = sys;

               SrcEntry srcEnt = sys.getSrcEntryForPath(fileName, false, true);
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

         out("Parsed: " + fileName + " " + opts.repeatCount + (opts.repeatCount == 1 ? " time" : " times") + " in: " + rangeToSecs(startTime, parseResultTime));

         if (opts.reparseIndexes != null) {
            opts.reparseFiles = new String[opts.reparseIndexes.size()];
            int rix = 0;
            String fileNoExt = FileUtil.removeExtension(fileName);
            for (String reparseIndex:opts.reparseIndexes) {
               opts.reparseFiles[rix] = FileUtil.addExtension(fileNoExt + reparseIndex, ext);
               rix++;
            }
         }

         if (opts.reparseFiles != null) {
            for (String reparseFile:opts.reparseFiles) {
               String reparsedString = ParseUtil.readFileString(new File(reparseFile));
               if (reparsedString == null) {
                  System.err.println("*** Unable to open -rp - reparse file: " + reparseFile);
               }
               else {
                  if (result instanceof ParseError) {
                     ParseError err = ((ParseError) result);
                     result = err.getBestPartialValue();
                  }
                  // 218 - is where we change from forVar to forControl
                  // 250 - is where it should parse
                  if (reparseFile.contains("9")) {
                     //HTMLLanguage.getHTMLLanguage().blockStatements.trace = true;
                     //SCLanguage.getSCLanguage().classBody.trace = true;
                     //SCLanguage.getSCLanguage().blockStatements.trace = true;
                     //JavaLanguage.getJavaLanguage().classBodyDeclarations.trace = true;
                     //HTMLLanguage.getHTMLLanguage().templateBodyDeclarations.trace = true;
                     // Let's you set breakpoints easily for semantic nodes that don't match
                     //SemanticNode.debugDiffTrace = true;
                     // Enables breakpoints for finding the diffs in the old and new versions
                     //DiffContext.debugDiffContext = true;
                  }
                  if (result == null)
                     out("*** FAILURE: No previous result for reparse");

                  // Reset the stats for each file
                  lang.globalReparseCt = lang.globalParseCt = 0;

                  Object newRes = ParseUtil.reparse((IParseNode) result, reparsedString);
                  String reparseErrorMessage = null;
                  if (newRes instanceof ParseError) {
                     ParseError newErr = (ParseError) newRes;
                     reparseErrorMessage = newErr.toString();
                     Object newPV = newErr.getBestPartialValue();
                     if (newPV == null)
                        error("*** FAILURE: no reparse result!");
                     else
                        newRes = newPV;
                  }

                  if (verifyResults) {
                     if (newRes instanceof IParseNode) {
                        // We should have already updated the startIndex in all of the parse nodes but we are validating that they are set correctly here
                        ((IParseNode) newRes).resetStartIndex(0, true, false);
                     }

                     Object reparsedModelObj = getTestResult(newRes);

                     Object parseComplete = lang.parseString(reparsedString, opts.enablePartialValues);
                     boolean parseError = false;
                     if (parseComplete instanceof ParseError) {
                        parseError = true;
                        ParseError parseCompleteErr = (ParseError) parseComplete;
                        String parseCompleteErrorMessage = parseCompleteErr.toString();
                        if (reparseErrorMessage != null && !parseCompleteErrorMessage.equals(reparseErrorMessage))
                           error("*** Error - reparse returned different error - reparse: " + reparseErrorMessage + " complete: " + parseCompleteErrorMessage);
                        parseComplete = parseCompleteErr.getBestPartialValue();
                        if (parseComplete == null)
                           error("No partial value for file with syntax errors: " + reparseFile);
                     }

                     boolean exactMatch = false;
                     boolean modelErrorsOk = false;
                     if (parseComplete instanceof IParseNode) {
                        Object parseCompleteObj = getTestResult(parseComplete);

                        String newResStr = newRes.toString();
                        String parseStr = parseComplete.toString();

                        boolean reparseSameAsOrig = newResStr.equals(reparsedString);
                        boolean parseSameAsOrig = parseStr.equals(reparsedString);
                        boolean reparseSameAsParse = newResStr.equals(parseStr);

                        if (parseError) { // NOTE: sometimes reparseError will be false - if we reparse a document that was originally an error and then is not changed on the reparse - we just return the original
                           // If we have an error in both parse and reparse and everything else matches and we have at least a partial parse of the
                           // value it's ok
                           if (reparsedString.startsWith(newResStr) && reparsedString.startsWith(parseStr)) {
                              if (reparseSameAsParse || reparseSameAsOrig)
                                  parseSameAsOrig = reparseSameAsOrig = true;
                              else {
                                 error("*** FAILURE: Reparsed partial match does not match parsed partial match ");
                              }
                           }
                        }

                        if (!reparseSameAsOrig || !parseSameAsOrig || !reparseSameAsParse) {
                           if (!parseSameAsOrig)
                              error("*** PARSE FAILURE - parsed text does not match original - reparse matches parse: " + reparseSameAsParse);
                           else {
                              if (!reparseSameAsOrig)
                                 error("*** REPARSE FAILURE - reparsed text does not match");
                              // There are valid cases where the reparsed string is longer than the parse-string
                              else if (!newResStr.startsWith(parseStr))
                                 error("*** REPARSE FAILURE - reparsed text does not match - parsed"); // is this possible?
                           }
                        }


                        if (reparsedModelObj instanceof ISemanticNode) {
                           // These must be inited so some properties get set which are compared - it would be nice to have a way to compare just the parsed models but we need two categories of these
                           // semantic properties - cloned, parsed?
                           ParseUtil.initComponent(parseCompleteObj);
                           // Need to reinit so we catch the reparsed models - TODO: ideally we'd clear the inited flag of any parents who have children which are modified in the reparse so we only
                           // have to reinit those.
                           ParseUtil.reinitComponent(reparsedModelObj);
                           StringBuilder diffs = new StringBuilder();
                           ISemanticNode reparsedNode = (ISemanticNode) reparsedModelObj;
                           reparsedNode.diffNode(parseCompleteObj, diffs);

                           String modelNewErrorsFile = FileUtil.addExtension(FileUtil.removeExtension(reparseFile), "mismatch");
                           String modelOkErrorsFile = FileUtil.addExtension(FileUtil.removeExtension(reparseFile), "mismatchOK");
                           if (diffs.length() > 0) {
                              String diffStr = diffs.toString();
                              boolean saveNew = false;
                              if (new File(modelOkErrorsFile).canRead()) {
                                 String acceptErrors = FileUtil.getFileAsString(modelOkErrorsFile);
                                 if (!acceptErrors.equals(diffStr)) {
                                    error("*** Changed model errors for: " + reparseFile + " Old ok'd errors:\n" + acceptErrors);
                                    System.err.println("New model errors:\n" + diffStr);
                                    saveNew = true;
                                 }
                                 else {
                                    modelErrorsOk = true;
                                 }
                              }
                              else {
                                 error("*** New model errors for: " + reparseFile + ":\n" + diffStr);
                                 saveNew = true;
                              }
                              if (saveNew) {
                                 FileUtil.saveStringAsFile(modelNewErrorsFile, diffStr, false);
                                 newModelErrors.add(modelNewErrorsFile);
                              }
                           }
                           else {
                              exactMatch = true;
                              File modelErrorFile = new File(modelNewErrorsFile);
                              if (modelErrorFile.canRead()) {
                                 System.out.println("*** Warning - removing old model errors for: " + reparseFile);
                                 modelErrorFile.delete();
                              }
                              File modelOkFile = new File(modelOkErrorsFile);
                              if (modelOkFile.canRead()) {
                                 System.out.println("*** Warning - removing ok'd old model errors for: " + reparseFile);
                                 modelOkFile.delete();
                              }
                           }
                        }
                     }
                     else if (parseComplete != null)
                        error("*** Unrecognized return from reparse: " + parseComplete);

                     if (diffParseNodes) {
                        if (newRes instanceof IParseNode && !(parseComplete instanceof IParseNode)) {
                           error("Parse node results are not the same");
                        }
                        else if (newRes instanceof ParseError && !(parseComplete instanceof ParseError)) {
                           error("Parse node error is not the same");
                        }
                        else if (newRes instanceof IParseNode) {
                           StringBuilder parseNodeDiffs = new StringBuilder();
                           ((IParseNode) newRes).diffParseNode((IParseNode) parseComplete, parseNodeDiffs);
                           if (parseNodeDiffs.length() > 0) {
                              error("Parse nodes are not the same: " + parseNodeDiffs);
                           }
                        }
                        else {
                           // TODO: compare the ParseErrors here?
                        }
                     }

                     double reparsePer = 100.0 * lang.globalReparseCt / (double) lang.globalParseCt;
                     String per = new DecimalFormat("#").format(reparsePer);
                     reparseStats.add(per);
                     out("Reparsed: " + reparseFile + ": "  + per + "% nodes (" + lang.globalReparseCt + "/" + lang.globalParseCt + ")" +
                         (exactMatch ? "" : (modelErrorsOk ? " ok'd modelMismatch" : " modelMismatch")));
                  }
               }
            }
         }
         else if (verifyResults) {
            if (result == null || !input.equals(result.toString())) {
               if (result instanceof ParseError)
                  out("File: " + fileName + ": " + ((ParseError) result).errorStringWithLineNumbers(file));
               else
               {
                  error("*** FAILURE: Parsed results do not match for file: " + fileName);
                  out(input + " => " + result);
               }
            }
            else {
               IParseNode node = (IParseNode) result;
               out("parse results match for: " + fileName);

               //if (result instanceof IParseNode)
               //   System.out.println(((IParseNode)result).toDebugString());

               //System.out.println("Model:" + node.getSemanticValue());

               Object modelObj = getTestResult(node);

               if (modelObj instanceof ISemanticNode) {
                  String serFileName = FileUtil.replaceExtension(fileName, "mbinf");
                  ParseUtil.serializeModel((ISemanticNode) modelObj, serFileName, fileName);

                  String parseFileName = FileUtil.replaceExtension(fileName, "pbinf");
                  ParseUtil.serializeParseNode(node, parseFileName, fileName);

                  int rc = 0;
                  do {
                     long startDeserTime = System.currentTimeMillis();
                     ISemanticNode deserModel = ParseUtil.deserializeModel(serFileName);
                     long endDeserTime = System.currentTimeMillis();

                     out("Deserialized: " + fileName + " " + opts.repeatCount + (opts.repeatCount == 1 ? " time" : " times") + " in: " + rangeToSecs(startDeserTime, endDeserTime));

                     if (!deserModel.equals(modelObj))
                        error("Deserialize - results do not match!");
                     else {
                        out("Serialized models match");
                     }

                     long startRestoreTime = System.currentTimeMillis();
                     ParseInStream pIn = rc % 2 == 0 ? ParseUtil.openParseNodeStream(parseFileName) : null;
                     Object restored = lang.restore(fileName, deserModel,  pIn, false);
                     long endRestoreTime = System.currentTimeMillis();
                     out("Restored " + (pIn == null ? " with no parse stream" : " with parse stream") + ": " + fileName +  " in: " + rangeToSecs(startRestoreTime, endRestoreTime));

                     if (restored instanceof ParseError || restored == null)
                        error("Invalid return from restore - should always restore to a valid parse node.");

                     if (restored instanceof IParseNode) {
                        if (deserModel.getParseNode() != restored)
                           error("*** Error - model not updated with parse node tree!");

                        if (!restored.toString().equals(result.toString()))
                           error("Restored parse node text does not match");
                        else if (!restored.equals(result))
                           error("Restored parse nodes do not match");
                        else {
                           // TODO: compare the parse node trees and verify that the semantic node is registered properly onto it
                           out("Restored parse node successfully");
                        }
                     }
                     rc++;
                  } while (rc < 2);
               }

               StringBuilder sb = new StringBuilder();

               if (opts.enableModelToString)
                  out("Parsed model: " + (modelObj instanceof ISemanticNode ? ((ISemanticNode)modelObj).toModelString() : modelObj.toString()));

               //((JavaLanguage) lang).identifierExpression.trace = true;
               if (opts.enableStyle)
                  out("Styled output: " + ParseUtil.styleSemanticValue(modelObj, result));

               if (modelObj instanceof JavaModel) {
                  JavaModel m = (JavaModel) modelObj;
                  TransformUtil.makeClassesBindable(m);

                  if (lang instanceof JavaLanguage && !(lang instanceof TemplateLanguage))
                     sb.append("/* External references: " + m.getExternalReferences() + "*/" + FileUtil.LINE_SEPARATOR);
               }

               //System.out.println("Bindable result: " + result);

               // Clear out the old parse-tree
               node.setSemanticValue(null, true);

               // For debugging the generation rpocess only
               lang.debug = false;

               // Generate a completely new one
               String genFileName = FileUtil.replaceExtension(fileName, "result");

               long generateStartTime = System.currentTimeMillis();

               Object generateResult = lang.generate(modelObj, opts.finalGenerate);
               if (generateResult instanceof GenerateError)
                  error("**** FAILURE during generation: " + generateResult);
               else {
                  String genResult = generateResult.toString();

                  long generateResultTime = System.currentTimeMillis();
                  if ((lang instanceof JavaLanguage) && !(lang instanceof TemplateLanguage)) {
                     sb.append("/* GeneratedResult: */" + FileUtil.LINE_SEPARATOR);
                  }
                  sb.append(genResult);
                  FileUtil.saveStringAsFile(genFileName, sb.toString(), true);

                  out("*** processed: " + fileName + " bytes: " + sb.length() +
                          " generate: " + rangeToSecs(generateStartTime, generateResultTime));

                  Object reparsedResult = lang.parse(new StringReader(genResult));
                  if (reparsedResult instanceof ParseError)
                     error("FAILURE: Unable to parse result: " + genFileName + " e: " + reparsedResult);
                  else {
                     if (!reparsedResult.toString().equals(genResult))
                        error("**** FAILURE - reparsed result does not match generated result: " + fileName);

                     Object mnew = getTestResult(reparsedResult);
                     // TODO: this breaks for schtml files - Element.equals uses == cause the deep version of that implementation is too expensive for large pages
                     // TODO: Semantic node list - add deepEquals and use that here
                     if (!((ISemanticNode) mnew).deepEquals(modelObj)) {
                        error("**** FAILURE - reparsed model does not match for: " + fileName);
                        mnew.equals(modelObj);
                     }
                     else
                        out("***** SUCCESS: models match for: " + fileName);
                  }
               }
               out("ln");
            }
         }

         if (reparseStats.size() != 0) {
            testVerifyOutput.append("Reparse percentages: " + reparseStats.toString());
            out("Summary of reparse percentages: " + reparseStats);
         }
      }

      String testName = opts.testBaseName + opts.testArgsId;
      String testId = System.getProperty("user.dir") + "/" + testName;
      String testOutputFileName = FileUtil.addExtension(testName, "goodOut");
      String testVerifyFileName = FileUtil.addExtension(testName, "goodVerify");
      String newOutputFileName = FileUtil.replaceExtension(testOutputFileName , "newOut");
      String newVerifyFileName = FileUtil.replaceExtension(testOutputFileName , "newVerify");
      File testOutputFile = new File(testOutputFileName);
      File testVerifyFile = new File(testVerifyFileName);
      String testVerifyStr = testVerifyOutput.toString();
      String testOutputStr = testOutput.toString();
      boolean needsSave = false;

      FileUtil.saveStringAsFile(newVerifyFileName, testVerifyStr, false);
      FileUtil.saveStringAsFile(newOutputFileName, testOutputStr, false);

      if (testVerifyFile.canRead()) {
         String oldVerify = FileUtil.getFileAsString(testVerifyFileName);
         if (oldVerify == null) {
            testVerifyFile.delete();
         }
         else if (!oldVerify.equals(testVerifyStr)) {
            error("Test output different - new output:\n" + testVerifyStr + "\ngood output:\n" + oldVerify);

            //System.out.println("Run: diff " + testVerifyFile.getAbsolutePath() + " " + new File(newVerifyFileName).getAbsolutePath());
            System.out.println("*** Run diff command for details:");
            System.out.println("diff " + testOutputFile.getAbsolutePath() + " " + new File(newOutputFileName).getAbsolutePath());

            Console c = System.console();
            if (opts.interactive && c != null) {
               String ans = c.readLine("Accept new output? [yn]? ");
               if (ans.equalsIgnoreCase("y") || ans.equalsIgnoreCase("yes")) {
                  FileUtil.renameFile(newVerifyFileName, testVerifyFileName);
                  FileUtil.renameFile(newOutputFileName, testOutputFileName);
                  numErrors = 0; // So we exit with a valid code to continue the script
               }
            }
         }
         else {
            if (numErrors == 0) {
               System.out.println("Success: test passes: " + testId);
            }
            else {
               System.out.println("Failed: " + numErrors + " errors (but verify output matches): " + testId);
               Console c = System.console();
               if (opts.interactive && c != null && newModelErrors.size() > 0) {
                  String ans = c.readLine("Accept new model errors? [yn]?");
                  if (ans.equalsIgnoreCase("y") || ans.equalsIgnoreCase("yes")) {
                     numErrors = 0; // So we exit with a valid code to continue the script
                     for (String newModelError:newModelErrors) {
                        FileUtil.renameFile(newModelError, FileUtil.replaceExtension(newModelError, "mismatchOK"));
                     }
                  }
               }
            }
         }
      }
      else if (testVerifyStr.length() > 0) {
         System.out.println("First run - saving verify out: " + testVerifyFileName + ", test output: " + testOutputFileName + " for: " + testId);
         FileUtil.saveStringAsFile(testVerifyFile, testVerifyStr, false);
         FileUtil.saveStringAsFile(testOutputFile, testOutputStr, false);
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
