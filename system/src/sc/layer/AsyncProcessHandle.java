/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

public class AsyncProcessHandle {
   public Process process;

   AsyncProcessHandle(Process p) {
      process = p;
   }

   public void endProcess() {
      process.destroy();
   }
}
