/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer.deps;

import sc.lang.SemanticNode;
import sc.lang.SemanticNodeList;
import sc.layer.Layer;
import sc.layer.LayerUtil;
import sc.layer.LayeredSystem;
import sc.layer.SrcEntry;
import sc.lifecycle.ILifecycle;
import sc.parser.IString;
import sc.parser.PString;
import sc.parser.ParseError;
import sc.parser.ParseUtil;
import sc.util.FileUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DependencyFile extends SemanticNode implements ILifecycle {
   public List<DependencyEntry> depList;

   public boolean depsChanged = false;

   public File file;

   public transient Map<String,DependencyEntry> depsByName = new HashMap<String,DependencyEntry>();

   public DependencyEntry getDependencies(String fileName) {
      return depsByName.get(fileName);
   }

   public void init() {
      if (depList == null)
         depList = new SemanticNodeList<DependencyEntry>(this);
      for (int i = 0; i < depList.size(); i++) {
         DependencyEntry ent = depList.get(i);
         depsByName.put(ent.fileName, ent);
      }
   }

   public boolean addDirectory(String fileName) {
      DependencyEntry newEnt = new DependencyEntry();
      newEnt.fileName = FileUtil.normalize(fileName);
      newEnt.isDirectory = true;
      if (depsByName.put(fileName, newEnt) == null) {
         depList.add(newEnt);
         return true;
      }
      return false;
   }

   public void start() {}

   public DependencyEntry getOrCreateDependencyEntry(String baseFileName) {
      baseFileName = FileUtil.normalize(baseFileName);
      DependencyEntry ent = depsByName.get(baseFileName);
      if (ent == null) {
         ent = new DependencyEntry();
         ent.parentNode = this;
         ent.fileName = baseFileName;
         depsByName.put(baseFileName, ent);
         if (depList == null)
            depList = new SemanticNodeList<DependencyEntry>(ent);
         depList.add(ent);
      }
      return ent;
   }

   public void addDependencies(String baseFileName, List<SrcEntry> genFiles, List<SrcEntry> srcDeps) {
      DependencyEntry ent = getOrCreateDependencyEntry(baseFileName);
      if (ent.fileDeps == null)
         ent.fileDeps = new SemanticNodeList<LayerDependencies>(ent);
      else
         ent.fileDeps.clear();
      ent.genFileNames = genFiles == null ? null : LayerUtil.getRelFileNamesFromSrcEntries(genFiles, false);

      // Now organize the dependencies by layer so they are easier to read in the file
      if (srcDeps != null) {
         for (SrcEntry srcEnt:srcDeps) {
            String layerName = srcEnt.layer.getLayerUniqueName();
            LayerDependencies layerDeps = ent.getLayerDependencies(layerName);
            if (layerDeps == null) {
               layerDeps = new LayerDependencies();
               layerDeps.parentNode = ent;
               layerDeps.layerName = layerName;
               layerDeps.fileList = new SemanticNodeList<IString>(layerDeps);
               layerDeps.position = srcEnt.layer.getLayerPosition();
               ent.addLayerDependencies(layerDeps);
            }
            layerDeps.fileList.add(PString.toIString(srcEnt.relFileName));
         }
      }
   }

   public void removeDependencies(String baseFileName) {
      DependencyEntry ent = depsByName.remove(baseFileName);
      if (ent == null)
         return;
      depList.remove(ent);
   }

   public void removeDependencies(int index) {
      DependencyEntry ent = depList.remove(index);
      depsByName.remove(ent.fileName);
   }

   public int getChildNestingDepth()
   {
      if (parentNode != null)
         return parentNode.getChildNestingDepth() + 1;
      return 0;
   }

   public List<IString> getGeneratedFiles(String baseFileName) {
      DependencyEntry ent = getDependencies(baseFileName);
      if (ent == null)
         return null;
      return ent.genFileNames;
   }

   public void addErrorDependency(String baseFileName) {
      DependencyEntry ent = getOrCreateDependencyEntry(baseFileName);
      ent.isError = true;
      if (ent.fileDeps != null)
         ent.setProperty("fileDeps", null);
   }

   public static DependencyFile create() {
      DependencyFile deps = new DependencyFile();
      deps.depList = new ArrayList<DependencyEntry>();
      return deps;
   }

   public static DependencyFile readDependencies(LayeredSystem sys, File depFile, SrcEntry srcEnt) {
      Object result;
      DependencyFile deps = null;
      try {
         result = DependenciesLanguage.INSTANCE.parse(depFile);
         if (result instanceof ParseError) {
            System.err.println("*** Ignoring corrupt dependencies file: " + depFile + " " +
                    ((ParseError) result).errorStringWithLineNumbers(depFile));
            return null;
         }
         else
            deps = (DependencyFile) ParseUtil.getParseResult(result);

         for (DependencyEntry dent: deps.depList) {
            dent.layer = srcEnt.layer;
            if (!dent.isDirectory && dent.fileDeps != null) {
               for (LayerDependencies layerEnt: dent.fileDeps) {
                  Layer currentLayer = sys.getLayerByName(layerEnt.layerName);
                  // Something in the layers we are using changed so recompute all
                  if (currentLayer == null) {
                     System.err.println("Warning: layer: " + layerEnt.layerName + " referenced in dependencies: " + depFile + " but is no longer a system layer.");
                     continue;
                  }

                  // We keep these sorted by the layer position for clarity in reading
                  layerEnt.position = currentLayer.layerPosition;
                  for (IString dependent:layerEnt.fileList) {
                     String dependentStr = dependent.toString();
                     File absSrcFile = currentLayer.findSrcFile(dependentStr, true);
                     if (absSrcFile == null)
                        dent.invalid = true;
                     else {
                        if (dent.srcEntries == null)
                           dent.srcEntries = new ArrayList<SrcEntry>();
                        dent.srcEntries.add(new SrcEntry(currentLayer, absSrcFile.getPath(), dependentStr));
                     }
                  }
               }
            }
         }
      }
      catch (IllegalArgumentException exc) {
         System.err.println("*** Error reading dependencies: " + exc);
      }
      if (deps != null)
         deps.file = depFile;
      return deps;
   }
}
