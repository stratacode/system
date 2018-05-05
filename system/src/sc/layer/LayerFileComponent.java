package sc.layer;

import sc.util.FileUtil;

public abstract class LayerFileComponent extends LayerComponent implements IFileProcessor {

   /** If true, add the layer's package prefix onto the path name of the file in the layer to get the generated/copied file. */
   public boolean prependLayerPackage = true;

   /** For files that start with this prefix, do not include that prefix in the output path used for the file. e.g. with skipSrcPathPrefix = "resources", a path of "resources/icons/foo.gif" turns into "icons/foo.gif" */
   public String skipSrcPathPrefix;

   /** Prefix pre-pended onto the file in the build dir. */
   public String templatePrefix;

   public String outputDir;

   /** Store the result in the buildSrcDir if true.  Otherwise use the classesDir or the root of the build-dir (see usesClassesDir)  */
   public boolean useSrcDir = true;

   /** If true, store the file in the directory used for java classes, otherwise use the build-dir itself.  Value of true not allowed with useSrcDir = true  */
   public boolean useClassesDir = false;

   /** Ordinarily files are copied to the current build dir for the build layer.  Setting this changes it so they are always copied to a fixed shared buildDir, say for a WEB-INF directory */
   public boolean useCommonBuildDir = false;

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

}
