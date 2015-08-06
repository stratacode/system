/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.obj.CompilerSettings;
import sc.obj.IComponent;
import sc.util.FileUtil;
import sc.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/**
 * Manages the process of building (or simply copying) a particular type of file during the layered build process.
 * Typically a LayerFileProcessor is registered during system startup by calling layeredSystem.registerFileProcessor from a
 * layer configuration file.  You register it with one or more suffixes, prefixes, or patterns.  The file processor uses those to match
 * files in the layers during the build process.  It matches any files defined in layers which extends from the layer in which it was defined (if
 * definedInLayer is set), or any subsequent layer if not.  You may prepend the package name of the source file or layer when appropriate.
 * For example, in wicket, a .gif file as a java resource would typically prepend the package suffix.  A .gif file in the web subdirectory
 * of a web application would not prepend it's layer's package prefix.
 */
public class LayerFileProcessor extends LayerFileComponent {
   private HashMap<String,LayerFileProcessorResult> fileIndex = new HashMap<String,LayerFileProcessorResult>();

   public String[] extensions;

   public String[] patterns;

   public String outputDir;

   /** If true, use the resulting file part of the generated source and store it in the buildSrcDir of the layer. */
   public boolean useSrcDir = true;

   /** If true, store the file in the directory used for java classes */
   public boolean useClassesDir = false;

   /** Optionally set to restrict this processor to only working on files with a given type - e.g. files in the web directory are marked as 'web' files and have different processors. */
   public String[] srcPathTypes;

   public BuildPhase buildPhase = BuildPhase.Process;

   /** If true extended layers see this processor.  If false, only files in this layer will use it */
   public boolean exportProcessing = true;

   /** If true, this processor disables processing of this file type (i.e. if this type has conflicts with a higher up layer) */
   public boolean disableProcessing = false;

   public boolean compiledLayersOnly = false;

   /** Ordinarily files are copied to the current build dir for the build layer.  Setting this changes it so they are always copied to a fixed shared buildDir, say for a WEB-INF directory */
   public boolean useCommonBuildDir = false;

   /** Should the resulting file be executable */
   public boolean makeExecutable = false;

   public boolean processInAllLayers = false;

   /** When inheriting a file from a previous layer, should we use the .inh files and remove all class files like the Java case does? */
   public boolean inheritFiles = false;

   private boolean producesTypes = false;

   public LayerFileProcessor() {
   }

   public LayerFileProcessor(Layer definedInLayer) {
      super(definedInLayer);
   }

   /*
   public static LayerFileProcessor newLayerFileProcessor(Layer parent) {
      return new LayerFileProcessor(parent);
   }

   public static LayerFileProcessor newLayerFileProcessor() {
      return new LayerFileProcessor(null);
   }
   */

   public void start() {
      if (_initState > 2)
         return;
      super.start();

      if (definedInLayer != null) {
         if (extensions != null) {
            for (String ext:extensions) {
               definedInLayer.registerFileProcessor(this, ext);
            }
         }
         if (patterns != null) {
            for (String pattern:patterns) {
               definedInLayer.layeredSystem.registerPatternFileProcessor(pattern, this, definedInLayer);
            }
         }
      }
   }

   public static class PathMapEntry {
      public String fromDir;
      public String toDir;
   }

   /*
   private ArrayList<PathMapEntry> pathMapTable;

   public void addPathMap(String fromDir, String toDir) {
      if (pathMapTable == null) {
         pathMapTable = new ArrayList<PathMapEntry>();
      }
      PathMapEntry ent = new PathMapEntry();
      ent.fromDir = fromDir;
      ent.toDir = toDir;
      pathMapTable.add(ent);
   }
   */

   public void validate() {
      if (useSrcDir && useClassesDir) {
         System.err.println("*** LayerFileProcessor: " + this + " has both useSrcDir and useClassesDir set.  Set useSrcDir to store the results in the buildSrc directory and useClassesDir to store it next to the classes in the runtime specific folder.  If useSrcDir = false and useClassesDir=false, the default is to store it in the buildDir without the runtime prefix.");
      }
   }

   public Object process(SrcEntry srcEnt, boolean enablePartialValues) {
      LayerFileProcessorResult res = null;
      LayerFileProcessorResult current = fileIndex.get(srcEnt.relFileName);
      int cpos, spos;
      // If processInAllLayers is set, we put the generated file in all build layers.  That means every time this gets called with the right file, we
      // process it.
      if (current == null || (cpos = current.srcEnt.layer.layerPosition) < (spos = srcEnt.layer.layerPosition) || (processInAllLayers && cpos == spos))
         fileIndex.put(srcEnt.relFileName, res = new LayerFileProcessorResult(this, srcEnt));
      return res;
   }

