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
      ArrayList<TypeIndex> indexEntries = inactiveTypeIndex.modifyTypeIndex.get(typeName);
      int layerIx = 0;

      // First find the current type in the list we've indexed.
      if (indexEntries != null) {
         checkedTypes.add(processIdent);
         for (TypeIndex idx:indexEntries) {
            if (idx.layerName != null && typeLayer != null && idx.layerName.equals(typeLayer.getLayerName()))
               break;
            layerIx++;
         }

         if (layerIx == indexEntries.size()) {
            // Since we search each layered system for each type, we may just not find this type in this layered system so add no entries from it.
            return;
         }

         // Now add each successive entry - those are the possible modifiers.
         for (int i = before ? 0 : layerIx+1; i < (before ? layerIx : indexEntries.size()); i++) {
            TypeIndex modTypeIndex = indexEntries.get(i);

            // TODO: add a mode which creates stub type declarations when the type has not been loaded so we do not have to parse so much code in this operation.
            // we can use the replaced logic to swap in the new one once it's fetched and/or just populate this one when we need to by parsing it's file.

            // Make sure the index entry matches this process before we go and add it.
            if (StringUtil.equalStrings(modTypeIndex.processIdent, sys.getProcessIdent())) {
               Layer modLayer = sys.getActiveOrInactiveLayerByPath(modTypeIndex.layerName, null, true);
               if (modLayer == null) {
                  System.err.println("*** Warning unable to find modifying layer: " + modTypeIndex.layerName + " - skipping index entyr");
               } else {
                  TypeDeclaration modType = (TypeDeclaration) modLayer.layeredSystem.getSrcTypeDeclaration(typeName, modLayer.getNextLayer(), true, false, true, type.getLayer(), type.isLayerType);
                  if (modType != null) {
                     res.add(modType);
                  }
               }
            }
         }
      }
   }

   ArrayList<TypeIndex> getTypeIndexes(String typeName) {
      return inactiveTypeIndex.modifyTypeIndex.get(typeName);
   }


   public void addMatchingGlobalNames(String prefix, Set<String> candidates, boolean retFullTypeName) {
      // For each type in the type index, add the type if it matches
      for (Map.Entry<String,LayerTypeIndex> typeIndexEnt:inactiveTypeIndex.typeIndex.entrySet()) {
         //String layerName = typeIndexEnt.getKey();
         LayerTypeIndex layerTypeIndex = typeIndexEnt.getValue();
         HashMap<String,TypeIndex> layerTypeMap = layerTypeIndex.layerTypeIndex;
         for (Map.Entry<String,TypeIndex> typeEnt:layerTypeMap.entrySet()) {
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
   }

   public void clearActiveLayers() {
      activeTypeIndex.clear();
   }
}
