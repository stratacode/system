/*
 * Copyright (c) 2018. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.lang.SemanticNodeList;
import sc.lang.java.ImportDeclaration;
import sc.lang.java.TypeDeclaration;

import java.util.ArrayList;
import java.util.Map;

class TypeDeclarationCacheEntry extends ArrayList<TypeDeclaration> {
   int fromPosition;
   boolean reloaded = false;
   SemanticNodeList<ImportDeclaration> autoImports;
   Map<String,AutoImportEntry> autoImportsIndex;

   public TypeDeclarationCacheEntry(int size) {
      super(size);
   }

   public void clear() {
       super.clear();
       fromPosition = -1;
   }
}
