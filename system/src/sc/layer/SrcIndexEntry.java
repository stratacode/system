/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.util.FileUtil;

import java.io.File;
import java.io.Serializable;

public class SrcIndexEntry implements Serializable {
   public byte[] hash;
   public String extension;

   public static boolean debugSrcIndexEntry = true;

   /** Not persisted - used to track entries which are no longer needed by the project */
   public transient boolean inUse;

   /** The layer containing this buildSrcIndex entry */
   public transient Layer srcIndexLayer;

   /** Name of the layer containing the src file for this index entry */
   public String layerName;

   // TODO: Only if debugSrcIndexEntry is set
   public long lastModified;
   public byte[] fileBytes;

   public String toString() {
      if (hash == null)
         return "null hash";
      return hashToString(hash);
   }

   public static String hashToString(byte[] hash) {
      StringBuilder sb = new StringBuilder();
      sb.append("[");
      for (int i = 0; i < hash.length; i++) {
         if (i != 0)
            sb.append(",");
         sb.append(hash[i]);
      }
      sb.append("]");
      return sb.toString();
   }

   void updateDebugFileInfo(String fileName) {
      if (debugSrcIndexEntry) {
         fileBytes = FileUtil.getFileAsBytes(fileName);
         lastModified = new File(fileName).lastModified();
      }
   }
}
