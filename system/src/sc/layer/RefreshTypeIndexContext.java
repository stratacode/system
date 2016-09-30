/*
 * Copyright (c) 2016. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;

public class RefreshTypeIndexContext {
   String typeIndexIdent;
   HashMap<String,Boolean> filesToProcess;
   Set<String> refreshedLayers;
   ArrayList<Layer> layersToRebuild  = new ArrayList<Layer>();
   File typeIndexDir;
   SysTypeIndex curTypeIndex;

   public RefreshTypeIndexContext(String typeIndexIdent, HashMap<String, Boolean> filesToProcess, SysTypeIndex curTypeIndex, Set<String> refreshedLayers, File typeIndexDir) {
      this.typeIndexIdent = typeIndexIdent;
      this.filesToProcess = filesToProcess;
      this.curTypeIndex = curTypeIndex;
      this.refreshedLayers = refreshedLayers;
      this.typeIndexDir = typeIndexDir;
   }
}
