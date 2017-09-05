/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import sc.js.JSSettings;

/** 
 * A generic interface for implementing an object with a String id.  This is a hook point used by
 * the synchronization framework in particular to get unique ids for client/server objects that
 * are consistent, and persistent.
 *
 * For the sync framework, if the type is an inner type, it can be any name that is a valid Java identifier.
 * If it's an outer type, it should include the type name of the instance - e.g. package.ClassName.uniqueIdInTheClass
 */
@JSSettings(jsModuleFile = "js/scgen.js")
public interface IObjectId {
   String getObjectId();
}
