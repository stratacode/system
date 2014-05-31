/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

@sc.js.JSSettings(jsModuleFile="js/scgen.js", prefixAlias="sc_")
public interface ScopeDestroyListener {
   void scopeDestroyed(ScopeContext scope);
}
