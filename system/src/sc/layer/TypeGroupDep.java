/*
 * Copyright (c) 2018. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

class TypeGroupDep {
   String relFileName;
   String typeName;
   String typeGroupName;
   Layer forLayer;

   TypeGroupDep(Layer forLayer, String relFn, String tn, String tgn) {
      this.forLayer = forLayer;
      relFileName = relFn;
      typeName = tn;
      typeGroupName = tgn;
   }

   boolean processForBuildLayer(Layer genLayer) {
      return forLayer == null || (forLayer.layerPosition < genLayer.layerPosition && (genLayer == genLayer.layeredSystem.buildLayer || genLayer.extendsLayer(forLayer)));
   }
}
