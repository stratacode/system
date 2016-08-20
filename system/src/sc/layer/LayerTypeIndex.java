/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.type.CTypeUtil;
import sc.util.FileUtil;

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
}
