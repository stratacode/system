/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

@sc.js.JSSettings(jsLibFiles="js/scdyn.js", prefixAlias="sc_")
public interface IAltStoppable {
   /** You can optionally implement this method to receive a stop hook when your object or component is disposed */
   public void _stop();
}
