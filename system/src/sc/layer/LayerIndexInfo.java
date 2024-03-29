/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.type.CTypeUtil;

public class LayerIndexInfo {
   public String layerPathRoot; // The layer path where this layer lives
   public String layerDirName;   // The groupName.dirName of the layer
   public String packageName;  // the package prefix for the layer if any
   public String[] extendsLayers;

   Layer layer; // NOTE: includes only the model-part of the layer - the only part we have initialized for it to be part of the index.

   public LayeredSystem system;

   public String getExtendsPackageName() {
      if (extendsLayers == null)
         return null;
      for (String extName:extendsLayers) {
         Layer l = system.getLayerByDirName(extName);
         if (l != null) {
            return l.packagePrefix;
         }
         LayerIndexInfo olii = system.allLayerIndex.get(extName);
         // Try the local package name lookup: TODO: need to walk up the layer group hierarchy trying each prefix here?
         if (olii == null) {
            String layerGroup = CTypeUtil.getPackageName(layerDirName);
            if (layerGroup != null)
               olii = system.allLayerIndex.get(CTypeUtil.prefixPath(layerGroup, extName));
         }
         if (olii != null) {
            if (olii.packageName != null)
               return olii.packageName;
            String extPkg = olii.getExtendsPackageName();
            if (extPkg != null)
               return extPkg;
         }
         else {
            if (system.options.verbose)
               System.out.println("Layer: " + layerDirName + " extends layer: " + extName + " not found in found in layer path: " + layerPathRoot + " when  building the 'all layers' index");
         }
      }
      return null;
   }

   public String toString() {
      return "Layer index: " + layerDirName + " (pkg: " + packageName + ")";
   }
}
