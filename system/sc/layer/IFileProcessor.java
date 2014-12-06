/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

/**
 * This is the core interface used when adding new types to the system.  Typically you use either a Language such as
 * sc.lang.JavaLanguage or you use a LayerFileProcessor instance.  Each of those classes comes with various properties
 * to customize how you process a file.
 * When you implement a file processor that produces a new file type, e.g. the .scsh files produce .sh files, you
 * also need to register a file processor which tells the system where the resulting files go.  It uses that internally
 * for the buildSrcIndex.  It's also good practice because you can then replace a .scsh file with a .sh file and vice
 * versa.
 */
public interface IFileProcessor {
   void validate();
   Object process(SrcEntry file, boolean enablePartialValues);

   boolean getInheritFiles();

   enum FileEnabledState { Disabled, NotEnabled, Enabled };

   boolean getNeedsCompile();

   boolean getProducesTypes();

   /** Is this file processor enabled for src files in the layer specified */
   FileEnabledState enabledFor(Layer layer);

   /** Returns the layer position used to sort this entry to resolve conflicts when more than one is registered for a suffix */
   int getLayerPosition();

   void setDefinedInLayer(Layer l);

   Layer getDefinedInLayer();

   /** Returns the phase in which this processor's files get processed */
   BuildPhase getBuildPhase();

   /** Some file types like WEB-INF don't use the layer's package as part of the type name */
   boolean getPrependLayerPackage();

   /** Returns true if the commonBuildDir (i.e. one per system) is used instead of the regular buildDir (one per build layer) */
   boolean getUseCommonBuildDir();

   /** Returns the directory to use for this processor for storing the generated files.  Returns null for the default buildSrcDir */
   String getOutputDir();

   /** Gets the output directory to use for the given system and buildDir  */
   String getOutputDirToUse(LayeredSystem sys, String buildSrcDir, String layerBuildDir);

   String getOutputFileToUse(LayeredSystem sys, IFileProcessorResult result, SrcEntry srcEnt);
}
