/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.dyn;

/** Implemented by classes that expose their list of children at runtime */
@sc.js.JSSettings(jsLibFiles="js/scdyn.js")
public interface IObjChildren {
   public Object[] getObjChildren(boolean create);
}
