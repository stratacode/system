/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.layer.Layer;

import java.util.IdentityHashMap;

public class TypeContext {
   public TypeContext rootCtx;
   IdentityHashMap<BodyTypeDeclaration,BodyTypeDeclaration> map;
   public Layer fromLayer; // When set, only return types defined in a previous layer to this one
   public boolean sameType;
   public boolean transformed;

   public TypeContext() {
   }

   public TypeContext(boolean transformed) {
      this.transformed = transformed;
   }

   public TypeContext(TypeContext root) {
      rootCtx = root;
   }

   public TypeContext(Layer fromLyr) {
      fromLayer = fromLyr;
      sameType = true;
   }

   public TypeContext(Layer fromLyr, boolean sameType) {
      fromLayer = fromLyr;
      this.sameType = sameType;
   }

   public void add(BodyTypeDeclaration baseType, BodyTypeDeclaration subType) {
      if (map == null)
         map = new IdentityHashMap<BodyTypeDeclaration,BodyTypeDeclaration>();
      map.put(baseType, subType);
   }

   public BodyTypeDeclaration getSubType(BodyTypeDeclaration baseType) {
      BodyTypeDeclaration o = null;
      if (map != null)
         o = map.get(baseType);
      if (o == null && rootCtx != null)
         return rootCtx.getSubType(baseType);

      if (o != null) {
         BodyTypeDeclaration next = getSubType(o);
         if (next != null)
            return next;
      }
      return o;
   }
}
