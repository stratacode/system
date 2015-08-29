/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.lang.java.BodyTypeDeclaration;
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

   public boolean sameType(Object other) {
      if (other instanceof TypeIndex) {
         TypeIndex oi = (TypeIndex) other;
         if (oi.typeName.equals(typeName) && oi.layerName.equals(layerName))
            return true;
      }
      else if (other instanceof BodyTypeDeclaration) {
         BodyTypeDeclaration ot = (BodyTypeDeclaration) other;
         if (ot.getFullTypeName().equals(typeName) && ot.getLayer().getLayerName().equals(layerName))
            return true;
      }
      return false;
   }

   public static final TypeIndex EXCLUDED_SENTINEL = new TypeIndex();
}
