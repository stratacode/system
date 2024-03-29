/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.lang.java.DeclarationType;
import sc.type.CTypeUtil;
import sc.util.FileUtil;

import java.util.*;

/**
 * Created to store the complete index info for a set of layers.  We generally store one instance for 'active' layers
 * and another for 'inactive' layers in a project.
 */
public class LayerListTypeIndex {
   LayeredSystem sys;
   // NOTE: this points directly to either LayeredSystem.layers or LayeredSystem.inactiveLayers - it's not a copy
   List<Layer> layersList;

   // A cached list of Layer objects that are not initialized, started or anything - just basic properties are set - such as layerPathName, etc. derived from the index - used for tooling that want to display a combination of loaded inactive layers and layers in the inactive index
   transient ArrayList<Layer> indexLayersCache;

   /** Global typeIndex which maps from layerName to the Map of typeName to TypeIndexEntry entries each of which maps to the Layer.layerTypeIndex map in each layer.  We'll accumulate type index's over both active and inactive layers */
   HashMap<String, LayerTypeIndex> typeIndex;

   /** Map from typeName to the set of types which extend this type.  This is refreshed, synchronized to the source for use by the IDE.  See subTypesByName for the data structure used by the dynamic runtime. */
   HashMap<String, LinkedHashMap<String,TypeIndexEntry>> subTypeIndex = new HashMap<String,LinkedHashMap<String,TypeIndexEntry>>();
   /** Map from typeName to the list of TypeIndexEntry entries which define this type. */
   HashMap<String, ArrayList<TypeIndexEntry>> modifyTypeIndex = new HashMap<String,ArrayList<TypeIndexEntry>>();

   private boolean reverseIndexBuilt = false;
   private boolean reverseIndexValid = false;

   LayerOrderIndex orderIndex;

   String typeIndexIdent;

   public LayerListTypeIndex(LayeredSystem sys, List<Layer> layers, String typeIndexIdent) {
      this.sys = sys;
      this.layersList = layers;
      this.typeIndex = new LinkedHashMap<String, LayerTypeIndex>();
      this.typeIndexIdent = typeIndexIdent;
      if (this.orderIndex == null)
         this.orderIndex = new LayerOrderIndex();
   }

   public void clearReverseTypeIndex() {
      subTypeIndex.clear();
      modifyTypeIndex.clear();
      reverseIndexBuilt = false;
      reverseIndexValid = false;
      if (sys != null && sys.getMainLayeredSystem().allNames != null)
         System.out.println("*** clearReverseTypeIndex");
      clearAllNames();
   }

   private void clearAllNames() {
      if (sys == null)
         return;
      LayeredSystem msys = sys.getMainLayeredSystem();
      if (msys.allNames != null)
         System.out.println("*** Clearing all names");
      msys.allNames = null;
   }

   public void clearTypeIndex() {
      if (sys != null && sys.getMainLayeredSystem().allNames != null)
         System.out.println("*** clearTypeIndex");
      typeIndex.clear();
      sys = null;
      clearAllNames();
      // do not clear layersList here - it is the list managed by the LayeredSystems
   }

   public boolean loadFromDir(String typeIndexDir) {
      orderIndex = LayerOrderIndex.readFromFile(typeIndexDir, sys == null ? null : sys.messageHandler);
      if (orderIndex == null) {
         orderIndex = new LayerOrderIndex();
         return false;
      }
      return true;
   }

   public void saveToDir(String typeIndexDir) {
      orderIndex.saveToDir(typeIndexDir);
   }