   public boolean getInheritFiles() {
      return inheritFiles;
   }

   public boolean getNeedsCompile() {
      return false;
   }

   /** Set this to true if your file processor produces a type in the global type system */
   public void setProducesTypes(boolean pt) {
      producesTypes = pt;
   }

   public boolean getProducesTypes() {
      return producesTypes;
   }

   public boolean getUseCommonBuildDir() {
      return useCommonBuildDir;
   }

   public String getOutputDir() {
      return outputDir;
   }

   public void resetBuild() {
      fileIndex.clear();
   }

   public String getOutputDirToUse(LayeredSystem sys, String buildSrcDir, String layerBuildDir) {
      return outputDir == null ?
              (useSrcDir ? buildSrcDir :
                      useClassesDir ? sys.buildClassesDir :
                              (useCommonBuildDir ? sys.commonBuildDir : layerBuildDir)) : outputDir;
   }

   public FileEnabledState enabledForPath(String pathName, Layer fileLayer, boolean abs, boolean generatedFile) {
      // TODO: We should not be passing in null here in general
      if (fileLayer == null || generatedFile)
         return FileEnabledState.Enabled;

      String filePathType = fileLayer.getSrcPathTypeName(pathName, abs);
      if (srcPathTypes != null) {
         for (int i = 0; i < srcPathTypes.length; i++) {
            boolean res = StringUtil.equalStrings(srcPathTypes[i], filePathType);
            if (res)
               return FileEnabledState.Enabled;
         }
      }
      else if (filePathType == null)
         return FileEnabledState.Enabled;
      return FileEnabledState.NotEnabled;
   }

   public FileEnabledState enabledFor(Layer layer) {
      // Null disables this check
      if (layer == null)
         return FileEnabledState.Enabled;

      // Explicitly disabled for this layer
      if (disableProcessing)
         return FileEnabledState.Disabled;

      // Defined globally
      if (definedInLayer == null)
         return FileEnabledState.Enabled;

      if (compiledLayersOnly && layer.dynamic)
         return FileEnabledState.Disabled;

      // Don't enable this before the layer in which it was defined.
      return (exportProcessing ? layer.layerPosition >= definedInLayer.layerPosition :
                                layer.layerPosition == definedInLayer.layerPosition) ? FileEnabledState.Enabled : FileEnabledState.NotEnabled;
   }

   public int getLayerPosition() {
      return definedInLayer == null ? -1 : definedInLayer.layerPosition;
   }

   public void setDefinedInLayer(Layer l) {
      definedInLayer = l;
   }

   public Layer getDefinedInLayer() {
      return definedInLayer;
   }

   public BuildPhase getBuildPhase() {
      return buildPhase;
   }

   public boolean getPrependLayerPackage() {
      return prependLayerPackage;
   }

   public LayerFileProcessorResult getLayerFile(String relFileName) {
      return fileIndex.get(relFileName);
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("LayerFileProcessor: definedInLayer: " + definedInLayer + " for phase: " + buildPhase + " prependLayerPackage=" + prependLayerPackage + " outputDir=" + outputDir + " templatePrefix=" + templatePrefix + " useCommonBuildDir=" + useCommonBuildDir + " srcPathTypes=" + StringUtil.arrayToString(srcPathTypes));
      return sb.toString();
   }

   public boolean isParsed() {
      return false;
   }

   public String[] getSrcPathTypes() {
      return srcPathTypes;
   }

   public void removeExtensions(String... toRem) {
      if (extensions != null) {
         ArrayList<String> newRes = new ArrayList<String>(Arrays.asList(extensions));
         for (int i = 0; i < extensions.length; i++) {
            String ext = extensions[i];
            for (int j = 0; j < toRem.length; j++) {
               if (ext.equals(toRem[j])) {
                  newRes.remove(i);
                  break;
               }
            }
         }
         extensions = newRes.toArray(new String[newRes.size()]);
      }
   }

   public void addExtensions(String... toAdd) {
      if (extensions != null) {
         ArrayList<String> newRes = new ArrayList<String>(Arrays.asList(extensions));
         newRes.addAll(Arrays.asList(toAdd));
         extensions = newRes.toArray(new String[newRes.size()]);
      }
   }
}
