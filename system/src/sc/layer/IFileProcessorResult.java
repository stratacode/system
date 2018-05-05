/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import java.util.List;

/**
 * Files processed by the layered system can return an implementation of this interface to get automatic
 * dependency management.
 */
public interface IFileProcessorResult {

   /** The files which this result is dependent upon - i.e. any imports or external references */
   List<SrcEntry> getDependentFiles();

   /** True if this result has errors - in that case, it will always be processed next compile */
   boolean hasErrors();

   /** True if this result needs to be compiled using javac */
   boolean needsCompile();

   /** True if this result needs to be compiled using javac */
   boolean needsPostBuild();

   /** Returns the set of result processed files for dependency purposes. */
   List<SrcEntry> getProcessedFiles(Layer buildLayer, String buildSrcDir, boolean generate);

   void postBuild(Layer buildLayer, String buildDir);

   SrcEntry getSrcFile();

   void addSrcFile(SrcEntry file);

   /** Returns the unique name of the file or type this entity produces so we only process it once in a given system.  */
   String getProcessedFileId();

   /**
    * Returns the unique name of the file this type generates in the post-build process so we only generate that file once in a given system.
    * This is different than the processedFileId for files which are both types and files, e.g. schtml files.  It allows you to override an schtml
    * template in a different layer package.   At the type level, we'll maintain two unique types but the generated files will replace each other
    * to make it easier to merge web roots with layers that are in different packages.
    */
   String getPostBuildFileId();

   /** Returns the time this file was last modified */
   long getLastModifiedTime();
}
