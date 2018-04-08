/*
 * Copyright (c) 2018. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.lang.java.ImportDeclaration;

// Keeps track of which layer a particular auto-import was derived from so that as we add layers we
// keep the most recent import in case the base names of the import conflict.
class AutoImportEntry {
   int layerPosition;
   ImportDeclaration importDecl;
}
