/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.lang.java.BodyTypeDeclaration;
import sc.lang.java.DeclarationType;
import sc.util.StringUtil;

import java.io.Serializable;
import java.util.List;

/** We store this information about each type in the type index - essentially one for each .sc file for each process/runtime where it's used. */
public class TypeIndexEntry implements Serializable {
   public String typeName;
   public String layerName;
   public String processIdent;
   public int layerPosition;
   public List<String> baseTypes;
   public DeclarationType declType;
   public String fileName;
   public long lastModified;
   public boolean isLayerType;

   public String toString() {
      return "index: " + typeName + " (" + layerName + ")";
   }

   public boolean sameType(Object other) {
      if (other instanceof TypeIndexEntry) {
         TypeIndexEntry oi = (TypeIndexEntry) other;
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

   public boolean equals(Object other) {
      if (!(other instanceof TypeIndexEntry))
         return false;
      TypeIndexEntry ot = (TypeIndexEntry) other;
      if (!StringUtil.equalStrings(typeName, ot.typeName) || declType != ot.declType || lastModified != ot.lastModified)
         return false;
      if (baseTypes != null) {
         if (ot.baseTypes == null)
            return false;
         if (!baseTypes.equals(ot.baseTypes))
            return false;
      }
      return true;
   }

   public static final TypeIndexEntry EXCLUDED_SENTINEL = new TypeIndexEntry();
}
