package sc.layer;

import java.util.*;

class BuildState {
   int numModelsToTransform = 0;
   boolean changedModelsPrepared = false;
   BuildStepFlags prepPhase = new BuildStepFlags();
   BuildStepFlags processPhase = new BuildStepFlags();
   boolean changedModelsDetected = false;
   Set<String> processedFileNames = new HashSet<String>();

   HashSet<SrcEntry> srcEntSet = new HashSet<SrcEntry>();
   ArrayList<SrcEntry> srcEnts = new ArrayList<SrcEntry>();

   boolean anyError = false;
   boolean fileErrorsReported = false;
   LinkedHashSet<SrcEntry> toCompile = new LinkedHashSet<SrcEntry>();
   // The list of files which have errors of any kind
   List<SrcEntry> errorFiles = new ArrayList<SrcEntry>();
   long buildTime = System.currentTimeMillis();
   LinkedHashSet<SrcDirEntry> srcDirs = new LinkedHashSet<SrcDirEntry>();
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
}
