/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import java.util.*;

/**
 * Created to store the complete index info for a set of layers.  We generally store one instance for 'active' layers
 * and another for 'inactive' layers in a project.
 */
public class LayerListTypeIndex {
   LayeredSystem sys;
   // NOTE: this points directly to either LayeredSystem.layers or LayeredSystem.inactiveLayers - it's not a copy
   List<Layer> layersList;

   /** Global typeIndex which maps from layerName to the Map of typeName to TypeIndex entries each of which maps to the Layer.layerTypeIndex map in each layer.  We'll accumulate type index's over both active and inactive layers */
   HashMap<String, LayerTypeIndex> typeIndex;

   /** Map from typeName to the set of types which extend this type.  This is refreshed, synchronized to the source for use by the IDE.  See subTypesByName for the data structure used by the dynamic runtime. */
   HashMap<String, LinkedHashMap<String,TypeIndex>> subTypeIndex = new HashMap<String,LinkedHashMap<String,TypeIndex>>();
   /** Map from typeName to the list of TypeIndex entries which define this type. */
   HashMap<String, ArrayList<TypeIndex>> modifyTypeIndex = new HashMap<String,ArrayList<TypeIndex>>();

   public LayerListTypeIndex(LayeredSystem sys, List<Layer> layers) {
      this.sys = sys;
      this.layersList = layers;
      this.typeIndex = new LinkedHashMap<String, LayerTypeIndex>();
   }

   public void clearReverseTypeIndex() {
      subTypeIndex.clear();
      modifyTypeIndex.clear();
   }

   public void clearTypeIndex() {
      typeIndex.clear();
      sys = null;
      // do not clear layersList here - it is the list managed by the LayeredSystems
   }

   private void visitIndexEntry(String layerName, LayerTypeIndex lti, HashSet<String> visitedLayers) {
      if (visitedLayers.contains(layerName))
         return;

      visitedLayers.add(layerName);
      if (lti == null) {
         System.err.println("*** Missing layer type index for layer: " + layerName);
         return;
      }
      HashMap<String,TypeIndex> layerTypeIndexMap = lti.layerTypeIndex;

      for (Map.Entry<String,TypeIndex> typeEnt:layerTypeIndexMap.entrySet()) {
         TypeIndex layerTypeIndex = typeEnt.getValue();
         String layerTypeName = typeEnt.getKey();
         // Build the reverse list - for each
         if (layerTypeIndex.baseTypes != null) {
            for (String baseType:layerTypeIndex.baseTypes) {
               LinkedHashMap<String,TypeIndex> subTypes = subTypeIndex.get(baseType);
               if (subTypes == null) {
                  subTypes = new LinkedHashMap<String,TypeIndex>();
                  subTypeIndex.put(baseType, subTypes);
               }

               subTypes.put(layerTypeName, layerTypeIndex);
            }
         }
         ArrayList<TypeIndex> modifyTypes = modifyTypeIndex.get(layerTypeName);
         if (modifyTypes == null) {
            modifyTypes = new ArrayList<TypeIndex>();
            modifyTypeIndex.put(layerTypeName, modifyTypes);
         }
         int ix;
         for (ix = 0; ix < modifyTypes.size(); ix++) {
            TypeIndex tind = modifyTypes.get(ix);
            if (tind.layerName.equals(layerTypeIndex.layerName) && tind.typeName.equals(layerTypeIndex.typeName))
               break;
         }
         if (ix == modifyTypes.size())
            modifyTypes.add(layerTypeIndex);
         else
            modifyTypes.set(ix, layerTypeIndex);
      }
   }

   public void buildReverseTypeIndex() {
      HashSet<String> visitedLayers = new HashSet<String>();
      for (Map.Entry<String,LayerTypeIndex> typeIndexEntry:typeIndex.entrySet()) {
         String layerName = typeIndexEntry.getKey();
         LayerTypeIndex lti = typeIndexEntry.getValue();

         if (lti.baseLayerNames != null) {
            for (String baseLayerName: lti.baseLayerNames) {
               LayerTypeIndex baseLayerIndex = typeIndex.get(baseLayerName);
               if (baseLayerIndex != null)
                  visitIndexEntry(baseLayerName, baseLayerIndex, visitedLayers);
               // else - probably in another runtime's index...
            }
         }
         visitIndexEntry(layerName, lti, visitedLayers);
      }
   }

   public void clear() {
      clearTypeIndex();
      clearReverseTypeIndex();
   }
}
