/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.util.FileUtil;

import java.io.File;
import java.util.Collections;
import java.util.List;

public class LayerFileProcessorResult implements IFileProcessorResult {
   SrcEntry srcEnt;
   LayerFileProcessor processor;
   long lastModifiedTime;

   private boolean hasErrors;

   LayerFileProcessorResult(LayerFileProcessor processor, SrcEntry srcEnt) {
      this.processor = processor;
      this.srcEnt = srcEnt;
      if (srcEnt != null)
         this.lastModifiedTime = new File(srcEnt.absFileName).lastModified();
   }

   public List<SrcEntry> getDependentFiles() {
      return null;
   }

   public boolean hasErrors() {
      return hasErrors;
   }

   public boolean needsCompile() {
      return false;
   }

   public boolean needsPostBuild() {
      return false;
   }

   public List<SrcEntry> getProcessedFiles(Layer buildLayer, String buildSrcDir, boolean generate) {
      // If not overridden by another file with the same name... maybe this is not needed though since generate is
      // false for none files
      if (processor.getLayerFile(srcEnt.relFileName) == this || processor.processInAllLayers) {
         SrcEntry src = srcEnt;
         LayeredSystem sys = srcEnt.layer.layeredSystem;
         String newRelFile = FileUtil.concat(processor.templatePrefix == null ? null : processor.templatePrefix,
                 FileUtil.concat(processor.prependLayerPackage ? srcEnt.layer.getPackagePath() : null, src.relFileName));
         String newFile = FileUtil.concat(processor.getOutputDirToUse(sys, buildSrcDir, buildLayer.buildDir),  newRelFile);

         // The layered system processes hidden layer files backwards.  So generate will be true the for the
         // final layer's objects but an overriden component comes in afterwards... don't overwrite the new file
         // with the previous one.  We really don't need to transform this but I think it is moot because it will
         // have been transformed anyway.
         if (generate) {
            if (!FileUtil.copyFile(srcEnt.absFileName, newFile, true))
               hasErrors = true;
         }
         SrcEntry srcEnt = new SrcEntry(src.layer, newFile, newRelFile);
         srcEnt.hash = FileUtil.computeHash(newFile);
         if (processor.makeExecutable)
            new File(newFile).setExecutable(true, true);
         return Collections.singletonList(srcEnt);
      }
      return null;
   }

   public void postBuild(Layer buildLayer, String buildDir) {
      System.err.println("*** post build not yet implemented for layer files");
   }

   public void addSrcFile(SrcEntry file) {
   }

   /** Needs the full type name plus the suffix to differentiate */
   public String getProcessedFileId() {
      if (processor.prependLayerPackage)
         return srcEnt.getTypeName() + "." + FileUtil.getExtension(srcEnt.baseFileName);
      else
         return srcEnt.getRelTypeName() + "." + FileUtil.getExtension(srcEnt.baseFileName);
   }

   public String getPostBuildFileId() {
      return getProcessedFileId();
   }

   public long getLastModifiedTime() {
      return lastModifiedTime;
   }
}
