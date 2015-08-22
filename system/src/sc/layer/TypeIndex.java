/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.lang.java.DeclarationType;

import java.io.Serializable;
import java.util.List;

/** We store this information about each type in the type index - essentially one for each .sc file for each process/runtime where it's used. */
public class TypeIndex implements Serializable {
   public String typeName;
   public String layerName;
   public String processIdent;
   public List<String> baseTypes;
   public DeclarationType declType;
   public String fileName;
   public long lastModified;
   public boolean isLayerType;

   public String toString() {
      return "index: " + typeName + " (" + layerName + ")";
   }

   public static final TypeIndex EXCLUDED_SENTINEL = new TypeIndex();
}
