/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import sc.js.JSSettings;

/** 
 * Represents the various sync modes for a given class or property.  
 * It can be enabled, disabled, set to automatic or enabled only for one direction - client to server or server to client.  
 * When you set the SyncMode to Automatic, any objects that exist in more than one runtime are synchronized in those runtimes automatically.
 * Essentially overlapping types and properties are given the @Sync(syncMode=Enabled) annotation by default so that the two runtimes are 
 * properly synchronized.  The code-generation phase looks at which properties have initializers to determine the sync behavior.  When a property
 * has a forward binding expression only, it's value is not synchronized since it's computed from other properties.  When it's initialized on
 * the client and server, its initDefault flag is set to false.  When it only exists in one runtime, it's not synchronized at all.
 */
@JSSettings(jsModuleFile="js/scgen.js", prefixAlias="sc_")
public enum SyncMode {
   Enabled, Disabled, Automatic, ClientToServer, ServerToClient
}
