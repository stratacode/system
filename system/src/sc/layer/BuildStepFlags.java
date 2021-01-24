/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

class BuildStepFlags {
   boolean preInited;
   boolean inited;
   boolean started;

   void reset() {
      preInited = inited = started = false;
   }
}
