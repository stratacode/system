/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer.deps;

import sc.lang.SemanticNode;
import sc.layer.Layer;
import sc.layer.SrcEntry;
import sc.parser.IString;
import sc.util.FileUtil;

import java.util.List;

public class DependencyEntry extends SemanticNode {
   public String fileName;
   public List<LayerDependencies> fileDeps;
   public List<IString> genFileNames;
   public boolean isDirectory, isError;

   public transient boolean invalid; // set to true when we can't find all of the files references
   public transient List<SrcEntry> srcEntries; // we copy over info from fileDeps after resolving the layer name
   public transient Layer layer; // the layer containing the file for this entry - set after parsing

   public int getChildNestingDepth()
   {
      if (parentNode != null)
         return parentNode.getChildNestingDepth() + 1;
      return 0;
   }

   public String getEntryFileName() {
      return FileUtil.unnormalize(fileName);
   }

   public String getGeneratedFileName(int index) {
      return FileUtil.unnormalize(genFileNames.get(index).toString());
   }

   public LayerDependencies getLayerDependencies(String layerName) {
      if (fileDeps == null)
         return null;

      for (LayerDependencies ld:fileDeps)
         if (ld.layerName.equals(layerName))
            return ld;
      return null;
   }

   public void addLayerDependencies(LayerDependencies layerDeps) {
      int i;
      for (i = 0; i < fileDeps.size(); i++)
         if (fileDeps.get(i).position < layerDeps.position)
            break;
      fileDeps.add(i,layerDeps);
   }
}