   private void visitIndexEntry(String layerName, LayerTypeIndex lti, HashSet<String> visitedLayers, LayeredSystem sys) {
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
            if (tind == null || tind.layerName == null || entry == null || tind.typeName == null) {
               System.err.println("*** Invalid type index entry!");
               continue;
            }
            if (tind.layerName.equals(entry.layerName) && tind.typeName.equals(entry.typeName))
               break;

            // Update the layer positions if we've created the layered system.  If not, we'll use the order defined from when this type index was generated
            /*
            if (sys != null) {
               Layer layer = sys.getInactiveLayer(tind.layerName, false, false, true, true);
               if (layer != null) {
                  if (layer.layerPosition != tind.layerPosition)
                     System.out.println("***");
                  tind.layerPosition = layer.layerPosition;
               }
               else
                  System.out.println("***");
               layer = sys.getInactiveLayer(entry.layerName, false, false, true, true);
               if (layer != null) {
                  if (layer.layerPosition != entry.layerPosition)
                     System.out.println("***");
                  entry.layerPosition = layer.layerPosition;
               }
               else
                  System.out.println("***");
            }
            */
            int tindPos = orderIndex.getLayerPosition(tind.layerName);
            int entPos = orderIndex.getLayerPosition(entry.layerName);
            if (tindPos == -1)
               System.out.println("*** Missing layer index position for: " + tind.layerName);
            if (entPos == -1)
               System.out.println("*** Missing layer index position for: " + entry.layerName);

            if (tindPos > entPos && (curPos == -1 || tindPos < curPos)) {
               curPos = tindPos;
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

   public void refreshReverseTypeIndex(LayeredSystem sys) {
      if (reverseIndexValid)
         return;
      reverseIndexBuilt = false;
      if (sys != null && sys.getMainLayeredSystem().allNames != null)
         System.out.println("*** refreshReverseTypeIndex");
      //clearAllNames(); - not sure why we would do this here
      buildReverseTypeIndex(sys);
   }

   /** Optional layered system - used to determined the layer positions used for the type index if present */
   public void buildReverseTypeIndex(LayeredSystem sys) {
      if (reverseIndexBuilt)
         return;
      reverseIndexBuilt = true;

      System.out.println("Start building reverseTypeIndex");
      HashSet<String> visitedLayers = new HashSet<String>();
      for (Map.Entry<String,LayerTypeIndex> typeIndexEntry:typeIndex.entrySet()) {
         String layerName = typeIndexEntry.getKey();
         LayerTypeIndex lti = typeIndexEntry.getValue();

         if (lti.baseLayerNames != null) {
            for (String baseLayerName: lti.baseLayerNames) {
               LayerTypeIndex baseLayerIndex = typeIndex.get(baseLayerName);
               if (baseLayerIndex != null)
                  visitIndexEntry(baseLayerName, baseLayerIndex, visitedLayers, sys);
               // else - probably in another runtime's index...
            }
         }
         visitIndexEntry(layerName, lti, visitedLayers, sys);
      }
      System.out.println("end building reverseTypeIndex");
      reverseIndexValid = true;
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
      reverseIndexValid = false;
   }

   public void removeTypeName(String oldTypeName) {
      TreeSet<String> layerNamesToSave = new TreeSet<String>();
      for (Map.Entry<String, LayerTypeIndex> ent:typeIndex.entrySet()) {
         LayerTypeIndex layerIndex = ent.getValue();
         if (layerIndex.removeTypeName(oldTypeName)) {
            layerNamesToSave.add(ent.getKey());
         }
      }
      modifyTypeIndex.remove(oldTypeName);
      subTypeIndex.remove(oldTypeName);
      saveLayerTypeIndexes(layerNamesToSave);
      reverseIndexValid = false;
      System.out.println("*** removeTypeName: " + oldTypeName);
      // We are going to allow the allNames index to hold even type names that have been removed because it's expensive to refresh it and right now the IDE validates the names anyway
      //clearAllNames();
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
      reverseIndexValid = false;
   }

   private void saveLayerTypeIndexes(TreeSet<String> layerNamesToSave) {
      for (String layerNameToSave:layerNamesToSave) {
         Layer layerToSave = sys.lookupInactiveLayer(layerNameToSave, false, true);
         if (layerToSave != null && layerToSave.isStarted()) {
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

   public TypeIndexEntry getTypeIndexEntryForPath(String pathName) {
      if (typeIndex != null) {
         for (LayerTypeIndex layerTypeIndex:typeIndex.values()) {
            TypeIndexEntry ent = layerTypeIndex.getTypeIndexEntryForPath(pathName);
            if (ent != null)
               return ent;
         }
      }
      return null;
   }

   public LayerTypeIndex getTypeIndex(String layerName) {
      if (typeIndex != null)
         return typeIndex.get(layerName);
      return null;
   }

   public void addLayerTypeIndex(String layerName, LayerTypeIndex layerTypeIndex) {
      LayerTypeIndex newIndex = typeIndex.put(layerName, layerTypeIndex);
      reverseIndexValid = false;
      if (sys != null) {
         if (newIndex == null || !newIndex.equals(layerTypeIndex)) {
            // Append any new names from this index. We are not worried now about removed types since they will be validated
            sys.addAllNamesForLayerTypeIndex(layerTypeIndex);
         }
      }
   }

   public void removeLayerTypeIndex(String layerName) {
      typeIndex.remove(layerName);
      reverseIndexValid = false;
      if (sys != null && sys.getMainLayeredSystem().allNames != null)
         System.out.println("*** Remove layer type index: " + layerName);
      //clearAllNames();
   }

   public void layerTypeIndexChanged(boolean namesChanged, String typeName, TypeIndexEntry newEntry) {
      reverseIndexValid = false;
      if (namesChanged && sys != null) {
         sys.addAllNamesForIndexEntry(typeName, newEntry);
      }
   }

   /**
    * Returns the list of all layers in this type index.  If sys is not-null, any normal inactiveLayers
    * defined there are returned before we return the IndexLayer defined by the info we keep in the type
    * index.
    */
   public List<Layer> getIndexLayers(LayeredSystem sys) {
      if (indexLayersCache != null)
         return indexLayersCache;

      synchronized (this) {
         ArrayList<String> indexNames = orderIndex.inactiveLayerNames;
         indexLayersCache = new ArrayList<Layer>(indexNames.size());
         for (int i = 0; i < indexNames.size(); i++) {
            String indexName = indexNames.get(i);
            Layer layer = null;
            if (sys != null) {
               layer = sys.lookupInactiveLayer(indexName, false, true);
            }
            if (layer == null) {
               LayerTypeIndex layerIndexInfo = typeIndex.get(indexName);
               if (layerIndexInfo != null) {
                  layer = layerIndexInfo.getIndexLayer();
               }
            }
            if (layer != null)
               indexLayersCache.add(layer);
         }
         return indexLayersCache;
      }
   }

   public synchronized void clearIndexLayersCache() {
      indexLayersCache = null;
   }

   public final static int MAX_NAMES_IN_INDEX = 10000000;

   public Set<String> getAllNames() {
      HashSet<String> allNames = new HashSet<String>();
      addMatchingGlobalNames("", allNames, false, null, false, MAX_NAMES_IN_INDEX);
      return allNames;
   }

   public boolean addMatchingGlobalNames(String prefix, Set<String> candidates, boolean retFullTypeName, Layer refLayer, boolean annotTypes, int max) {
      if (sys.writeLocked == 0) {
         System.err.println("*** Modifying type index without write lock");
         new Throwable().printStackTrace();
      }

      // For each type in the type index, add the type if it matches
      for (Map.Entry<String,LayerTypeIndex> typeIndexEnt:typeIndex.entrySet()) {
         //String layerName = typeIndexEnt.getKey();
         LayerTypeIndex layerTypeIndex = typeIndexEnt.getValue();
         String layerName = typeIndexEnt.getKey();

         if (refLayer != null && sys != null && layerName != null) {
            // Using lookup here so we only look through layers that have been loaded.  Otherwise there's a concurrent modification exception as we modify this index
            Layer indexLayer = sys.lookupInactiveLayer(layerName, true, true);
            // Only search layers which this layer can depend upon
            if (indexLayer == null || refLayer == Layer.ANY_INACTIVE_LAYER || refLayer == Layer.ANY_LAYER || (!refLayer.getLayerName().equals(indexLayer.getLayerName()) && !refLayer.extendsLayer(indexLayer)))
               continue;
         }
         if (!layerTypeIndex.addMatchingGlobalNames(prefix, candidates, retFullTypeName, annotTypes, max))
            return false;
      }
      // Indexing layers as types but only with the full type name
      if (layersList != null && !annotTypes) {
         for (Layer inactiveLayer : layersList) {
            String typeName = inactiveLayer.layerDirName;
            if (typeName.startsWith(prefix)) {
               candidates.add(typeName);
               if (candidates.size() >= max)
                  return false;
            }
         }
      }
      return true;
   }

}
