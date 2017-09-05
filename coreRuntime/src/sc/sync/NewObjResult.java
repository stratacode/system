/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.sync;

import sc.obj.Sync;
import sc.obj.SyncMode;

import java.util.ArrayList;

@sc.js.JSSettings(jsModuleFile="js/sync.js", prefixAlias="sc_")
@Sync(syncMode= SyncMode.Disabled)
class NewObjResult {
   SyncSerializer newSB = null;
   boolean newSBPushed = false;
   ArrayList<String> startObjNames = null;
}
