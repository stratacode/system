/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import sc.js.JSSettings;

/**
 * Can be specified as null in a call to create a binding, but provides parameters that alter the behavior of the binding
 */
@JSSettings(jsLibFiles = "js/scbind.js", prefixAlias="sc_")
public class BindOptions {
   /** Run the binding after waiting for the specified number of milliseconds */
   public int delay = -1;
   public int priority = 0;

   public static BindOptions delay(int val, BindOptions opts) {
      if (opts == null)
         opts = new BindOptions();
      opts.delay = val;
      return opts;
   }

   public static BindOptions priority(int p, BindOptions opts) {
      if (opts == null)
         opts = new BindOptions();
      opts.priority = p;
      return opts;
   }
}
