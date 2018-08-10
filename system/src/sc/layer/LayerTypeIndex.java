/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.obj.SyncMode;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Stores the information we persist in the type-index for a given layer.
*/
public class LayerTypeIndex implements Serializable {
   String layerPathName; // Path to layer directory
   String layerDirName; // the layer name
   String layerBaseName; // base name of layer definition file
   String packagePrefix;
   CodeType codeType;
   SyncMode syncMode;
   boolean finalLayer;
   boolean buildSeparate;
   boolean buildLayer;
   boolean annotationLayer;
   String defaultModifier;
   String[] baseLayerNames; // List of base layer names for this layer
   String[] topLevelSrcDirs; // Top-level srcDirs as registered after starting the layer
   /** Type name to the type index information we store for that type */
   HashMap<String,TypeIndexEntry> layerTypeIndex = new HashMap<String,TypeIndexEntry>();
   /** File name to the type index info we store for that file */
   HashMap<String,TypeIndexEntry> fileIndex = new HashMap<String,TypeIndexEntry>();
   String[] langExtensions; // Any languages registered by this layer - need to know these are source

   public List<String> excludeRuntimes = null;
   public List<String> includeRuntimes = null;

   public List<String> excludeProcesses = null;
   public List<String> includeProcesses = null;

   public boolean hasDefinedRuntime = false;
   String definedRuntimeName;

   public boolean hasDefinedProcess = false;
   String definedProcessName;

   transient Layer indexLayer;

   public boolean updateTypeName(String oldTypeName, String newTypeName) {
      TypeIndexEntry ent = layerTypeIndex.remove(oldTypeName);
      if (ent != null) {
         layerTypeIndex.put(newTypeName, ent);
         ent.typeName = newTypeName;
         return true;
      }
      return false;
   }

   public boolean removeTypeName(String typeName) {
      return layerTypeIndex.remove(typeName) != null;
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

   public TypeIndexEntry getTypeIndexEntryForPath(String pathName) {
      int pathLen = pathName.length();
      int layerPathLen = layerPathName.length();
      if (pathLen > layerPathLen + 1 && pathName.startsWith(layerPathName)) {
         String fileName = pathName.substring(layerPathName.length() + 1);
         if (fileName.length() > 0) {
            return fileIndex.get(pathName);
         }
      }
      return null;
   }

   public Layer getIndexLayer() {
      if (indexLayer != null)
         return indexLayer;

      indexLayer = new Layer();
      indexLayer.indexLayer = true;
      indexLayer.layerPathName = layerPathName;
      indexLayer.baseLayerNames = baseLayerNames == null ? null : new ArrayList<String>(Arrays.asList(baseLayerNames));
      indexLayer.layerDirName = layerDirName;
      indexLayer.layerBaseName = layerBaseName;
      indexLayer.packagePrefix = packagePrefix;
      indexLayer.codeType = codeType;
      indexLayer.syncMode = syncMode;
      indexLayer.finalLayer = finalLayer;
      indexLayer.defaultModifier = defaultModifier;
      indexLayer.hasDefinedRuntime = hasDefinedRuntime;
      indexLayer.hasDefinedProcess = hasDefinedProcess;
      indexLayer.definedRuntimeName = definedRuntimeName;
      indexLayer.definedProcessName = definedProcessName;
      indexLayer.excludeRuntimes = excludeRuntimes;
      indexLayer.excludeProcesses = excludeProcesses;
      indexLayer.buildSeparate = buildSeparate;
      indexLayer.buildLayer = buildLayer;

      // Index layers are closed for the icon
      indexLayer.closed = true;

      return indexLayer;
   }

   public void initFrom(Layer layer) {
      layerPathName = layer.getLayerPathName();
      layerDirName = layer.layerDirName;
      baseLayerNames = layer.baseLayerNames == null ? null : layer.baseLayerNames.toArray(new String[layer.baseLayerNames.size()]);
      layerBaseName = layer.layerBaseName;
      packagePrefix = layer.packagePrefix;
      codeType = layer.codeType;
      syncMode = layer.syncMode;
      finalLayer = layer.finalLayer;
      defaultModifier = layer.defaultModifier;
      hasDefinedRuntime = layer.hasDefinedRuntime;
      hasDefinedProcess = layer.hasDefinedProcess;
      definedRuntimeName = layer.definedRuntimeName;
      definedProcessName = layer.definedProcessName;
      excludeRuntimes = layer.excludeRuntimes;
      excludeProcesses = layer.excludeProcesses;
      buildSeparate = layer.buildSeparate;
      buildLayer = layer.buildLayer;
   }
}
