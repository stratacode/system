/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.binf.BinfConstants;
import sc.binf.ParseInStream;
import sc.dyn.DynUtil;
import sc.lang.*;
import sc.lang.java.Expression;
import sc.lang.java.JavaModel;
import sc.lang.java.ModelUtil;
import sc.lang.java.TypeDeclaration;
import sc.lang.sc.ModifyDeclaration;
import sc.lang.sc.PropertyAssignment;
import sc.lang.sc.SCModel;
import sc.layer.deps.DependenciesLanguage;
import sc.layer.deps.DependencyFile;
import sc.obj.SyncMode;
import sc.parser.*;
import sc.repos.*;
import sc.type.CTypeUtil;
import sc.type.IBeanMapper;
import sc.type.RTypeUtil;
import sc.type.TypeUtil;
import sc.util.*;

import javax.tools.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.regex.Pattern;

public class LayerUtil implements LayerConstants {

   // This is a build layer we use to hold any generated files required for the layer definition files themselves - i.e.
   // before we've set up the build layer itself.  This will only be needed if we have compiled stubs required for starting
   // the layers themselves.
   public static Layer initCoreBuildLayer(LayeredSystem sys) {
      Layer layer = new Layer();
      sys.coreBuildLayer = layer;
      layer.layeredSystem = sys;
      layer.layerUniqueName = layer.layerDirName = "sys.core.build";
      layer.layerBaseName = "build.sc";
      layer.layerPathName = FileUtil.concat(sys.getNewLayerDir(), "sys", "core", "build");
      layer.dynamic = false;
      layer.defaultModifier = "public";
      layer.buildLayer = true;
      layer.setHasSrc(false);
      layer.initBuildDir();
      layer.makeBuildLayer();
      layer.init();
      layer.start();
      layer.validate();
      // We need these in the classes directory so we can load the dynamic stubs
      layer.compiled = true;
      return layer;
   }

   static void saveDeps(DependencyFile deps, File depFile, long buildTime) {
      try {
         depFile.delete();
         File depDir = depFile.getParentFile();
         depDir.mkdirs();
         DependenciesLanguage.INSTANCE.saveSemanticValue(deps, depFile);

         // Reset the deps file back to the start so we do not miss any changes made while
         // we were compiling on the next build.
         depFile.setLastModified(buildTime);
      }
      catch (IllegalArgumentException exc) {
         System.err.println("Unable to write dependencies file: " + exc);
      }
   }

   static boolean sameTypeGroupLists(List<String> prevTypeGroupMembers, List<TypeGroupMember> newTypeGroupMembers) {
      if (prevTypeGroupMembers == null && newTypeGroupMembers == null)
         return true;
      else if (prevTypeGroupMembers == null || newTypeGroupMembers == null)
         return false;
      int prevSz = prevTypeGroupMembers.size();
      int newSz = newTypeGroupMembers.size();
      if (prevSz != newSz)
         return false;

      for (int i = 0; i < newSz; i++) {
         String newTypeName = newTypeGroupMembers.get(i).typeName;
         if (!newTypeName.equals(prevTypeGroupMembers.get(i))) {
            // For some reason these are getting reordered
            if (prevTypeGroupMembers.indexOf(newTypeName) == -1)
               return false;
         }
      }
      return true;
   }

   static boolean anyTypesChanged(HashSet<String> changedTypes, List<String> typeNameList) {
      for (String typeName:typeNameList)
         if (changedTypes.contains(typeName))
            return true;
      return false;
   }

   // If we are dealing with a source file that's already built in a previous build layer, use that layer's "buildAllFiles" so we do the build-all one clump of files at a time.
   static boolean doIncrCompileForFile(SrcEntry srcEnt) {
      Layer prevSrcEntBuildLayer = srcEnt.layer.getNextBuildLayer();
      return !prevSrcEntBuildLayer.getBuildAllFiles();
   }

   public static void printVersion(String appName, String buildTagClassName) {
      BuildTag buildTag = (BuildTag) DynUtil.resolveName(buildTagClassName, true);
      if (buildTag != null) {
         System.out.println("   " + appName + " version: " + buildTag.getBuildTag());
      }
      else {
         System.out.println("   " + appName + " version: java build - no version available");
      }
   }

   private final static String modelCacheDirName = "modelCache";

   private static String getDefaultModelCacheDir() {
      return FileUtil.concat(LayerUtil.getDefaultHomeDir(), modelCacheDirName);
   }

   static String getModelCacheDir(LayeredSystem sys) {
      return sys.getStrataCodeHomeDir(modelCacheDirName);
   }

   private final static String deployedDBSchemasDirName = "deployedDBSchemas";

   public static String getDeployedDBSchemasDir(LayeredSystem sys) {
      return sys.getStrataCodeHomeDir(deployedDBSchemasDirName);
   }

   private static String getModelCacheBaseDir(LayeredSystem sys, Layer layer, SrcEntry srcEnt) {
      return FileUtil.concat(getModelCacheDir(sys), layer.getUnderscoreName(), srcEnt.getRelDir());
   }

   public static Object restoreModel(LayeredSystem sys, Language lang, SrcEntry srcEnt, long srcFileModTime) {
      Layer layer = srcEnt.layer;
      if (layer == null)
         return null;
      String cacheBaseDir = getModelCacheBaseDir(sys, layer, srcEnt);
      String baseName = FileUtil.removeExtension(srcEnt.baseFileName);
      String ext = FileUtil.getExtension(srcEnt.baseFileName);
      baseName = baseName + "_" + ext;
      String serFileName = FileUtil.concat(cacheBaseDir, FileUtil.addExtension(baseName, BinfConstants.ModelStreamSuffix));

      File serFile = new File(serFileName);
      long serLastModified = serFile.lastModified();
      if (serLastModified == 0) {
         return null;
      }

      // TODO: add length and/or a hash verification here for more accuracy?   Need to consider == as changed because of limited timestamp granularity (1 sec in HFS)
      if (serLastModified <= srcFileModTime) {
         if (sys.options.verboseModelCache)
            sys.info("Model cache changed: " + serFileName);
         return null;
      }

      PerfMon.start("deserializeModel");
      ISemanticNode deserModel = null;
      try {
         deserModel = ParseUtil.deserializeModel(serFileName, lang);
      }
      finally {
         PerfMon.end("deserializeModel");
      }

      lang.postProcessSemanticValue(deserModel, srcEnt.absFileName);

      return deserModel;
   }

