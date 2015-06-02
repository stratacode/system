/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.lang.*;
import sc.lang.java.ModelUtil;
import sc.obj.SyncMode;
import sc.parser.*;
import sc.repos.IRepositoryManager;
import sc.repos.RepositoryPackage;
import sc.repos.RepositorySource;
import sc.repos.RepositorySystem;
import sc.type.CTypeUtil;
import sc.util.*;

import javax.tools.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.regex.Pattern;

public class LayerUtil implements LayerConstants {
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
      if (sys.buildLayer == layer && sys.options.buildLayerAbsDir != null) {
         return sys.options.buildLayerAbsDir;
      }
      String sysBuildDir = srcDir && sys.options.buildSrcDir != null ?  sys.options.buildSrcDir : sys.options.buildDir;
      String mainLayerDir = sys.mainLayerDir;
      if (mainLayerDir == null) {
         mainLayerDir = System.getProperty("user.dir");
      }
      // If we use the -dyn option to selectively make layers dynamic, use a different build dir so that we can quickly switch back and forth between -dyn and not without rebuilding everything.
      String prefix = sysBuildDir == null ?  FileUtil.concat(mainLayerDir, SC_DIR, sys.options.anyDynamicLayers ? "dynbuild" : "build") : sysBuildDir;
      prefix = FileUtil.concat(prefix, layer.getUnderscoreName());
      // TODO: remove this comment - this was a naive solution that caused headaches between JS marks all layers as compiled
      //return prefix + FileUtil.FILE_SEPARATOR + (layer.dynamic ? DYN_BUILD_DIRECTORY : BUILD_DIRECTORY);
      return prefix;
   }

   /** Takes the generated srcEnt */
   public static File getClassFile(Layer genLayer, SrcEntry srcEnt) {
      return new File(FileUtil.concat(genLayer.buildClassesDir,  srcEnt.getRelDir(), FileUtil.replaceExtension(srcEnt.baseFileName, "class")));
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

   public static int compileJavaFilesInternal(Collection<SrcEntry> srcEnts, String buildDir, String classPath, boolean debug, IMessageHandler messageHandler) {
      JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

      DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();

      StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null);

      List<File> filesToCompile = new ArrayList<File>(srcEnts.size());

      addFilesToCompile(srcEnts, buildDir, filesToCompile);

      if (filesToCompile.size() == 0) {
         System.out.println("No files to compile");
         return 0;
      }
      else {
         List<String> options = new ArrayList<String>();
         options.add("-d");
         options.add(buildDir);
         options.add("-cp");
         options.add(classPath);
         if (debug)
            options.add("-g");
         //options.add("-target");
         //options.add("1.5");

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
                  messageHandler.reportMessage(message, source.toString(), (int) lineNumber, (int) column, type);
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
   
   public static int compileJavaFiles(Collection<SrcEntry> srcEnts, String buildDir, String classPath, boolean debug, IMessageHandler handler) {
      if (srcEnts.size() > 0) {
         List<String> args = new ArrayList<String>(srcEnts.size() + 5);
         args.add("javac");

         addFileNamesToCompile(srcEnts, buildDir, args);

         args.add("-d");
         args.add(buildDir);
         args.add("-cp");
         args.add(classPath);
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
         System.out.println("*** No files to compile");
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
   public static final FilenameFilter CLASSES_JAR_FILTER = new ExtensionFilenameFilter(Arrays.asList(new String[]{"class", "properties", "sctp", "xml", "jpg", "gif", "png", "ft", "html"}), true);
   public static final FilenameFilter SRC_JAR_FILTER = new ExtensionFilenameFilter(Arrays.asList(new String[]{"java", "properties", "sctp", "xml"}), true);

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
            resFiles.add(dirPath);
            Map<String,Object> dirEnt = (Map<String,Object>) fileValue;
            addFilesToSubList(resFiles, dirEnt, dirPath);
         }
      }
   }

   public static int buildJarFile(String buildDir, String prefix, String jarName, String mainTypeName, String[] pkgs, String classPath, FilenameFilter jarFilter, boolean verbose) {
      List<String> args = null;
      File manifestTmp = null;

      // When there's a java or js prefix for the src/class files, we still need the files relative to that dir.  This means making
      // the jar name be ../bin/...
      if (prefix != null && prefix.length() > 0)
         jarName = FileUtil.concat("..", jarName);

      String classDir = FileUtil.concat(buildDir, prefix);

      // First make directory the file goes in
      String jarDir = FileUtil.getParentPath(FileUtil.concat(classDir,jarName));
      File jarDirFile = new File(jarDir);
      jarDirFile.mkdirs();

      try {
         List<String> allClassFiles;
         if (pkgs != null) {
            allClassFiles = new ArrayList<String>();
            for (String pkg:pkgs) {
               List<String> newFiles = FileUtil.getRecursiveFiles(classDir, FileUtil.concat(pkg.replace(".", FileUtil.FILE_SEPARATOR)), jarFilter);
               allClassFiles.addAll(newFiles);
            }
         }
         else {
            allClassFiles = FileUtil.getRecursiveFiles(classDir, null, jarFilter);
         }
         String opts;

         allClassFiles = folderizeFileList(allClassFiles);

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
         args.addAll(allClassFiles);

         ProcessBuilder pb = new ProcessBuilder(args);
         pb.directory(new File(classDir));
         pb.redirectErrorStream(true);

         if (verbose)
            System.out.println("Packaging: " + jarName + " with files: " + StringUtil.argsToString(args));
         else
            System.out.println("Packaging: " + args.size() + " files into: " + jarName);

         Process p = pb.start();

         BufferedInputStream bis = new BufferedInputStream(p.getInputStream());
         byte [] buf = new byte[1024];
         int len;
         while ((len = bis.read(buf, 0, buf.length)) != -1)
            System.out.write(buf, 0, len);
         int stat = p.waitFor();
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

   public static int execCommand(ProcessBuilder cmd, String dir) {
      if (dir != null)
         cmd.directory(new File(dir));
      
      try {
         cmd.redirectErrorStream(true);

         Process p = cmd.start();

         BufferedInputStream bis = new BufferedInputStream(p.getInputStream());
         byte [] buf = new byte[1024];
         int len;
         while ((len = bis.read(buf, 0, buf.length)) != -1)
            System.out.write(buf, 0, len);
         int stat = p.waitFor();
         return stat;
      }
      catch (InterruptedException exc) {
         if (cmd.command() != null)
            System.err.println("*** package of: " + StringUtil.arrayToCommand(cmd.command().toArray()) + " - wait interrupted: " + exc);
      }
      catch (IOException exc) {
         if (cmd.command() != null)
            System.err.println("*** package of: " + StringUtil.arrayToCommand(cmd.command().toArray()) + " failed: " + exc);
      }
      return -1;
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
      String baseName = FileUtil.getFileName(pathName) + SCLanguage.STRATACODE_SUFFIX;
      return FileUtil.concat(pathName, baseName);
   }

   public static boolean execCommands(List<ProcessBuilder> cmds, String directory, boolean info) {
      for (ProcessBuilder b:cmds) {
         if (info)
            System.out.println("Executing: '" + StringUtil.arrayToCommand(b.command().toArray()) + "' in: " + directory);
         if (execCommand(b, directory) != 0) {
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
         boolean first = true;
         if (hidden)
            first = opAppend(sb, "hidden", first);
         if (l.buildSeparate)
            first = opAppend(sb, "build separate", first);
         if (l.isBuildLayer())
            first = opAppend(sb, "build", first);
         if (l.finalLayer)
            first = opAppend(sb, "finalLayer", first);
         if (l.excludeRuntimes != null)
            first = opAppend(sb, " excludes: " + l.excludeRuntimes, first);
         if (l.hasDefinedRuntime)
            first = opAppend(sb, " only: " + (l.definedRuntime == null ? "java" : l.definedRuntime), first);
         if (l.excludeProcesses != null)
            first = opAppend(sb, " excludes: " + l.excludeProcesses, first);
         if (l.hasDefinedProcess)
            first = opAppend(sb, " only: " + (l.definedProcess == null ? "<default>" : l.definedProcess), first);
         if (l.defaultSyncMode == SyncMode.Automatic)
            first = opAppend(sb, " auto-sync", first);
         Object syncAnnot = ModelUtil.getLayerAnnotation(l, "sc.obj.Sync");
         if (syncAnnot != null) {
            SyncMode mode = (SyncMode) ModelUtil.getAnnotationValue(syncAnnot, "syncMode");
            if (mode == null)
               mode = SyncMode.Enabled;
            first = opAppend(sb, " syncMode=" + mode.toString(), first);
         }
         sb.append("\n");
      }
   }

   private static boolean opAppend(StringBuilder sb, String opt, boolean first) {
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

   public static String getExceptionStack(Throwable t) {
      StringWriter sw = new StringWriter();
      PrintWriter out = new PrintWriter(sw);

      t.printStackTrace(out);
      return sw.toString();
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
      if (parseNode != null) {
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

   public static String installDefaultLayers(String resultDir, IMessageHandler handler, boolean verbose, String gitURL) {
      RepositorySystem sys = new RepositorySystem(resultDir, handler, verbose);
      IRepositoryManager mgr = sys.getRepositoryManager("git");
      String fileName = gitURL == null ? "layers" : FileUtil.removeExtension(FileUtil.getFileName(gitURL)); // Remove the '.git' suffix and take the last name as the file name.
      RepositoryPackage pkg = new RepositoryPackage(mgr, fileName, new RepositorySource(mgr, gitURL == null ? LayerConstants.DEFAULT_LAYERS_URL : gitURL, false));
      //RepositoryPackage pkg = new RepositoryPackage("layers", new RepositorySource(mgr, "ssh://vsgit@stratacode.com/home/git/vs/layers", false));
      pkg.fileName = null; // Just install this package into the packageRoot - don't add the packageName like we do for most packages
      String err = pkg.install();
      return err;
   }

}
