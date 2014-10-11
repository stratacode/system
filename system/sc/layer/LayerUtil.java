/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.lang.*;
import sc.obj.SyncMode;
import sc.parser.*;
import sc.type.CTypeUtil;
import sc.util.ExtensionFilenameFilter;
import sc.util.FileUtil;
import sc.util.PatternFilenameFilter;
import sc.util.StringUtil;

import javax.tools.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.regex.Pattern;

public class LayerUtil implements LayerConstants {
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
      String newLayerDir = sys.newLayerDir;
      if (newLayerDir == null) {
         newLayerDir = System.getProperty("user.dir");
      }
      // If we use the -dyn option to selectively make layers dynamic, use a different build dir so that we can quickly switch back and forth between -dyn and not without rebuilding everything.
      String prefix = sysBuildDir == null ?  FileUtil.concat(newLayerDir, "temp", sys.options.anyDynamicLayers ? "dynbuild" : "build") : sysBuildDir;
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
         options.add("-target");
         options.add("1.5");

         Iterable<? extends JavaFileObject> fileObjectsToCompile = fileManager.getJavaFileObjectsFromFiles(filesToCompile);

         boolean result = compiler.getTask(null, fileManager, diagnostics, options, null, fileObjectsToCompile).call();

         for (Diagnostic diagnostic : diagnostics.getDiagnostics()) {
            java.io.PrintStream printer = result ? System.out : System.err;
            String message = diagnostic.getMessage(Locale.getDefault());
            if (!result && !suppressedCompilerMessages.contains(message)) {
               if (messageHandler != null)
                  messageHandler.reportMessage(message, null, -1, -1, MessageType.Error);
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
         args.add("-target");
         args.add("1.5");

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

   public static final FilenameFilter CLASSES_JAR_FILTER = new ExtensionFilenameFilter(Arrays.asList(new String[]{"class", "properties", "sctp"}), true);
   public static final FilenameFilter SRC_JAR_FILTER = new ExtensionFilenameFilter(Arrays.asList(new String[]{"java", "properties", "sctp", "xml"}), true);

   public static int buildJarFile(String buildDir, String prefix, String jarName, String typeName, String[] pkgs, String classPath, FilenameFilter jarFilter, boolean verbose) {
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

         if (typeName != null || classPath != null) {
            StringBuilder manifestChunk = new StringBuilder();
            if (typeName != null) {
               manifestChunk.append("Main-Class: ");
               manifestChunk.append(typeName);
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

}
