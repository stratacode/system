/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.lang.java.TypeDeclaration;
import sc.type.CTypeUtil;
import sc.util.StringUtil;

import java.util.*;

/**
 */
public class SysTypeIndex {
   LayerListTypeIndex activeTypeIndex;
   LayerListTypeIndex inactiveTypeIndex;
   boolean needsSave = false;

   public SysTypeIndex(LayeredSystem sys, String typeIndexIdent) {
      activeTypeIndex = new LayerListTypeIndex(sys, sys == null ? null : sys.layers, typeIndexIdent);
      inactiveTypeIndex = new LayerListTypeIndex(sys, sys == null ? null : sys.inactiveLayers, typeIndexIdent);
   }

   public void clearReverseTypeIndex() {
      //activeTypeIndex.clearReverseTypeIndex();
      inactiveTypeIndex.clearReverseTypeIndex();
   }

   public void buildReverseTypeIndex(LayeredSystem sys) {
      if (sys != null) {
         if (sys.options.typeIndexMode == TypeIndexMode.Rebuild) {
            LayerOrderIndex loi = inactiveTypeIndex.orderIndex;
            loi.refreshAll(sys, true);
         }
      }

      // TODO: not using the activeTypeIndex now... maybe just remove it?  We have this same info maintained in separate apis stored on the layered system.
      //activeTypeIndex.buildReverseTypeIndex();
      inactiveTypeIndex.buildReverseTypeIndex(sys);
   }

   public boolean loadFromDir(String typeIndexDir) {
      return inactiveTypeIndex.loadFromDir(typeIndexDir);
   }

   public void saveToDir(String typeDir) {
      inactiveTypeIndex.saveToDir(typeDir);
      needsSave = false;
   }

   public void setSystem(LayeredSystem sys) {
      activeTypeIndex.sys = sys;
      activeTypeIndex.layersList = sys.layers;
      inactiveTypeIndex.layersList = sys.inactiveLayers;

      if (!inactiveTypeIndex.typeIndexIdent.equals(sys.getTypeIndexIdent()))
         System.err.println("*** mismatching system and type index!");
   }

   /** Adds the TypeDeclarations of any matching types.  The LayeredSystem passed may be the system or the main system so be careful to use the system from the layer to retrieve the type. */
   public void addModifiedTypesOfType(String processIdent, LayeredSystem sys, TypeDeclaration type, boolean before, TreeSet<String> checkedTypes, ArrayList<TypeDeclaration> res) {
      Layer typeLayer = type.getLayer();
      String typeName = type.getFullTypeName();
      ArrayList<TypeIndexEntry> indexEntries = inactiveTypeIndex.modifyTypeIndex.get(typeName);
      int layerIx = -1;

      // First find the current type in the list we've indexed.
      if (indexEntries != null) {
         checkedTypes.add(processIdent);
         int idxPos = 0;
         for (TypeIndexEntry idx:indexEntries) {
            if (idx.layerName != null && typeLayer != null && idx.layerName.equals(typeLayer.getLayerName()) && StringUtil.equalStrings(processIdent, idx.processIdent)) {
               layerIx = idxPos;
               break;
            }
            idxPos++;
         }

         if (layerIx == -1) {
            // Since we search each layered system for each type, we may just not find this type in this layered system so add no entries from it.
            return;
         }

         // Now add each successive entry - those are the possible modifiers.
         for (int i = before ? 0 : layerIx + 1; i < (before ? layerIx : indexEntries.size()); i++) {
            TypeIndexEntry modTypeIndexEntry = indexEntries.get(i);

            // TODO: add a mode which creates stub type declarations when the type has not been loaded so we do not have to parse so much code in this operation.
            // we can use the replaced logic to swap in the new one once it's fetched and/or just populate this one when we need to by parsing it's file.

            // Make sure the index entry matches this process before we go and add it.
            if (StringUtil.equalStrings(modTypeIndexEntry.processIdent, processIdent)) {
               Layer modLayer = sys.getActiveOrInactiveLayerByPath(modTypeIndexEntry.layerName, null, false, true, true);
               if (modLayer == null) {
                  System.err.println("*** Warning unable to find modifying layer: " + modTypeIndexEntry.layerName + " - skipping index entyr");
               } else {
                  // The modLayer may not be in the right system but perhaps we've created that system to lookup this layer.  If so, get the right system
                  // and use it
                  Layer typeLayerInSystem;
                  if (!modLayer.layeredSystem.getProcessIdent().equals(processIdent)) {
                     LayeredSystem peerSys = sys.getPeerLayeredSystem(processIdent);
                     if (peerSys == null)
                        continue;
                     modLayer = typeLayerInSystem = peerSys.getPeerLayerFromRemote(modLayer);
                     if (modLayer == null || modLayer == Layer.ANY_INACTIVE_LAYER)
                        continue;
                  }
                  else
                     typeLayerInSystem = sys.getActiveOrInactiveLayerByPath(type.getLayer().getLayerName(), null, false, true, true);
                  if (typeLayerInSystem != null) {
                     Layer peerLayer = typeLayerInSystem.layeredSystem == modLayer.layeredSystem ? typeLayerInSystem : modLayer.layeredSystem.getPeerLayerFromRemote(typeLayerInSystem);
                     if (peerLayer == null)
                        System.err.println("*** Unable to find layer in layer in runtime: " + sys.getProcessIdent() + " for: " + typeLayerInSystem);
                     else {
                        TypeDeclaration modType = (TypeDeclaration) modLayer.layeredSystem.getSrcTypeDeclaration(typeName, modLayer.getNextLayer(), true, false, true, peerLayer, type.isLayerType || type.isLayerComponent());
                        if (modType != null) {
                           res.add(modType);
                        }
                     }
                  } else
                     System.err.println("*** Unable to map layer name from system to the other");
               }
            }
         }
      }
   }

