/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.lang.java.JavaModel;
import sc.layer.deps.DependencyFile;
import sc.util.FileUtil;

import java.io.File;
import java.util.*;

class BuildState {
   int numModelsToTransform = 0;
   boolean changedModelsPrepared = false;
   BuildStepFlags prepPhase = new BuildStepFlags();
   BuildStepFlags processPhase = new BuildStepFlags();
   boolean changedModelsDetected = false;
   Set<String> processedFileNames = new HashSet<String>();

   // TODO: rename this buildSrcDirs? It's confusing to use 'ent' for dirs only
   // The list of source directories to build, in order. As we process one srcDir, we find sub-dirs that are appended to the list.
   ArrayList<SrcEntry> srcEnts = new ArrayList<SrcEntry>();

   // Index of srcEnts
   HashSet<SrcEntry> srcEntSet = new HashSet<SrcEntry>();

   boolean anyError = false;
   boolean fileErrorsReported = false;
   LinkedHashSet<SrcEntry> toCompile = new LinkedHashSet<SrcEntry>();
   // The list of files which have errors of any kind
   List<SrcEntry> errorFiles = new ArrayList<SrcEntry>();
   long buildTime = System.currentTimeMillis();
   LinkedHashSet<SrcDirEntry> srcDirs = new LinkedHashSet<SrcDirEntry>();
   // TODO: is there ever more than one entry in this list? Should we just accumulate all changes to the same dir in the same SrcDirEntry (addChangedModel would need to be updated)
   HashMap<String,ArrayList<SrcDirEntry>> srcDirsByPath = new HashMap<String,ArrayList<SrcDirEntry>>();

   // Models which contribute to typeGroup dependencies, e.g. @MainInit, must be processed
   HashMap<String,IFileProcessorResult> typeGroupChangedModels = new HashMap<String, IFileProcessorResult>();
   HashMap<String,SrcEntry> unchangedFiles = new HashMap<String, SrcEntry>();

   // The list of actually changed files we found when we are incrementally compiling
   ArrayList<SrcEntry> modifiedFiles = new ArrayList<SrcEntry>();

   HashMap<SrcEntry,SrcEntry> dependentFilesChanged = new HashMap<SrcEntry,SrcEntry>();

   boolean anyChangedClasses = false;

   HashMap<String,Integer> typeGroupChangedModelsCount = new HashMap<String,Integer>();

   void addSrcEntry(int ix, SrcEntry newSrcEnt) {
      if (!srcEntSet.contains(newSrcEnt)) {
         if (ix == -1)
            srcEnts.add(newSrcEnt);
         else
            srcEnts.add(ix, newSrcEnt);
         srcEntSet.add(newSrcEnt);
      }
   }

   void addErrorFile(SrcEntry ent) {
      anyError = true;
      if (!errorFiles.contains(ent))
         errorFiles.add(ent);
      fileErrorsReported = false;
   }

   ArrayList<Layer> startedLayers;

   String getDependentNamesString() {
      StringBuilder sb = new StringBuilder();
      boolean first = true;
      for (Map.Entry<SrcEntry,SrcEntry> ent:dependentFilesChanged.entrySet()) {
         if (!first) {
            sb.append(", ");
         }
         else
            first = false;
         sb.append(ent.getKey().baseFileName);
         sb.append("->");
         sb.append(ent.getValue().baseFileName);
      }
      return sb.toString();
   }

   static File getDependenciesFile(Layer genLayer, SrcEntry srcEnt, BuildPhase phase) {
      String srcRootPath = srcEnt.srcRootName == null ? "" : "--" + srcEnt.srcRootName;
      return new File(genLayer.buildSrcDir,
              FileUtil.concat(srcEnt.layer.getPackagePath(),
                                srcEnt.relFileName,
                                srcEnt.layer.getLayerName().replace('.', '-') + srcRootPath + "-" + phase.getDependenciesFile()));
   }

   void addSrcDirEntry(SrcEntry srcEnt, DependencyFile deps, LinkedHashSet<SrcEntry> toGenerate) {
      // Need to do this even if there are no models to transform to ensure deps get saved and
      // in case we later mark a file changed via a type group dependency.
      SrcDirEntry srcDirEnt = new SrcDirEntry();
      srcDirEnt.deps = deps;
      srcDirEnt.layer = srcEnt.layer;
      srcDirEnt.srcPath = srcEnt.relFileName;
      srcDirEnt.depsChanged = deps.depsChanged;
      srcDirEnt.fileError = true;
      if (toGenerate == null)
         toGenerate = new LinkedHashSet<SrcEntry>();
      srcDirEnt.toGenerate = toGenerate;
      srcDirs.add(srcDirEnt);
      ArrayList<SrcDirEntry> sdEnts = srcDirsByPath.get(srcEnt.absFileName);
      if (sdEnts == null) {
         sdEnts = new ArrayList<SrcDirEntry>();
         srcDirsByPath.put(srcEnt.absFileName, sdEnts);
      }
      sdEnts.add(srcDirEnt);
   }

   // We do not ordinarily add source directories for layers that have already been built using an intermediate build layer
   // But if we change a base type of a model, we don't discover it until later. This method lets framework code add a changed
   // model after the set of changed files has been discovered in the next build layer, those changes have been applied, we've refreshed
   // all of the types in the system and those changes result in dependent types that also have changed. For example, when changing a base
   // type affects the code generated in subclasses due to some framework effects like an schtml template or annotation.
   void addChangedModel(JavaModel changedModel, Layer genLayer, BuildPhase phase) {
      SrcEntry srcEnt = changedModel.getSrcFile();
      String relDir = FileUtil.getParentPath(srcEnt.relFileName);
      if (relDir == null) relDir = "";
      SrcEntry srcDir = new SrcEntry(changedModel.getLayer(), FileUtil.getParentPath(srcEnt.absFileName), relDir, "", true, srcEnt.srcRootName);
      List<SrcDirEntry> sdEnts = srcDirsByPath.get(srcDir.absFileName);
      if (sdEnts != null) {
         for (SrcDirEntry sdEnt:sdEnts) {
            if (sdEnt.toGenerate.contains(srcEnt)) // already generating it
               return;
         }
      }
      LinkedHashSet<SrcEntry> toGenerate = new LinkedHashSet<SrcEntry>();
      LayeredSystem sys = changedModel.getLayeredSystem();
      sys.addToGenerateList(toGenerate, srcEnt, changedModel.getModelTypeName());
      File depFile = BuildState.getDependenciesFile(genLayer, srcDir, phase);
      DependencyFile deps = DependencyFile.readDependencies(changedModel.getLayeredSystem(), depFile, srcDir);
      if (deps == null) {
         deps = DependencyFile.create();
         deps.file = depFile;
         deps.depsChanged = true;
      }
      if (!srcEntSet.contains(srcDir))
         addSrcEntry(-1, srcDir);
      addSrcDirEntry(srcDir, deps, toGenerate);
   }
}
