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
                  Layer typeLayerInSystem = sys.getActiveOrInactiveLayerByPath(type.getLayer().getLayerName(), null, false, true, true);
                  if (typeLayerInSystem != null) {
                     Layer peerLayer = typeLayerInSystem.layeredSystem == sys ? typeLayerInSystem : modLayer.layeredSystem.getPeerLayerFromRemote(typeLayerInSystem);
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


   public void addMatchingGlobalNames(String prefix, Set<String> candidates, boolean retFullTypeName) {
      if (inactiveTypeIndex.sys.writeLocked == 0) {
         System.err.println("*** Modifying type index without write lock");
         new Throwable().printStackTrace();
      }
      // For each type in the type index, add the type if it matches
      for (Map.Entry<String,LayerTypeIndex> typeIndexEnt:inactiveTypeIndex.typeIndex.entrySet()) {
         //String layerName = typeIndexEnt.getKey();
         LayerTypeIndex layerTypeIndex = typeIndexEnt.getValue();
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
}
