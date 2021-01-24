/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import sc.js.JSSettings;

@JSSettings(jsModuleFile="js/scgen.js", prefixAlias="sc_")
public interface IScopeEventListener {
   public void startContext();
}
