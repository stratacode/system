package sc.layer;

class BuildStepFlags {
   boolean preInited;
   boolean inited;
   boolean started;

   void reset() {
      preInited = inited = started = false;
   }
}
