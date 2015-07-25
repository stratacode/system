package sc.layer;

import sc.util.FileUtil;

public abstract class LayerFileComponent extends LayerComponent implements IFileProcessor {

   /** If true, add the layer's package prefix onto the path name of the file in the layer to get the generated/copied file. */
   public boolean prependLayerPackage = true;

   /** For files that start with this prefix, do not include that prefix in the output path used for the file. e.g. with skipSrcPathPrefix = "resources", a path of "resources/icons/foo.gif" turns into "icons/foo.gif" */
   public String skipSrcPathPrefix;

   /** Prefix pre-pended onto the file in the build dir. */
   public String templatePrefix;

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

}
