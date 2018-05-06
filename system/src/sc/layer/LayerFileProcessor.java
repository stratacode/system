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
 * layer configuration file, or via an 'object processorName extends LayerFileProcessor {}' in your layer definition file.
 * Either way, a layer file processor is configured with a list of file extensions, prefixes, or patterns to match and process at build time.
 * A LayerFileProcessor controls it's visibility to other layers via the definedInLayer property (inherited from LayerComponent).  It's set to the layer which registers or defines the processor.
 * When it's not null, only layers which explicitly extend the defined layer will see this LayerFileProcessor.  When definedInLayer is not set, all files in all layers are matched.
 * A LayerFileProcessor inherits the properties from LayerFileComponent which control where processed files are placed - in the 'buildSrcDir', 'buildDir', or buildClassesDir.  You can
 * prepend the layers package onto the file path (e.g. for files that are compiled to .class or Java resources) or not - (e.g. files that are in the web directory).
 */
public class LayerFileProcessor extends LayerFileComponent {
   public String[] extensions;

   public String[] patterns;

   /** Optionally set to restrict this processor to only working on files with a given type - e.g. files in the web directory are marked as 'web' files and have different processors. */
   public String[] srcPathTypes;

   public BuildPhase buildPhase = BuildPhase.Process;

   /** If true extended layers see this processor.  If false, only files in this layer will use it */
   public boolean exportProcessing = true;

   /** If true, this processor disables processing of this file type (i.e. if this type has conflicts with a higher up layer) */
   public boolean disableProcessing = false;

   public boolean compiledLayersOnly = false;

   /** When inheriting a file from a previous layer, should we use the .inh files and remove all class files like the Java case does? */
   public boolean inheritFiles = false;

   private boolean producesTypes = false;

   {
      fileIndex = new HashMap<String,IFileProcessorResult>();
   }

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

   public Object process(SrcEntry srcEnt, boolean enablePartialValues) {
      LayerFileProcessorResult res;
      LayerFileProcessorResult current = (LayerFileProcessorResult) fileIndex.get(srcEnt.relFileName);
      int cpos, spos;
      // If processInAllLayers is set, we put the generated file in all build layers.  That means every time this gets called with the right file, we
      // process it.
      if (current == null || (cpos = current.srcEnt.layer.layerPosition) < (spos = srcEnt.layer.layerPosition) || (processInAllLayers && cpos == spos)) {
         fileIndex.put(srcEnt.relFileName, res = new LayerFileProcessorResult(this, srcEnt));
         return res;
      }
      else
         return new LayerFileProcessorResult(this, srcEnt); //FILE_OVERRIDDEN_SENTINEL;
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

   public FileEnabledState enabledForPath(String pathName, Layer fileLayer, boolean abs, boolean generatedFile) {
      // Currently we have one data structure to store all file processors - both activated and inactivated.  We filter them out here.
      // This is not accurate if we do not have a layer but almost all cases now do supply a layer so we should be fine.
      if (definedInLayer != null && fileLayer != null && definedInLayer.activated != fileLayer.activated)
         return FileEnabledState.NotEnabled;

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

   public void removePatterns(String... toRem) {
      if (patterns != null) {
         ArrayList<String> newRes = new ArrayList<String>(Arrays.asList(patterns));
         for (int i = 0; i < patterns.length; i++) {
            String ext = patterns[i];
            for (int j = 0; j < toRem.length; j++) {
               if (ext.equals(toRem[j])) {
                  newRes.remove(i);
                  break;
               }
            }
         }
         patterns = newRes.toArray(new String[newRes.size()]);
      }
   }

   public void addPatterns(String... toAdd) {
      if (patterns != null) {
         ArrayList<String> newRes = new ArrayList<String>(Arrays.asList(patterns));
         newRes.addAll(Arrays.asList(toAdd));
         patterns = newRes.toArray(new String[newRes.size()]);
      }
      else
         patterns = toAdd;
   }
}
