/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import java.io.Serializable;

public class SrcIndexEntry implements Serializable {
   public byte[] hash;
   public String extension;

   /** Not persisted - used to track entries which are no longer needed by the project */
   public transient boolean inUse;

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
}