   public static Object restoreParseNodes(LayeredSystem sys, Language lang, SrcEntry srcEnt, long srcFileModTime, ISemanticNode deserModel) {
      Layer layer = srcEnt.layer;
      if (layer == null)
         return null;

      String cacheBaseDir = getModelCacheBaseDir(sys, layer, srcEnt);
      String baseName = FileUtil.removeExtension(srcEnt.baseFileName);
      String ext = FileUtil.getExtension(srcEnt.baseFileName);
      baseName = baseName + "_" + ext;

      ParseInStream pIn = null;
      String parseFileName = FileUtil.concat(cacheBaseDir, FileUtil.addExtension(baseName, BinfConstants.ParseStreamSuffix));
      File parseFile = new File(parseFileName);
      long parseLastModified = parseFile.lastModified();
      if (parseLastModified != 0 && parseLastModified > srcFileModTime) {
         try {
            pIn = ParseUtil.openParseNodeStream(parseFileName, lang);
         }
         catch (UncheckedIOException exc) {
            if (sys.options.verbose || sys.options.verboseModelCache)
               sys.info("Error opening parseNodeStream: " + exc);
         }
      }

      PerfMon.start("restoreModel");
      Object restored = null;
      try {
         restored = lang.restore(srcEnt.absFileName, deserModel,  pIn, false);
      }
      catch (UncheckedIOException exc) {
         return restoreWithoutPNStream(lang, srcEnt, parseFile, parseFileName, parseLastModified, deserModel);
      }
      catch (IllegalArgumentException exc) {
         sys.info("Error restoring model with pn stream: " + srcEnt + " return: " + restored);
         try {
            return restoreWithoutPNStream(lang, srcEnt, parseFile, parseFileName, parseLastModified, deserModel);
         }
         catch (IllegalArgumentException iexc) {
            sys.info("Error restoring model without pn stream: " + srcEnt + " return: " + restored);
            return null;
         }
      }
      finally {
         PerfMon.end("restoreModel");
      }

      if (restored instanceof ParseError || restored == null) {
         if (sys.options.verbose || sys.options.verboseModelCache)
            sys.info("Error restoring model: " + srcEnt + " return: " + restored);
         return null;
      }
      return restored;
   }

   private static Object restoreWithoutPNStream(Language lang, SrcEntry srcEnt, File parseFile, String parseFileName, long parseLastModified, ISemanticNode deserModel) {
      Object restored = null;
      // Something is wrong with this parse node stream - maybe a bug or maybe our stream does not match the current file?
      System.err.println("*** Failed to restore parse node for file: " + parseFileName + " - last modified: " + new Date(parseLastModified) +
              " with src last modified: " + new Date(new File(srcEnt.absFileName).lastModified()) + " - removing parse file");
      parseFile.delete();
      // Restore the model without the parse node stream
      restored = lang.restore(srcEnt.absFileName, deserModel,  null, false);
      if (restored == null) {
         System.err.println("*** Failed to recover from a failed restore of parse node for file: " + parseFileName + " - last modified: " + new Date(parseLastModified) +
                 " with src last modified: " + new Date(new File(srcEnt.absFileName).lastModified()) + " - removing parse file");
      }
      return restored;
   }

   public static void saveModelCache(LayeredSystem sys, SrcEntry srcEnt, ILanguageModel model, Language lang) {
      Layer layer = srcEnt.layer;
      if (layer == null)
         return;
      String cacheBaseDir = getModelCacheBaseDir(sys, layer, srcEnt);
      String baseName = FileUtil.removeExtension(srcEnt.baseFileName);
      String ext = FileUtil.getExtension(srcEnt.baseFileName);
      baseName = baseName + "_" + ext;
      String serFileName = FileUtil.concat(cacheBaseDir, FileUtil.addExtension(baseName, BinfConstants.ModelStreamSuffix));

      File dir = new File(FileUtil.getParentPath(serFileName));
      dir.mkdirs();

      ParseUtil.serializeModel((ISemanticNode) model, serFileName, srcEnt.absFileName, lang);

      String parseFileName = FileUtil.concat(cacheBaseDir, FileUtil.addExtension(baseName, BinfConstants.ParseStreamSuffix));
      try {
         ParseUtil.serializeParseNode(model.getParseNode(), parseFileName, srcEnt.absFileName, lang);
      }
      catch (Exception exc) {
         System.err.println("*** Error trying to save parse node tree - removing model and parse files: " + serFileName + " and " + parseFileName);
         new File(serFileName).delete();
         new File(parseFileName).delete();
      }
   }

   public static void cleanModelCache() {
      FileUtil.removeDirectory(getDefaultModelCacheDir());
   }

   public static class LayeredSystemPtr {
      public LayeredSystem system;
      public LayeredSystemPtr(LayeredSystem sys) {
         this.system = sys;
      }
   }

   public static String getLayerJavaFileFromName(String layerName) {
      return layerName + FileUtil.FILE_SEPARATOR + LAYER_CLASS_NAME + ".java";
   }

   public static String getLayerClassFileDirectory(Layer layer, String layerName, boolean srcDir) {
      LayeredSystem sys = layer.layeredSystem;
      // This is a convenience option which eliminates the layer_name/build from the paths we generate for the final build layer
      if (sys.buildLayer == layer) {
          if (sys.options.buildLayerAbsDir != null)
             return sys.options.buildLayerAbsDir;
          // When we have a peer runtime that does not have the same final buildLayer as a child system, we still
          // want to use the same buildDir as the main system
          if (sys.peerMode) {
             // For the IDE the main system is just the first one and not necessarily the active one.
             LayeredSystem activeSys = sys.getActiveLayeredSystem(null);
             if (activeSys == null)
                activeSys = sys.getMainLayeredSystem();
             if (activeSys != sys && activeSys != null && activeSys.buildDir != null)
                return activeSys.buildDir;
          }
      }
      String sysBuildDir = srcDir && sys.options.buildSrcDir != null ?  sys.options.buildSrcDir : sys.options.buildDir;
      String mainLayerDir = sys.strataCodeMainDir;
      if (mainLayerDir == null) {
         mainLayerDir = System.getProperty("user.dir");
      }
      // If we use the -dyn option to selectively make layers dynamic, use a different build dir so that we can quickly switch back and forth between -dyn and not without rebuilding everything.
      String prefix = sysBuildDir == null ?  FileUtil.concat(mainLayerDir, sys.options.anyDynamicLayers ? "dynbuild" : "build") : sysBuildDir;
      prefix = FileUtil.concat(prefix, layer.getUnderscoreName());
      // TODO: remove this comment - this was a naive solution that caused headaches between JS marks all layers as compiled
      //return prefix + FileUtil.FILE_SEPARATOR + (layer.dynamic ? DYN_BUILD_DIRECTORY : BUILD_DIRECTORY);
      return prefix;
   }

