/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.util.FileUtil;

import java.io.File;
import java.util.Collections;
import java.util.List;

public class LayerFileProcessorResult implements IFileProcessorResult {
   SrcEntry srcEnt;
   LayerFileComponent processor;
   long lastModifiedTime;

   private boolean hasErrors;

   LayerFileProcessorResult(LayerFileComponent processor, SrcEntry srcEnt) {
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

   public void setHasErrors(boolean val) {
      hasErrors = val;
   }

   public boolean needsCompile() {
      return false;
   }

   public boolean needsPostBuild() {
      return false;
   }

   public List<SrcEntry> getProcessedFiles(Layer buildLayer, String buildSrcDir, boolean generate) {
      return processor.getProcessedFiles(this, buildLayer, buildSrcDir, generate);
   }

   public void postBuild(Layer buildLayer, String buildDir) {
      System.err.println("*** post build not yet implemented for layer files");
   }

   public SrcEntry getSrcFile() {
      return srcEnt;
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