   ArrayList<TypeIndexEntry> getTypeIndexes(String typeName) {
      return inactiveTypeIndex.modifyTypeIndex.get(typeName);
   }


   public void addMatchingGlobalNames(String prefix, Set<String> candidates, boolean retFullTypeName, Layer refLayer) {
      if (inactiveTypeIndex.sys.writeLocked == 0) {
         System.err.println("*** Modifying type index without write lock");
         new Throwable().printStackTrace();
      }

      // For each type in the type index, add the type if it matches
      for (Map.Entry<String,LayerTypeIndex> typeIndexEnt:inactiveTypeIndex.typeIndex.entrySet()) {
         //String layerName = typeIndexEnt.getKey();
         LayerTypeIndex layerTypeIndex = typeIndexEnt.getValue();
         String layerName = typeIndexEnt.getKey();

         if (inactiveTypeIndex.sys == null) {
            continue;
         }

         if (refLayer != null && inactiveTypeIndex.sys != null && layerName != null) {
            // Using lookup here so we only look through layers that have been loaded.  Otherwise there's a concurrent modification exception as we modify this index
            Layer indexLayer = inactiveTypeIndex.sys.lookupInactiveLayer(layerName, true, true);
            // Only search layers which this layer can depend upon
            if (indexLayer == null || refLayer == Layer.ANY_INACTIVE_LAYER || refLayer == Layer.ANY_LAYER || (!refLayer.getLayerName().equals(indexLayer.getLayerName()) && !refLayer.extendsLayer(indexLayer)))
               continue;
         }
         HashMap<String,TypeIndexEntry> layerTypeMap = layerTypeIndex.layerTypeIndex;
         for (Map.Entry<String,TypeIndexEntry> typeEnt:layerTypeMap.entrySet()) {
            String typeName = typeEnt.getKey();
            String className = CTypeUtil.getClassName(typeName);
            if (className.startsWith(prefix)) {
               if (retFullTypeName)
                  candidates.add(typeName);
               else
                  candidates.add(className);
            }
         }
      }
      // Indexing layers as types but only with the full type name
      if (inactiveTypeIndex.layersList != null) {
         for (Layer inactiveLayer : inactiveTypeIndex.layersList) {
            String typeName = inactiveLayer.layerDirName;
            if (typeName.startsWith(prefix)) {
               candidates.add(typeName);
            }
         }
      }
   }

   public void clearActiveLayers() {
      activeTypeIndex.clear();
   }

   public void clearInactiveLayers() {
      inactiveTypeIndex.clear();
   }

   public void updateTypeName(String oldTypeName, String newTypeName) {
      inactiveTypeIndex.updateTypeName(oldTypeName, newTypeName);
   }

   public void fileRenamed(String oldFileName, String newFileName) {
      inactiveTypeIndex.updateFileName(oldFileName, newFileName);
   }

   public StringBuilder dumpCacheStats() {
      StringBuilder sb = new StringBuilder();
      if (activeTypeIndex != null) {
         sb.append("  activeTypeIndex: ");
         sb.append(activeTypeIndex.dumpCacheStats());
         sb.append("\n");
      }
      if (inactiveTypeIndex != null) {
         sb.append("  inActiveTypeIndex: ");
         sb.append(inactiveTypeIndex.dumpCacheStats());
         sb.append("\n");
      }
      sb.append("\n");
      return sb;
   }

   public void addDisabledLayer(String layerName) {
      if (inactiveTypeIndex != null)
         inactiveTypeIndex.orderIndex.disabledLayers.add(layerName);
   }

   public void addExcludedLayer(String layerName) {
      if (inactiveTypeIndex != null)
         inactiveTypeIndex.orderIndex.excludedLayers.add(layerName);
   }

   public void removeExcludedLayer(String layerName) {
      if (inactiveTypeIndex != null)
         inactiveTypeIndex.orderIndex.removeExcludedLayer(layerName);
   }

   public void refreshLayerOrder(LayeredSystem sys) {
      if (inactiveTypeIndex != null) {
         if (inactiveTypeIndex.orderIndex.refreshAll(sys, false))
            needsSave = true;
      }
   }

   public boolean excludesLayer(String layerName) {
      if (inactiveTypeIndex != null)
         return inactiveTypeIndex.orderIndex.excludesLayer(layerName);
      return false;
   }
}
