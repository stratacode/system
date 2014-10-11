/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

/**
 */
public class SysTypeIndex {
   LayerListTypeIndex activeTypeIndex;
   LayerListTypeIndex inactiveTypeIndex;

   public SysTypeIndex(LayeredSystem sys) {
      activeTypeIndex = new LayerListTypeIndex(sys, sys == null ? null : sys.layers);
      inactiveTypeIndex = new LayerListTypeIndex(sys, sys == null ? null : sys.inactiveLayers);
   }

   public void clearReverseTypeIndex() {
      //activeTypeIndex.clearReverseTypeIndex();
      inactiveTypeIndex.clearReverseTypeIndex();
   }

   public void buildReverseTypeIndex() {
      // TODO: not used - we have this same info in the sub-types now
      //activeTypeIndex.buildReverseTypeIndex();
      inactiveTypeIndex.buildReverseTypeIndex();
   }

   public void setSystem(LayeredSystem sys) {
      activeTypeIndex.sys = sys;
      activeTypeIndex.layersList = sys.layers;
      inactiveTypeIndex.layersList = sys.inactiveLayers;
   }
}
