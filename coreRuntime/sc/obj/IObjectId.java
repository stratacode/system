/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import sc.js.JSSettings;

/** 
 * A generic interface for implementing an object with a String id.  This is a hook point used by
 * the synchronization framework in particular to get unique ids for client/server objects that
 * are consistent, and persistent.  
 */
@JSSettings(jsModuleFile = "js/scgen.js")
public interface IObjectId {
   String getObjectId();
}
