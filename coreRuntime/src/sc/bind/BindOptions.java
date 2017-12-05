/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

/**
 * Can be specified as null in a call to create a binding, but provides parameters that alter the behavior of the binding
 */
public class BindOptions {
   /** Run the binding after waiting for the specified number of milliseconds */
   public int delay = -1;

   public static BindOptions delay(int val) {
      BindOptions opts = new BindOptions();
      opts.delay = val;
      return opts;
   }
}
