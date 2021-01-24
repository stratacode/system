/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import sc.js.JSSettings;

import java.util.ArrayList;

@JSSettings(jsLibFiles = "js/scbind.js", prefixAlias="sc_")
public class BindingLoopException extends RuntimeException {
   ArrayList<Bind.BindFrame> recurseFrames;
   public BindingLoopException(ArrayList<Bind.BindFrame> rfs) {
      recurseFrames = rfs;
   }
}
