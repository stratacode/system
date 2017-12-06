/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

/**
 * Called just before we get the children - used as a hook for frameworks that might lazily init children, like the SCHTML repeat tags.
 */
@sc.js.JSSettings(jsModuleFile="js/scgen.js", prefixAlias="sc_")
public interface IChildInit {
   void initChildren();
}
