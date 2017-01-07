package sc.layer;

import sc.layer.deps.DependencyFile;

import java.io.File;
import java.util.LinkedHashSet;

class SrcDirEntry {
   DependencyFile deps;
   File depFile;
   Layer layer;
   boolean depsChanged;
   boolean fileError;
   boolean anyError;
   LinkedHashSet<SrcEntry> toGenerate;
   LinkedHashSet<ModelToTransform> modelsToTransform;
   public String srcPath;

   public String toString() {
      return "layer: " + layer + "/" + srcPath;
   }
}