   /** Takes the generated srcEnt */
   public static File getClassFile(Layer genLayer, SrcEntry srcEnt) {
      return new File(getClassFileName(genLayer, srcEnt));
   }

   public static String getClassFileName(Layer genLayer, SrcEntry srcEnt) {
      return FileUtil.concat(genLayer.buildClassesDir,  srcEnt.getRelDir(), FileUtil.replaceExtension(srcEnt.baseFileName, "class"));
   }

   public static String getClassFileFromJavaFile(String javaFileName) {
      File javaFile = new File(javaFileName);
      String name = javaFile.getName();
      int suffixIx = name.lastIndexOf('.');
      if (suffixIx == -1)
         throw new IllegalArgumentException("Java file has no suffix: " + javaFileName);
      return new File(javaFile.getParent(), name.substring(0, suffixIx) + ".class").getPath();
   }

   private static TreeSet<String> suppressedCompilerMessages = new TreeSet<String>();
   static {
      suppressedCompilerMessages.add("Note: Some input files use unchecked or unsafe operations.");
      suppressedCompilerMessages.add("Note: Recompile with -Xlint:unchecked for details.");
   }

   public static int compileJavaFilesInternal(Collection<SrcEntry> srcEnts, String buildDir, String classPath, boolean debug, String srcVersion, IMessageHandler messageHandler, Set<String> errorFiles) {
      JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
      if (compiler == null) {
         System.err.println("*** No internal java compiler found - Do you have the JDK installed and is tools.jar in the system classpath? - trying javac");
         return compileJavaFiles(srcEnts, buildDir, classPath, debug, srcVersion, messageHandler);
      }

      DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();

      StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null);

      List<File> filesToCompile = new ArrayList<File>(srcEnts.size());

      addFilesToCompile(srcEnts, buildDir, filesToCompile);

