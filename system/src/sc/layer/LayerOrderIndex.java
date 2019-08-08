/*
 * Copyright (c) 2016. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.util.FileUtil;
import sc.util.IMessageHandler;
import sc.util.MessageType;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * One instance per LayeredSystem, used for storing the set of layers that could be loaded into this system
 * as inactiveLayers.   This keeps track of the global ordering of the layers, specifically so we can order
 * types in the modify type index when we restore the type index, before we've created the inactiveLayers instance.
 */
public class LayerOrderIndex implements Serializable {
   ArrayList<String> inactiveLayerNames = new ArrayList<String>();

   HashSet<String> disabledLayers = new HashSet<String>();
   HashSet<String> excludedLayers = new HashSet<String>();

   transient HashMap<String,Integer> layerPositions;

   public int getLayerPosition(String layerName) {
      if (layerPositions == null)
         refreshOrder();
      Integer res = layerPositions.get(layerName);
      if (res == null)
         return -1;
      return res;
   }

   public boolean excludesLayer(String layerName) {
      return disabledLayers.contains(layerName) || excludedLayers.contains(layerName);
   }

   public void removeExcludedLayer(String layerName) {
      disabledLayers.remove(layerName);
      excludedLayers.remove(layerName);
   }

   public boolean refreshAll(LayeredSystem sys, boolean reset) {
      boolean anyChanges = false;
      int numInactiveLayers = sys.inactiveLayers.size();
      if (reset) {
         inactiveLayerNames = new ArrayList<String>(numInactiveLayers);
         anyChanges = true;
      }
      int lastPos = -1;
      for (int i = 0; i < numInactiveLayers; i++) {
         String layerName = sys.inactiveLayers.get(i).getLayerName();
         int nextPos = inactiveLayerNames.indexOf(layerName);
         // We have a new item to add to the inactiveLayerNames - either add it to the beginning of the list or after
         // the last element we've processed so far.
         if (nextPos == -1) {
            if (lastPos == -1 || lastPos == inactiveLayerNames.size() - 1)
               inactiveLayerNames.add(layerName);
            else
               inactiveLayerNames.add(lastPos+1, layerName);
            anyChanges = true;
            lastPos = i;
         }
         // This next item in the current list is out of order in the index.
         else if (lastPos != -1 && nextPos < lastPos) {
            System.out.println("*** Order index - moving: " + layerName + " to after: " + inactiveLayerNames.get(lastPos));
            inactiveLayerNames.remove(nextPos);
            inactiveLayerNames.add(lastPos, layerName);
            anyChanges = true;
         }
         // The next item is after the last pos so just use this guys as the next marker
         else
            lastPos = nextPos;
      }

      if (anyChanges)
         layerPositions = null;

      return anyChanges;
   }

   public void refreshOrder() {
      if (layerPositions == null)
         layerPositions = new HashMap<String,Integer>();
      else
         layerPositions.clear();

      for (int i = 0; i < inactiveLayerNames.size(); i++) {
         layerPositions.put(inactiveLayerNames.get(i), i);
      }
   }

   public void saveToDir(String dirName) {
      File orderIndexFile = getOrderIndexFile(dirName);
      ObjectOutputStream os = null;
      try {
         os = new ObjectOutputStream(new FileOutputStream(orderIndexFile));
         os.writeObject(this);
      }
      catch (IOException exc) {
         System.err.println("*** Unable to write layer order index file: " + exc);
      }
      finally {
         FileUtil.safeClose(os);
      }
   }

   private static File getOrderIndexFile(String dirName) {
      return new File(dirName, "LayerOrderIndex.ser");
   }

   public static LayerOrderIndex readFromFile(String dirName, IMessageHandler msg) {
      File orderIndexFile = getOrderIndexFile(dirName);
      ObjectInputStream ois = null;
      FileInputStream fis = null;
      try {
         ois = new ObjectInputStream(fis = new FileInputStream(orderIndexFile));
         Object res = ois.readObject();
         if (res instanceof LayerOrderIndex) {
            LayerOrderIndex orderIndex = (LayerOrderIndex) res;
            orderIndex.refreshOrder();
            return orderIndex;
         }
         else {
            orderIndexFile.delete();
            if (msg != null)
               msg.reportMessage("Failed to read layer order index file: ", null, -1, -1, MessageType.Error);
            return null;
         }
      }
      catch (InvalidClassException exc) {
         System.out.println("typeIndex - version changed: " + exc);
         orderIndexFile.delete();
      }
      catch (IOException exc) {
         System.out.println("*** can't read orderIndex file: " + exc);
      }
      catch (ClassNotFoundException exc) {
         System.out.println("*** can't read orderIndex file: " + exc);
      }
      finally {
         FileUtil.safeClose(ois);
         FileUtil.safeClose(fis);
      }
      return null;
   }
}
