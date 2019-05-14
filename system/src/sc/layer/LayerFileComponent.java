package sc.layer;

import sc.util.FileUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/** This is the base class for the LayerFileProcessor and Language - the two ways to add new file formats to the build processor.  */
public abstract class LayerFileComponent extends LayerComponent implements IFileProcessor {
   /** If true, add the layer's package prefix onto the path name of the file in the layer to get the generated/copied file. */
   public boolean prependLayerPackage = true;

   /** For files that start with this prefix, do not include that prefix in the output path used for the file. e.g. with skipSrcPathPrefix = "resources", a path of "resources/icons/foo.gif" turns into "icons/foo.gif" */
   public String skipSrcPathPrefix;

   /** Specifies a specific prefix that's pre-pended onto all files placed in the build dir. */
   public String templatePrefix;

   public String outputDir;

   /** Store the result in the buildSrcDir if true.  Otherwise use the classesDir or the root of the build-dir (see usesClassesDir)  */
   public boolean useSrcDir = true;

   /** If true, store the file in the directory used for java classes, otherwise use the build-dir itself.  Value of true not allowed with useSrcDir = true  */
   public boolean useClassesDir = false;

   /** Ordinarily files are copied to the current build dir for the build layer.  Setting this changes it so they are always copied to a fixed shared buildDir, say for a WEB-INF directory */
   public boolean useCommonBuildDir = false;

   /** Should the resulting file be executable - TODO: this is supported for layer files only right now but could also be supported for processed languages if needed */
   public boolean makeExecutable = false;

   /** If true, this processor disables processing of this file type (i.e. if this type has conflicts with a higher up layer) */
   public boolean disableProcessing = false;

   /**
    * Set this to true so that result files are copied to the build dir for for all build layers (e.g. web files).  Other assets like resources only need to be built in the first
    * build layer where they are included because they are picked up in the classpath.
    */
   public boolean processInAllLayers = false;

   /** When inheriting a file from a previous layer, should we use the .inh files and remove all class files like the Java case does? */
   public boolean inheritFiles = false;

   public List<String> excludeRuntimes = null;
   public List<String> includeRuntimes = null;

   /**
    * For scr files, this is set to true so that if there's a single runtime use it but only process them in the main runtime.
    * Most often it doesn't matter that files once for each process a testing layer which is only in the java_Server runtime (for example) that we want to use.
    * and since html/core is in the JS runtime and the JS runtime often is processed after the server runtime, that one overrides it.
    */
   public boolean mainSystemOnly = false;

   // Stores a mapping from file path to the most specific result file - used by LayerFileProcessor and the test script language to determine
   // which file is the most specific.  Since it's not used by languages which are processed, it's initialized only when it's needed by a subclass.
   protected HashMap<String,IFileProcessorResult> fileIndex = null;

   public LayerFileComponent() {
   }

   public LayerFileComponent(Layer definedInLayer) {
      super(definedInLayer);
   }

   public String getPathPrefix(SrcEntry srcEnt, Layer buildLayer) {
      String pathTypeName = srcEnt.layer.getSrcPathTypeName(srcEnt.absFileName, true);
      String prefix = templatePrefix == null ? buildLayer.getSrcPathBuildPrefix(pathTypeName) : templatePrefix;
      return prefix;
   }

   public String getOutputFileToUse(LayeredSystem sys, IFileProcessorResult result, SrcEntry srcEnt, Layer buildLayer) {
      String relFileName = srcEnt.relFileName;
      if (skipSrcPathPrefix != null && relFileName.startsWith(skipSrcPathPrefix))
         relFileName = relFileName.substring(skipSrcPathPrefix.length() + 1);

      String pathTypeName = srcEnt.layer.getSrcPathTypeName(srcEnt.absFileName, true);
      String prefix = templatePrefix == null ? buildLayer.getSrcPathBuildPrefix(pathTypeName) : templatePrefix;

      if (pathTypeName != null && srcEnt.layer != null) {
         String remPrefix = srcEnt.layer.getSrcPathBuildPrefix(pathTypeName);
         if (remPrefix != null && relFileName != null && relFileName.startsWith(remPrefix))
            relFileName = relFileName.substring(remPrefix.length() + 1);
      }

      /*
      if (pathMapTable != null) {
         for (PathMapEntry pathMapEnt:pathMapTable) {
            String fromDir = pathMapEnt.fromDir;
            if (relFileName.startsWith(fromDir)) {
               relFileName = FileUtil.concat(pathMapEnt.toDir, relFileName.substring(fromDir.length()));
            }
         }
      }
      */

      return FileUtil.concat(prefix, FileUtil.concat(prependLayerPackage ? srcEnt.layer.getPackagePath() : null, relFileName));
   }

