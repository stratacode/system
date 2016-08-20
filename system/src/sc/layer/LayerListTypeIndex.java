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

   /** Global typeIndex which maps from layerName to the Map of typeName to TypeIndexEntry entries each of which maps to the Layer.layerTypeIndex map in each layer.  We'll accumulate type index's over both active and inactive layers */
   HashMap<String, LayerTypeIndex> typeIndex;

   /** Map from typeName to the set of types which extend this type.  This is refreshed, synchronized to the source for use by the IDE.  See subTypesByName for the data structure used by the dynamic runtime. */
   HashMap<String, LinkedHashMap<String,TypeIndexEntry>> subTypeIndex = new HashMap<String,LinkedHashMap<String,TypeIndexEntry>>();
   /** Map from typeName to the list of TypeIndexEntry entries which define this type. */
   HashMap<String, ArrayList<TypeIndexEntry>> modifyTypeIndex = new HashMap<String,ArrayList<TypeIndexEntry>>();

   private boolean reverseIndexBuilt = false;

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
      HashMap<String,TypeIndexEntry> layerTypeIndexMap = lti.layerTypeIndex;

      for (Map.Entry<String,TypeIndexEntry> typeEnt:layerTypeIndexMap.entrySet()) {
         TypeIndexEntry entry = typeEnt.getValue();
         String typeName = typeEnt.getKey();
         // Build the reverse list - for each
         if (entry.baseTypes != null) {
            for (String baseType: entry.baseTypes) {
               LinkedHashMap<String,TypeIndexEntry> subTypes = subTypeIndex.get(baseType);
               if (subTypes == null) {
                  subTypes = new LinkedHashMap<String,TypeIndexEntry>();
                  subTypeIndex.put(baseType, subTypes);
               }

               subTypes.put(typeName, entry);
            }
         }
         ArrayList<TypeIndexEntry> modifyTypes = modifyTypeIndex.get(typeName);
         if (modifyTypes == null) {
            modifyTypes = new ArrayList<TypeIndexEntry>();
            modifyTypeIndex.put(typeName, modifyTypes);
         }
         int ix;
         int insertIx = -1;
         int curPos = -1;
         for (ix = 0; ix < modifyTypes.size(); ix++) {
            TypeIndexEntry tind = modifyTypes.get(ix);
            if (tind.layerName.equals(entry.layerName) && tind.typeName.equals(entry.typeName))
               break;
            if (tind.layerPosition > entry.layerPosition && (curPos == -1 || tind.layerPosition < curPos)) {
               curPos = tind.layerPosition;
               insertIx = ix;
            }
         }
         if (ix == modifyTypes.size()) {
            if (insertIx == -1)
               modifyTypes.add(entry);
            else
               modifyTypes.add(insertIx, entry);
         }
         else
            modifyTypes.set(ix, entry);
      }
   }

   public void buildReverseTypeIndex() {
      if (reverseIndexBuilt)
         return;
      reverseIndexBuilt = true;
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

   public void updateTypeName(String oldTypeName, String newTypeName) {
      TreeSet<String> layerNamesToSave = new TreeSet<String>();
      for (Map.Entry<String, LayerTypeIndex> ent:typeIndex.entrySet()) {
         LayerTypeIndex layerIndex = ent.getValue();
         if (layerIndex.updateTypeName(oldTypeName, newTypeName)) {
            layerNamesToSave.add(ent.getKey());
         }
      }
      ArrayList<TypeIndexEntry> indexEntries = modifyTypeIndex.remove(oldTypeName);
      if (indexEntries != null) {
         modifyTypeIndex.put(newTypeName, indexEntries);
         for (TypeIndexEntry ent:indexEntries) {
            ent.typeName = newTypeName;
         }
      }
      LinkedHashMap<String,TypeIndexEntry> subTypes = subTypeIndex.remove(oldTypeName);
      if (subTypes != null)
         subTypeIndex.put(newTypeName, subTypes);

      for (LinkedHashMap<String,TypeIndexEntry> subTypeMap:subTypeIndex.values()) {
         TypeIndexEntry revEnt = subTypeMap.remove(oldTypeName);
         if (revEnt != null) {
            subTypeMap.put(newTypeName, revEnt);
         }
      }
      saveLayerTypeIndexes(layerNamesToSave);
   }

   public void updateFileName(String oldFileName, String newFileName) {
      TreeSet<String> layerNamesToSave = new TreeSet<String>();
      for (Map.Entry<String,LayerTypeIndex> ent:typeIndex.entrySet()) {
         LayerTypeIndex layerIndex = ent.getValue();
         if (layerIndex.updateFileName(oldFileName, newFileName)) {
            layerNamesToSave.add(ent.getKey());
         }
      }
      saveLayerTypeIndexes(layerNamesToSave);
   }

   private void saveLayerTypeIndexes(TreeSet<String> layerNamesToSave) {
      for (String layerNameToSave:layerNamesToSave) {
         Layer layerToSave = sys.lookupInactiveLayer(layerNameToSave, false, true);
         if (layerToSave != null) {
            layerToSave.saveTypeIndex();
         }
      }
   }

   public StringBuilder dumpCacheStats() {
      StringBuilder sb = new StringBuilder();
      sb.append(" numTypes: " + typeIndex.size());
      sb.append(" numSubTypes: " + subTypeIndex.size());
      sb.append(" numModTypes: " + modifyTypeIndex.size());
      return sb;
   }
}
