/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

@sc.js.JSSettings(jsModuleFile="js/scgen.js", prefixAlias="sc_")
public interface ISystemExitListener {
   void systemExiting();
}