      if (filesToCompile.size() == 0) {
         return 0;
      }
      else {
         List<String> options = new ArrayList<String>();
         options.add("-d");
         options.add(buildDir);
         options.add("-cp");
         options.add(classPath);
         if (srcVersion != null) {
            options.add("-source");
            options.add(srcVersion);
         }
         if (debug)
            options.add("-g");
         //options.add("-target");
         //options.add("1.5");

         /*
         StringBuilder cmdDebug  = new StringBuilder();
         cmdDebug.append("javac " + options);
         cmdDebug.append(" ");
         cmdDebug.append(filesToCompile);
         cmdDebug.append("\n");
         System.out.println("\n" + cmdDebug);
         */

         Iterable<? extends JavaFileObject> fileObjectsToCompile = fileManager.getJavaFileObjectsFromFiles(filesToCompile);

         boolean result = compiler.getTask(null, fileManager, diagnostics, options, null, fileObjectsToCompile).call();

         for (Diagnostic diagnostic : diagnostics.getDiagnostics()) {
            java.io.PrintStream printer = result ? System.out : System.err;
            String message = diagnostic.getMessage(Locale.getDefault());
            if (!result && !suppressedCompilerMessages.contains(message)) {
               long lineNumber = diagnostic.getLineNumber();
               long column = diagnostic.getColumnNumber();
               Object source = diagnostic.getSource();
               if (source instanceof FileObject) {
                  Object name = ((FileObject) source).getName();
                  if (name != null)
                     source = name;
               }
               else if (source == null)
                  source = "";
               String errorFile = source.toString();
               errorFiles.add(errorFile);
               if (messageHandler != null) {
                  MessageType type = MessageType.Error;
                  switch (diagnostic.getKind()) {
                     case ERROR:
                        type = MessageType.Error;
                        break;
                     case WARNING:
                        type = MessageType.Warning;
                        break;
                  }
                  messageHandler.reportMessage(message, "file://" + errorFile, (int) lineNumber, (int) column, type);
               }
               if (lineNumber != -1)
                  printer.println(source.toString() + ": line: " + lineNumber + " column: " + column + ": " + message);
               else
                  printer.println(message);

            }
            printer.flush();
         }

         try {
            fileManager.close();
         }
         catch (IOException exc) {
            System.err.println("**** error closing file manager");
         }
         if (result) return 0;
      }
      return -1;
   }

   private static void addFilesToCompile(Collection<SrcEntry> srcEnts, String buildDir, List<File> args) {
      for (SrcEntry src:srcEnts) {
         File classFile = new File(buildDir,
                 FileUtil.concat(src.relFileName,  FileUtil.replaceExtension(src.baseFileName, "class")));
         File srcFile = new File(src.absFileName);
         if (!classFile.exists() || classFile.lastModified() < srcFile.lastModified())
            args.add(srcFile);
      }
   }

   private static void addFileNamesToCompile(Collection<SrcEntry> srcEnts, String buildDir, List<String> args) {
      for (SrcEntry src:srcEnts) {
         File classFile = new File(buildDir,
                 FileUtil.concat(src.relFileName,  FileUtil.replaceExtension(src.baseFileName, "class")));
         File srcFile = new File(src.absFileName);
         if (!classFile.exists() || classFile.lastModified() < srcFile.lastModified())
            args.add(src.absFileName);
      }
   }
   
   public static int compileJavaFiles(Collection<SrcEntry> srcEnts, String buildDir, String classPath, boolean debug, String srcVersion, IMessageHandler handler) {
      if (srcEnts.size() > 0) {
         List<String> args = new ArrayList<String>(srcEnts.size() + 5);
         args.add("javac");

         addFileNamesToCompile(srcEnts, buildDir, args);

         args.add("-d");
         args.add(buildDir);
         args.add("-cp");
         args.add(classPath);
         if (srcVersion != null) {
            args.add("-source");
            args.add(srcVersion);
         }
         if (debug)
            args.add("-g");
         // args.add("-target");
         // args.add("1.5");

         try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);

            System.out.println("*** Starting compile: " + StringUtil.argsToString(args));

            Process p = pb.start();

            BufferedInputStream bis = new BufferedInputStream(p.getInputStream());
            byte [] buf = new byte[1024];
            int len;
            while ((len = bis.read(buf, 0, buf.length)) != -1)
               System.out.write(buf, 0, len);
            int stat = p.waitFor();
            System.out.println("*** Compile process exited with: " + stat);
            return stat;
         }
         catch (InterruptedException exc)
         {
            System.err.println("*** compile of: " + args.toString() + " - wait interrupted: " + exc);
         }
         catch (IOException exc)
         {
            System.err.println("*** compile of: " + args.toString() + " failed: " + exc);
         }
         return -1;
      }
      else {
         return 0;
      }
   }

   public static String getLayerSrcDirectory(String layerName) {
      return layerName;
   }

   /**
    * Takes the list of generated srcEntries in the model and produces the list of files they represent
    * Generated files are put under the package prefix whereas regular source files are not.
    */
   public static List<IString> getRelFileNamesFromSrcEntries(List<SrcEntry> genFiles, boolean generated) {
      int size = genFiles.size();
      List<IString> l = new SemanticNodeList<IString>(size);
      for (int i = 0; i < size; i++) {
         SrcEntry srcEnt = genFiles.get(i);
         String relFileName = srcEnt.relFileName;
         if (generated)
            relFileName = FileUtil.concat(srcEnt.layer.getPackagePath(), relFileName);
         l.add(PString.toIString(FileUtil.normalize(relFileName)));
      }
      return l;
   }

   // TODO: Write a FilenameFilter which uses the logic in the IFileProcessor's to match class and source files.  We might need a new flag in there when the default is not accurate but we already know whether a file goes into the buildSrc or buildClasses folders.
   // Note: ft and html are here for the sc4idea plugin which uses those extensions to load resources from the classpath for the file templates.
   public static final FilenameFilter CLASSES_JAR_FILTER = new ExtensionFilenameFilter(Arrays.asList(new String[]{"class", "properties", "sctp", "sctd", "sctdynt", "xml", "jpg", "gif", "png", "ft", "html", "dll"}), true);
   public static final FilenameFilter SRC_JAR_FILTER = new ExtensionFilenameFilter(Arrays.asList(new String[]{"java", "properties", "sctp", "sctd", "sctdynt", "xml"}), true);

   /**
    * Before creating a Jar file, we go through and order the files by directory name and include an entry for each directory.
    * For some reason, this makes a subtle difference in how IntelliJ processes Jar files from plugins - it won't recognize the file templates in the jar unless
    * we have things ordered this way - with an explicit entry for the folder.   There may be a specific check for that folder in the zip file?
    */
   private static List<String> folderizeFileList(List<String> allClassFiles) {
      int ndirs = 1;
      Map<String, Object> dirList = new LinkedHashMap<String, Object>();
      for (String fileName:allClassFiles) {
         Map<String, Object> curDir = dirList;

         String fileRest = fileName;
         int slashIx;
         do {
            slashIx = fileRest.indexOf(FileUtil.FILE_SEPARATOR);
            if (slashIx != -1) {
               String dirName = fileRest.substring(0, slashIx);
               Object dirEnt = curDir.get(dirName);
               if (dirEnt instanceof String)
                  throw new IllegalArgumentException("File and directory with same name!" + dirEnt);
               Map<String,Object> nextDir = (Map<String,Object>) dirEnt;
               if (nextDir == null) {
                  nextDir = new LinkedHashMap<String,Object>();
                  ndirs++;
                  curDir.put(dirName, nextDir);
               }
               curDir = nextDir;
               fileRest = fileRest.substring(slashIx+1);
            }
         } while (slashIx != -1 && fileRest.length() > 0);

         if (fileRest.length() > 0) {
            curDir.put(fileRest, fileName);
         }
      }
      ArrayList<String> resFiles = new ArrayList<String>(allClassFiles.size() + ndirs);
      addFilesToSubList(resFiles, dirList, "");
      return resFiles;
   }

   private static void addFilesToSubList(ArrayList<String> resFiles, Map<String,Object> dirList, String curPath) {
      for (Map.Entry<String,Object> fileEnt:dirList.entrySet()) {
         String fileName = fileEnt.getKey();
         Object fileValue = fileEnt.getValue();
         if (fileValue instanceof String)
            resFiles.add((String) fileValue);
         else {
            String dirPath = FileUtil.concat(curPath, fileName);
            //resFiles.add(dirPath); do not include the directory itself
            Map<String,Object> dirEnt = (Map<String,Object>) fileValue;
            addFilesToSubList(resFiles, dirEnt, dirPath);
         }
      }
   }

   /**
    * Builds a jar file according to the arguments.
    * buildDir is the main build dir (or null if all class jars/dirs are in the mergePath). Prefix is appended onto the buildDir to find the class files (e.g. java or js)
    * The array of packages restricts the class files copied into the jar.
    * The mergePath is an optional classPath of directories or jar files to include in the merge (last one wins).  The jarFilter restricts
    * the files that are put into it (usually by suffix using ExtensionFilter).
    */
   public static int buildJarFile(String buildDir, String prefix, String jarName, String mainTypeName, String[] pkgs, String classPath, String mergePath, FilenameFilter jarFilter, boolean verbose) {
      List<String> args = null;
      File manifestTmp = null;

      if (buildDir == null && mergePath == null)
         throw new IllegalArgumentException("Must specify one main buildDir or mergePath argument");

      String classDir = buildDir == null ? null : FileUtil.concat(buildDir, prefix);

      // First make directory the file goes in
      String jarDir = FileUtil.getParentPath(jarName);
      File jarDirFile = new File(jarDir);
      jarDirFile.mkdirs();
      StringBuilder jarArgsDesc = new StringBuilder();
      File zipTemp = null;
      File zipStageDir = null;

      try {
         List<String> allClassFiles = null;
         if (mergePath != null && mergePath.trim().length() > 0) {
            String[] mergeDirs = mergePath.split(FileUtil.PATH_SEPARATOR);
            zipTemp = createTempDirectory("scJarPkg");
            for (String mergeDir:mergeDirs) {
               if (mergeDir.trim().length() == 0)
                  continue;
               if (jarArgsDesc.length() > 0)
                  jarArgsDesc.append(", ");
               File mergeFile = new File(mergeDir);
               if (!mergeFile.isDirectory() && mergeFile.canRead()) {
                  ArrayList<String> unjarArgs = new ArrayList<String>();
                  unjarArgs.add("jar");
                  unjarArgs.add("xf");
                  unjarArgs.add(mergeDir);
                  if (verbose) {
                     System.out.println("Running: " + StringUtil.arrayToCommand(unjarArgs.toArray()));
                  }
                  StringBuilder errors = new StringBuilder();
                  if (FileUtil.execCommand(zipTemp.getPath(), unjarArgs, "", 0, verbose, errors) == null)
                     System.err.println("*** Failed to unjar with " + unjarArgs + " error output: " + errors);
                  mergeFile = zipTemp;

                  jarArgsDesc.append("jar: ");
                  jarArgsDesc.append(mergeDir);
               }
               else if (mergeFile.isDirectory()) {
                  if (verbose) {
                     System.out.println("Copying class files from: " + mergeFile.getPath() + " to: " + zipTemp.getPath());
                  }
                  FileUtil.copyAllFiles(mergeFile.getPath(), zipTemp.getPath(), true, jarFilter);
                  jarArgsDesc.append("classes: ");
                  jarArgsDesc.append(mergeDir);
               }
            }
         }
         // When we are merging from multiple sources we use a temp directory
         if (zipTemp != null) {
            // Make this absolute since we are now running this from a different directory
            String zipPath = zipTemp.getPath();
            try {
               if (classDir != null)
                  FileUtil.copyAllFiles(classDir, zipPath, true, jarFilter);
            }
            catch (RuntimeException exc) {
               System.err.println("*** Failed to copy classes while merging jars from: " + classDir + " with: " + zipPath + " due to : " + exc);
               exc.printStackTrace();
               return -1;
            }
            classDir = zipPath;
         }
         // If the user specified a specific list of packages only process those packages
         if (jarArgsDesc.length() > 0)
            jarArgsDesc.append(", ");
         if (pkgs != null) {
            if (allClassFiles == null)
               allClassFiles = new ArrayList<String>();
            for (String pkg:pkgs) {
               List<String> newFiles = FileUtil.getRecursiveFiles(classDir, FileUtil.concat(pkg.replace(".", FileUtil.FILE_SEPARATOR)), jarFilter);
               allClassFiles.addAll(newFiles);
            }
            jarArgsDesc.append("packages: " + Arrays.asList(pkgs));
         }
         else {
            allClassFiles = FileUtil.getRecursiveFiles(classDir, null, jarFilter);
            jarArgsDesc.append(allClassFiles.size() + " class files");
         }
         String opts;

         allClassFiles = folderizeFileList(allClassFiles);

         zipStageDir = createTempDirectory("scJarStage");
         for (String classFileName:allClassFiles) {
            String fromFile = FileUtil.concat(classDir, classFileName);
            String toFile = FileUtil.concat(zipStageDir.getPath(), classFileName);
            FileUtil.copyFile(fromFile, toFile, true);
         }

         if (mainTypeName != null || classPath != null) {
            StringBuilder manifestChunk = new StringBuilder();
            if (mainTypeName != null) {
               manifestChunk.append("Main-Class: ");
               manifestChunk.append(mainTypeName);
               manifestChunk.append(FileUtil.LINE_SEPARATOR);
            }
            if (classPath != null) {
               manifestChunk.append("Class-Path: ");
               manifestChunk.append(FileUtil.LINE_SEPARATOR);
               String [] classPathEnts = StringUtil.split(classPath, FileUtil.PATH_SEPARATOR_CHAR);
               for (int i = 0; i < classPathEnts.length; i++)
                  manifestChunk.append(" " + classPathEnts[i] + FileUtil.LINE_SEPARATOR);
               manifestChunk.append(FileUtil.LINE_SEPARATOR);
            }
            manifestTmp = File.createTempFile("manifestTmp", "tmp");
            FileUtil.saveStringAsFile(manifestTmp, manifestChunk.toString(), false);
            opts = "-cfm";
         }
         else
            opts = "-cf";

         args = new ArrayList<String>(allClassFiles.size() + 5);
         args.add("jar");

         args.add(opts);
         args.add(jarName);
         if (manifestTmp != null)
            args.add(manifestTmp.toString());
         //args.addAll(allClassFiles);
         args.add(".");

         ProcessBuilder pb = new ProcessBuilder(args);
         //pb.directory(new File(classDir));
         pb.directory(zipStageDir);
         pb.redirectErrorStream(true);

         if (verbose)
            System.out.println("Packaging: " + jarName + " with " + jarArgsDesc);

         Process p = pb.start();

         BufferedInputStream bis = new BufferedInputStream(p.getInputStream());
         byte [] buf = new byte[1024];
         int len;
         while ((len = bis.read(buf, 0, buf.length)) != -1)
            System.out.write(buf, 0, len);
         int stat = p.waitFor();

         if (zipTemp != null)
            FileUtil.removeDirectory(zipTemp.getPath());

         FileUtil.removeDirectory(zipStageDir.getPath());

         return stat;
      }
      catch (InterruptedException exc) {
         if (args != null)
            System.err.println("*** package of: " + args.toString() + " - wait interrupted: " + exc);
         else
            System.err.println("*** can't list class files: " + buildDir);
      }
      catch (IOException exc) {
         if (args != null)
            System.err.println("*** package of: " + args.toString() + " failed: " + exc);
         else
            System.err.println("*** can't list class files: " + buildDir);
      }
      finally {
         if (manifestTmp != null)
            manifestTmp.delete();
      }
      return -1;
   }

   static Random fileRandom = new Random();

   public static File createTempDirectory(String baseName) {
      File tempDir = new File(FileUtil.concat(System.getProperty("java.io.tmpdir"), baseName + fileRandom.nextInt(999999999)));
      tempDir.mkdir();
      return tempDir;
   }

   public static int execCommand(ProcessBuilder cmd, String dir, String inputFile, String outputFile) {
      if (dir != null)
         cmd.directory(new File(dir));

      PrintStream outStream = outputFile == null ? System.out : System.err;
      if (inputFile != null)
         cmd.redirectInput(new File(inputFile));
      if (outputFile != null)
         cmd.redirectOutput(new File(outputFile));

      try {
         if (outputFile == null)
            cmd.redirectErrorStream(true);

         Process p = cmd.start();

         BufferedInputStream bis = new BufferedInputStream(outputFile == null ? p.getInputStream() : p.getErrorStream());
         byte [] buf = new byte[1024];
         int len;
         while ((len = bis.read(buf, 0, buf.length)) != -1) {
            outStream.write(buf, 0, len);
         }
         int stat = p.waitFor();
         return stat;
      }
      catch (InterruptedException exc) {
         if (cmd.command() != null)
            System.err.println("*** exec cmd of: " + StringUtil.arrayToCommand(cmd.command().toArray()) + " inputFile: " + inputFile + " outputFile: " + outputFile + " - wait interrupted: " + exc);
      }
      catch (IOException exc) {
         if (cmd.command() != null)
            System.err.println("*** exec cmd of: " + StringUtil.arrayToCommand(cmd.command().toArray()) + " inputFile: " + inputFile + " outputFile: " + outputFile + " failed: " + exc);
      }
      return -1;
   }

   public static AsyncProcessHandle execAsync(ProcessBuilder cmd, String dir, String inputFile, String outputFile) {
      if (dir != null)
         cmd.directory(new File(dir));

      if (inputFile != null)
         cmd.redirectInput(new File(inputFile));
      if (outputFile != null) {
         cmd.redirectError(new File(outputFile + ".err"));
         cmd.redirectOutput(new File(outputFile));
      }

      try {
         Process p = cmd.start();

         InputStream in = p.getInputStream();
         InputStream err = p.getErrorStream();

         return new AsyncProcessHandle(p);
      }
      catch (IOException exc) {
         if (cmd.command() != null)
            System.err.println("*** execAsync: " + StringUtil.arrayToCommand(cmd.command().toArray()) + " inputFile: " + inputFile + " outputFile: " + outputFile + " failed: " + exc);
      }
      return null;
   }

   public static boolean isLayerDir(String dirName) {
      File dir = new File(dirName);
      if (dir.isDirectory()) {
         String filePart = FileUtil.getFileName(dirName);
         File layerDefFile = new File(FileUtil.concat(dirName, filePart + ".sc"));
         if (layerDefFile.canRead())
            return true;
      }
      return false;
   }

   public static String getNewLayerDefinitionFileName(LayeredSystem sys, String layerName) {
      String res = fixLayerPathName(layerName);
      String pathName = FileUtil.concat(sys.getNewLayerDir(), res);
      String baseName = FileUtil.getFileName(pathName) + "." + SCLanguage.STRATACODE_SUFFIX;
      return FileUtil.concat(pathName, baseName);
   }

   public static boolean execCommands(List<ProcessBuilder> cmds, String directory, boolean info) {
      for (ProcessBuilder b:cmds) {
         if (info) {
            String redirStr = "";
            ProcessBuilder.Redirect redirIn, redirOut;
            if ((redirIn = b.redirectInput()) != ProcessBuilder.Redirect.PIPE)
               redirStr = " < " + redirIn.file();
            if ((redirOut = b.redirectOutput()) != ProcessBuilder.Redirect.PIPE)
               redirStr += (b.redirectErrorStream() ? " &> " : " > ") + redirOut.file().getPath();
            System.out.println("Executing: '" + StringUtil.arrayToCommand(b.command().toArray()) + redirStr + "' in: " + directory);
         }
         if (execCommand(b, directory, null, null) != 0) {
            System.err.println("*** Exec of: " + StringUtil.arrayToCommand(b.command().toArray()) + " failed");
            return false;
         }
      }
      return true;
   }

   public static String getLayerTypeName(String layerPathName) {
      return layerPathName.replace(FileUtil.FILE_SEPARATOR, ".").replace("/", ".");
   }

   public static String fixLayerPathName(String layerPathName) {
      StringBuilder sb = new StringBuilder();
      int len = layerPathName.length();
      boolean relPath = true;
      for (int i = 0; i < len; i++) {
         char c = layerPathName.charAt(i);
         if (c == '.') {
            if (!relPath)
               sb.append(FileUtil.FILE_SEPARATOR);
            else
               sb.append(".");
         }
         else if (c == '/') {
            sb.append(FileUtil.FILE_SEPARATOR);
         }
         else {
            relPath = false;
            sb.append(c);
         }
      }
      return sb.toString();
   }

   private static void appendLayersString(int begin, int end, List<Layer> layers, StringBuilder sb) {
      for (int i = begin; i < end; i++) {
         Layer l = layers.get(i);
         sb.append("      ");
         boolean hidden = !l.getVisibleInEditor();
         sb.append(l);
         l.appendDetailString(sb, true, true, true, true);
         sb.append("\n");
      }
   }

   public static boolean opAppend(StringBuilder sb, String opt, boolean first) {
      if (first)
         sb.append(":");
      sb.append(" ");
      sb.append(opt);
      return false;
   }

   public static String layersToNewlineString(List<Layer> layers) {
      int dynamicIx = -1;
      for (int i = 0; i < layers.size(); i++) {
         if (layers.get(i).dynamic) {
            dynamicIx = i;
            break;
         }
      }

      StringBuilder sb = new StringBuilder();
      if (dynamicIx != -1) {
         sb.append("\n   layers to compile:\n");
         appendLayersString(0, dynamicIx, layers, sb);
         sb.append("   dynamic layers:\n");
         appendLayersString(dynamicIx, layers.size(), layers, sb);
      }
      else {
         sb.append("\n   no dynamic layers:\n");
         appendLayersString(0, layers.size(), layers, sb);
      }
      return sb.toString();
   }

   public static String layersToString(List<Layer> layers) {
      int dynamicIx = -1;
      for (int i = 0; i < layers.size(); i++) {
         if (layers.get(i).dynamic) {
            dynamicIx = i;
            break;
         }
      }

      StringBuilder sb = new StringBuilder();
      if (dynamicIx != -1) {
         sb.append("layers: ");
         sb.append(layers.subList(0, dynamicIx));
         sb.append(" dynamic: ");
         sb.append(layers.subList(dynamicIx, layers.size()));
      }
      else {
         sb.append("layers: ");
         sb.append(layers.toString());
      }
      return sb.toString();
   }

   /** Strips off base name for both . and / */
   public static String getLayerBaseName(String layerName) {
      return CTypeUtil.getPackageName(FileUtil.getFileName(layerName));
   }

   public static void removeInheritFile(String fileName) {
      new File(FileUtil.replaceExtension(fileName, Language.INHERIT_SUFFIX)).delete();
   }

   public static void removeFileAndClasses(String fileName) {
      File theFile = new File(fileName);
      theFile.delete();
      new File(FileUtil.replaceExtension(fileName, "class")).delete();
      new File(FileUtil.replaceExtension(fileName, ReverseDependencies.REVERSE_DEPENDENCIES_EXTENSION)).delete();
      File dir = theFile.getParentFile();
      String filePart = FileUtil.removeExtension(theFile.getName());
      String[] innerFiles = dir.list(new PatternFilenameFilter(Pattern.compile(filePart + "\\$.*\\.class")));
      if (innerFiles != null) {
         for (String innerFile:innerFiles)
            new File(FileUtil.concat(dir.getPath()), innerFile).delete();
      }
   }

   public static void addLibraryPath(String pathToAdd) throws Exception{
      final Field usrPathsField = ClassLoader.class.getDeclaredField("usr_paths");
      if (usrPathsField == null) {
         System.err.println("*** JVM change: failed to add library path directory: " + pathToAdd);
         return;
      }

       usrPathsField.setAccessible(true);

       //get array of paths
       final String[] paths = (String[])usrPathsField.get(null);

       //check if the path to add is already present
       for(String path : paths) {
           if(path.equals(pathToAdd)) {
               return;
           }
       }

       //add the new path
       final String[] newPaths = Arrays.copyOf(paths, paths.length + 1);
       newPaths[newPaths.length-1] = pathToAdd;
       usrPathsField.set(null, newPaths);
   }

   public static String errorsToString(LinkedHashSet<String> viewedErrors) {
      StringBuilder sb = new StringBuilder();
      for (String error:viewedErrors) {
         sb.append(error);
         sb.append(FileUtil.LINE_SEPARATOR);
      }
      return sb.toString();
   }

   public static void reportMessageToHandler(IMessageHandler errorHandler, String error, SrcEntry srcEnt, ISemanticNode node, MessageType type) {
      int line = -1;
      int col = -1;
      IParseNode parseNode = node.getParseNode();
      if (parseNode != null && srcEnt != null) {
         int startIx = parseNode.getStartIndex();
         if (startIx != -1) {
            FilePosition pos = ParseUtil.charOffsetToLinePos(new File(srcEnt.absFileName), startIx);
            if (pos != null) {
               line = pos.lineNum;
               col = pos.colNum;
            }
         }
      }
      errorHandler.reportMessage(error, srcEnt == null ? null : srcEnt.getJarUrl(), line, col, type);
   }

   public static String installLayerBundle(String projectDir, IMessageHandler handler, boolean verbose, String gitURL) {
      RepositorySystem sys = new RepositorySystem(new RepositoryStore(projectDir), handler, null, verbose, false, false, false);
      sys.pkgIndexRoot = FileUtil.concat(projectDir, "idx", "pkgIndex");
      IRepositoryManager mgr = sys.getRepositoryManager("git");
      String fileName = FileUtil.concat("bundles", FileUtil.removeExtension(URLUtil.getFileName(gitURL))); // Remove the '.git' suffix and take the last name as the file name.
      // Just install this package into the packageRoot - don't add the packageName like we do for most packages
      RepositoryPackage pkg = new RepositoryPackage(mgr, fileName, null, new RepositorySource(mgr, gitURL, false, null), null);
      String err = pkg.install(null);
      return err;
   }

   public static String installDefaultLayers(String resultDir, IMessageHandler handler, boolean verbose, String gitURL) {
      RepositorySystem sys = new RepositorySystem(new RepositoryStore(resultDir), handler, null, verbose, false, false, false);
      IRepositoryManager mgr = sys.getRepositoryManager("git");
      String fileName = gitURL == null ? LayerConstants.DEFAULT_LAYERS_PATH: FileUtil.removeExtension(URLUtil.getFileName(gitURL)); // Remove the '.git' suffix and take the last name as the file name.
      // Just install this package into the packageRoot - don't add the packageName like we do for most packages
      RepositoryPackage pkg = new RepositoryPackage(mgr, fileName, null, new RepositorySource(mgr, gitURL == null ? LayerConstants.DEFAULT_LAYERS_URL : gitURL, false, null), null);
      //RepositoryPackage pkg = new RepositoryPackage("layers", new RepositorySource(mgr, "ssh://vsgit@stratacode.com/home/git/vs/layers", false));
      String err = pkg.install(null);
      return err;
   }


   public static void addQuotedPath(StringBuilder sb, String path) {
      sb.append(FileUtil.PATH_SEPARATOR);
      if (path.indexOf(' ') != -1) {
         // this actually breaks the compile if there are spaces in it.
         //sb.append('"');
         sb.append(path);
         //sb.append('"');
      }
      else
         sb.append(path);
   }

   public static String appendSlashIfNecessary(String dir) {
      File f = new File(dir);
      if (f.isDirectory()) {
         if (!dir.endsWith(FileUtil.FILE_SEPARATOR))
            return dir + FileUtil.FILE_SEPARATOR;
      }
      return dir;
   }

   public static String getDefaultHomeDir() {
      String homeDir = System.getProperty("user.home");
      if (homeDir != null)
         return FileUtil.concat(homeDir, LayerConstants.SC_DIR);
      return null;
   }

   public static String incrBuildNumber(String appName) {
      File buildNumberPropsFile = new File(FileUtil.concat(getDefaultHomeDir(), "buildNum.properties"));
      Properties bnProps = new Properties();
      if (buildNumberPropsFile.canRead()) {
         FileReader reader = null;
         try {
            bnProps.load(reader = new FileReader(buildNumberPropsFile));
         }
         catch (IOException exc) {
            System.err.println("*** Failed to read existing buildNum.properties: " + exc);
         }
         finally {
            if (reader != null) {
               try { reader.close(); } catch (IOException exc) {}
            }
         }
      }
      String oldBn = bnProps.getProperty(appName);
      int buildNumber;
      if (oldBn == null) {
         bnProps.setProperty(appName, "1");
         buildNumber = 1;
      }
      else {
         try {
            buildNumber = Integer.parseInt(oldBn);
         }
         catch (NumberFormatException exc) {
            System.err.println("*** Failed to parse buildNumber: " + oldBn + " as integer in file: " + buildNumberPropsFile + ": " + exc + " resetting buildNumber to 1");
            buildNumber = 1;
         }
      }
      // Store the next build number - this is used by makeSCC as well to determine the buildDir (although maybe we should move that logic into the system?)
      int nextBuildNumber = buildNumber + 1;
      bnProps.setProperty(appName, String.valueOf(nextBuildNumber));

      FileWriter writer = null;
      try {
         writer = new FileWriter(buildNumberPropsFile);
         bnProps.store(writer, "");
      }
      catch (IOException exc) {
         System.err.println("*** Failed to save buildNumber: " + oldBn + " as integer in file: " + buildNumberPropsFile + ": " + exc + " resetting buildNumber to 1");
      }
      finally {
         if (writer != null) {
            try { writer.close(); } catch (IOException exc) {}
         }
      }
      return String.valueOf(buildNumber);
   }

   public static class DirectoryFilter implements FilenameFilter {
      public DirectoryFilter() {
      }

      public boolean accept(File dir, String fileInDir) {
         return new File(dir, fileInDir).isDirectory();
      }
   }

   public static void saveTypeToFixedTypeFile(String fileName, Object instance, String typeName) {
      String packageName = CTypeUtil.getPackageName(typeName);
      String baseName = CTypeUtil.getClassName(typeName);
      SCModel model = new SCModel();
      model.addSrcFile(new SrcEntry(null, fileName, packageName.replace('.',FileUtil.FILE_SEPARATOR_CHAR)));
      ModifyDeclaration modDecl = new ModifyDeclaration();
      modDecl.typeName = baseName;
      model.setProperty("types", new SemanticNodeList(1));
      model.types.add(modDecl);

      IBeanMapper[] props = RTypeUtil.getPersistProperties(instance.getClass());

      for (int i = 0; i < props.length; i++) {
         IBeanMapper prop = props[i];
         String pname = prop.getPropertyName();
         Object value = TypeUtil.getPropertyValue(instance, pname);
         if (value != null) {
            Expression expr = Expression.createFromValue(value, true);
            modDecl.addBodyStatement(PropertyAssignment.create(pname, expr, null));
         }
      }
      Language l = Language.getLanguageByExtension(FileUtil.getExtension(fileName));
      Object res = l.generate(model, false);
      if (res instanceof GenerateError) {
         System.err.println("*** Error saving type: " + typeName + " :" + res);
      }
      else {
         String genResult = res.toString();
         FileUtil.saveStringAsFile(fileName, genResult, true);
      }
   }

   public static StringBuilder dumpModelIndexStats(Map<String,ILanguageModel> modelIndex) {
      StringBuilder sb = new StringBuilder();

      int ct = 0;
      for (Map.Entry<String,ILanguageModel> ent: modelIndex.entrySet()) {
         if ((ct % 5) == 0)
            sb.append("\n      ");
         else
            sb.append(", ");
         ILanguageModel m = ent.getValue();
         TypeDeclaration mtype = m.getModelTypeDeclaration();
         if (mtype == null)
            sb.append("no model");
         else {
            sb.append(mtype.getTypeName());
            IParseNode pn = m.getParseNode();
            sb.append("(sn=" + m.getNodeCount() + ", pn=" + (pn == null ? "<null-pn>" : pn.getNodeCount()) + ")");
         }
         ct++;
      }
      if (ct == 0)
         sb.append("      <no models>");
      sb.append("\n\n");
      return sb;
   }

   public static String dumpModelIndexSummary(Map<String,ILanguageModel> modelIndex) {
      int snCt = 0, pnCt = 0;
      for (Map.Entry<String,ILanguageModel> ent: modelIndex.entrySet()) {
         ILanguageModel m = ent.getValue();
         TypeDeclaration mtype = m.getModelTypeDeclaration();
         if (mtype != null) {
            IParseNode pn = m.getParseNode();
            snCt += m.getNodeCount();
            if (pn != null)
               pnCt += pn.getNodeCount();
         }
      }
      return "(sn=" + snCt + ", pn=" + pnCt + ")";
   }

   public static StringBuilder dumpLayerListStats(String layerListType, List<Layer> layers) {
      StringBuilder sb = new StringBuilder();
      sb.append("      " + layerListType + " layers:\n");
      for (Layer l:layers) {
         sb.append("         " + l.layerDirName + ":");
         int ct = 0;
         for (IdentityWrapper<ILanguageModel> wrap:l.layerModels) {
            if ((ct % 5) == 0)
               sb.append("\n            ");
            else
               sb.append(", ");
            if (wrap.wrapped instanceof JavaModel) {
               JavaModel m = (JavaModel) wrap.wrapped;
               sb.append(m.getModelTypeName() + " (sn=" + m.getNodeCount() + ", pn=" + m.getParseNode().getNodeCount() + ")");
            }
            ct++;
         }
         sb.append("\n");
      }
      return sb;
   }

   public static String cleanBuildVersion(LayeredSystem sys, String path) {
      if (!sys.options.testMode)
         return path;
      int ix = path.indexOf("/dev/");
      if (ix != -1) {
         int startRem = ix + "/dev/".length();
         int endRemIx = path.indexOf("/", startRem+1);
         if (endRemIx != -1) {
            endRemIx = path.indexOf("/", endRemIx+1);
         }
         return path.substring(0, startRem) + "{buildVersion}" + path.substring(endRemIx);
      }
      return path;
   }

   static void initBuiltRuntimesFile(LayeredSystem sys) {
      StringBuilder rtSB = new StringBuilder();
      rtSB.append(sys.getProcessIdent());
      if (sys.peerSystems != null) {
         for (int i = 0; i < sys.peerSystems.size(); i++) {
            rtSB.append(",");
            rtSB.append(sys.peerSystems.get(i));
         }
      }

      String rtString = rtSB.toString();

      String builtStatusFile = FileUtil.concat(sys.buildDir, LayeredSystem.PROCESSES_FILE);

      String oldRtString = new File(builtStatusFile).canRead() ? FileUtil.getFileAsString(builtStatusFile) : null;
      if (oldRtString != null) {
         oldRtString = oldRtString.trim();
         if (!oldRtString.equals(rtString)) {
            List<String> toRem = new ArrayList<String>();
            String[] oldProcNames = StringUtil.split(oldRtString,',');
            for (String oldProcessId:oldProcNames) {
               if (sys.getProcessIdent().equals(oldProcessId) || sys.getPeerLayeredSystem(oldProcessId) != null)
                  continue;
               toRem.add(oldProcessId);
            }

            List<String> newProcs = new ArrayList<String>();
            List<String> oldProcs = Arrays.asList(oldProcNames);
            if (!oldProcs.contains(sys.getProcessIdent()))
               newProcs.add(sys.getProcessIdent());
            if (sys.peerSystems != null) {
               for (int i = 0; i < sys.peerSystems.size(); i++) {
                  String oldIdent = sys.peerSystems.get(i).getProcessIdent();
                  if (!oldProcs.contains(oldIdent))
                     newProcs.add(oldIdent);
               }
            }

            if (newProcs.size() > 0 && oldProcs.size() > 0) {
               sys.verbose("Process list changed for buildDir from: " + oldRtString + " to: " + rtString);
            }
            else if (newProcs.size() > 0)
               sys.verbose("New processes added for buildDir: " + newProcs);
            else if (oldProcs.size() > 0) {
               sys.verbose("Old processes removed for buildDir: " + oldProcs);
            }
            if (oldProcs.size() > 0)
               cleanOldProcessBuildSrcDirs(sys, oldProcs);
            sys.options.buildAllFiles = true;
         }

      }

      FileUtil.saveStringAsFile(builtStatusFile,  rtString + FileUtil.LINE_SEPARATOR, true);
   }

   static void cleanOldProcessBuildSrcDirs(LayeredSystem sys, List<String> oldProcIdents) {
      for (int i = 0; i < oldProcIdents.size(); i++) {
         String oldProcIdent = oldProcIdents.get(i);

         Layer buildLayer = sys.buildLayer;

         String rtPrefix = oldProcIdent;
         int rtIx = rtPrefix.indexOf('_');
         if (rtIx != -1) {
            rtPrefix = oldProcIdent.substring(0, rtIx);
         }

         String buildSrcDir = FileUtil.concat(LayerUtil.getLayerClassFileDirectory(buildLayer, buildLayer.layerPathName, true), rtPrefix, buildLayer.getBuildSrcSubDir());

         if (buildSrcDir != null) {
            File buildSrcFile = new File(buildSrcDir);
            if (buildSrcFile.isDirectory()) {
               sys.verbose("Removing old buildSrcDir for process: " + oldProcIdent);
               FileUtil.removeDirectory(buildSrcDir);
            }
         }
      }
   }
}