   public String getOutputDir() {
      return outputDir;
   }

   public String getOutputDirToUse(LayeredSystem sys, String buildSrcDir, Layer buildLayer) {
      return outputDir == null ?
              (useSrcDir ? buildSrcDir :
                      useClassesDir ? (buildLayer.buildClassesDir != null ? buildLayer.buildClassesDir : sys.buildClassesDir):
                              (useCommonBuildDir ? sys.commonBuildDir : buildLayer.buildDir)) : outputDir;
   }

   public void validate() {
      if (useSrcDir && useClassesDir) {
         System.err.println("*** LayerFileProcessor: " + this + " has both useSrcDir and useClassesDir set.  Set useSrcDir to store the results in the buildSrc directory and useClassesDir to store it next to the classes in the runtime specific folder.  If useSrcDir = false and useClassesDir=false, the default is to store it in the buildDir without the runtime prefix.");
      }
   }

   public IFileProcessorResult getLayerFile(String relFileName) {
      return fileIndex.get(relFileName);
   }

   public List<SrcEntry> getProcessedFiles(IFileProcessorResult result, Layer buildLayer, String buildSrcDir, boolean generate) {
      SrcEntry srcEnt = result.getSrcFile();
      if (getLayerFile(srcEnt.relFileName) == result || processInAllLayers) {
         LayeredSystem sys = srcEnt.layer.layeredSystem;
         String newRelFile = getOutputFileToUse(sys, result, srcEnt, buildLayer);
         String newFile = FileUtil.concat(getOutputDirToUse(sys, buildSrcDir, buildLayer),  newRelFile);

         // The layered system processes hidden layer files backwards.  So generate will be true the for the
         // final layer's objects but an overridden component comes in afterwards... don't overwrite the new file
         // with the previous one.  We really don't need to transform this but I think it is moot because it will
         // have been transformed anyway.
         if (generate) {
            if (!FileUtil.copyFile(srcEnt.absFileName, newFile, true))
               result.setHasErrors(true);
         }
         SrcEntry resEnt = new SrcEntry(srcEnt.layer, newFile, newRelFile);
         resEnt.hash = FileUtil.computeHash(newFile);
         if (makeExecutable)
            new File(newFile).setExecutable(true, true);
         return Collections.singletonList(resEnt);
      }
      return null;
   }

   public boolean getProcessInAllLayers() {
      return processInAllLayers;
   }

   public boolean getInheritFiles() {
      return inheritFiles;
   }

   public FileEnabledState enabledForPath(String pathName, Layer fileLayer, boolean abs, boolean generatedFile) {
      if (disableProcessing)
         return FileEnabledState.Disabled;
      // When activated, some file types like 'scr' files only want to be processed by the main system.
      if (mainSystemOnly && fileLayer != null && fileLayer.activated && !fileLayer.layeredSystem.isMainSystem())
         return FileEnabledState.Disabled;
      if (definedInLayer != null) {
         String runtimeName = definedInLayer.layeredSystem.getRuntimeName();
         if (includeRuntimes != null) {
            if (includeRuntimes.contains(runtimeName))
               return FileEnabledState.Enabled;
            else
               return FileEnabledState.Disabled;
         }
         if (excludeRuntimes != null) {
            if (excludeRuntimes.contains(runtimeName))
               return FileEnabledState.Disabled;
            else
               return FileEnabledState.Enabled;
         }
      }
      return null;
   }

   public void excludeRuntime(String rtName) {
      if (excludeRuntimes == null)
         excludeRuntimes = new ArrayList<String>();
      excludeRuntimes.add(rtName);
   }
}
