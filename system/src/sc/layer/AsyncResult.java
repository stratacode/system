/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

public class AsyncResult {
   public Process process;

   AsyncResult(Process p) {
      process = p;
   }

   public void endProcess() {
      process.destroy();
   }
}
