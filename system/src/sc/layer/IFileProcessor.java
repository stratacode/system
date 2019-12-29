/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

/**
 * This is the core interface used when adding new file types to the system.  Implementations include sc.parser.Language subclasses:
 * sc.lang.JavaLanguage, etc. The LayerFileProcessor class supports simpler processors that merge files without parsing (the default
 * behavior is to just take the last version to allow for a layer to replace the file in a previous one).
 * Each of those classes include configurable properties to make file type processor flexible.
 * Some file processors convert one format to another e.g. a file with a suffix .scsh files produces one with .sh.
 * In this case, register a LayerFileProcessor for .sh files to control the output file location.  This is needed for
 * the buildSrcIndex so that it can manage the generated files.
 * This also allows a .sh file to replace a .scsh file - to go from a converted file, to a fixed one in a subsequent layer.
 */
public interface IFileProcessor {
   final static Object FILE_OVERRIDDEN_SENTINEL = new String("<file-overridden-sentinel>");

   /** Called when the processor is registered to validate it's configuration */
   void validate();

   /**
    * Once all files have been validated, the process method is called on each to actually perform
    * produce the processor result.
    * This method returns Object to make it easier to plug in different
    * frameworks.
    * For a Language, the file is parsed and produces an IParseNode or a ParseError.
    *
    * For LayerFileProcessors, it returns IFileProcessorResult - an interface with info about the file to help with
    * dependency management, increment rebuilds, etc.
    *
    * For Languages, the top level semantic node (e.g. JavaModel) implements IFileProcessorResult for the same thing.
    */
   Object process(SrcEntry file, boolean enablePartialValues);

   boolean getInheritFiles();

   enum FileEnabledState { Disabled, NotEnabled, Enabled }

   boolean getNeedsCompile();

   boolean getProducesTypes();

   /** Is this file processor enabled for src files in the layer specified */
   FileEnabledState enabledFor(Layer layer);

   /** Some file processors are registered for a specific pathname (e.g. web files versus regular src files) */
   FileEnabledState enabledForPath(String fileName, Layer fileLayer, boolean absFileName, boolean generatedFile);

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
   String getOutputDirToUse(LayeredSystem sys, String buildSrcDir, Layer buildLayer);

   String getOutputFileToUse(LayeredSystem sys, IFileProcessorResult result, SrcEntry srcEnt, Layer buildLayer);

   void resetBuild();

   /** Returns true for Language implementations that are parseable and processable - TODO: rename to isProcessed() or isTransformed().  We have scr files that can be parsed but are not transformed */
   boolean isParsed();

   public String[] getSrcPathTypes();

   boolean getProcessInAllLayers();
}
