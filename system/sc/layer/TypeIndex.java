/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.lang.java.DeclarationType;

import java.io.Serializable;
import java.util.List;

public class TypeIndex implements Serializable {
   public String typeName;
   public String layerName;
   public List<String> baseTypes;
   public DeclarationType declType;
   public String fileName;
   public long lastModified;
   public boolean isLayerType;

   // Computed - not stored
   public transient List<String> modifiedByTypes;
   public transient List<String> extendedByTypes;

   public String toString() {
      return "index: " + typeName + " (" + layerName + ")";
   }

   public static final TypeIndex EXCLUDED_SENTINEL = new TypeIndex();
}
