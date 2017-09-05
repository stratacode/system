/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import java.io.File;
import java.io.Serializable;
import java.util.HashMap;

/**
 * Stores the information we persist in the type-index for a given layer.
*/
public class LayerTypeIndex implements Serializable {
   String layerPathName; // Path to layer directory
   String[] baseLayerNames; // List of base layer names for this layer
   String[] topLevelSrcDirs; // Top-level srcDirs as registered after starting the layer
   /** Type name to the type index information we store for that type */
   HashMap<String,TypeIndexEntry> layerTypeIndex = new HashMap<String,TypeIndexEntry>();
   /** File name to the type index info we store for that file */
   HashMap<String,TypeIndexEntry> fileIndex = new HashMap<String,TypeIndexEntry>();
   String[] langExtensions; // Any languages registered by this layer - need to know these are source

   public boolean updateTypeName(String oldTypeName, String newTypeName) {
      TypeIndexEntry ent = layerTypeIndex.remove(oldTypeName);
      if (ent != null) {
         layerTypeIndex.put(newTypeName, ent);
         ent.typeName = newTypeName;
         return true;
      }
      return false;
   }

   public boolean updateFileName(String oldFileName, String newFileName) {
      TypeIndexEntry ent = fileIndex.remove(oldFileName);
      if (ent != null) {
         ent.fileName = newFileName;
         fileIndex.put(newFileName, ent);
         return true;
      }
      return false;
   }

   /**
    * Refresh the layer type index - look for files that exist on the file system that are not in the index and
    * files which have changed.  This is called on the main layered system since the peer system which owns this layer
    * may not have been created yet.  If we need to create the layer, be careful to use the layer's layeredSystem so
    * we get the one which is managing this index (since each type index is per-layered system).
    */
   LayerTypeIndex refreshLayerTypeIndex(LayeredSystem sys, String layerName, long lastModified, RefreshTypeIndexContext refreshCtx) {
      File layerFile = sys.getLayerFile(layerName);
      if (layerFile == null) {
         sys.warning("Layer found in type index - not in the layer path: " + layerName + ": " + sys.layerPathDirs);
         return null;
      }
      String pathName = layerFile.getParentFile().getPath();

      // Excluded in the project - do not refresh it
      if (sys.externalModelIndex != null && sys.externalModelIndex.isExcludedFile(pathName))
         return null;

      if (!pathName.equals(layerPathName)) {
         return sys.buildLayerTypeIndex(layerName);
      }

      /* We want to refresh the layers in layer order so that we set up the modify inheritance types properly */
      if (baseLayerNames != null) {
         for (String baseLayer:baseLayerNames) {
            sys.refreshLayerTypeIndexFile(baseLayer, refreshCtx, true);
         }
      }

      for (String srcDir:topLevelSrcDirs) {
         if (!Layer.isBuildDirPath(srcDir)) {
            File srcDirFile = new File(srcDir);

            if (!srcDirFile.isDirectory()) {
               sys.warning("srcDir removed for layer: " + layerName + ": " + srcDir + " rebuilding index");
               return sys.buildLayerTypeIndex(layerName);
            }

            sys.refreshLayerTypeIndexDir(srcDirFile, "", layerName, this, lastModified);
         }
         // else - TODO: do we need to handle this case?
      }
      return this;
   }
}
