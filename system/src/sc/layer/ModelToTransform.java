/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

class ModelToTransform {
   IFileProcessorResult model;
   SrcEntry toGenEnt;
   boolean generate;

   public boolean equals(Object other) {
      if (!(other instanceof ModelToTransform))
         return false;
      ModelToTransform om = (ModelToTransform) other;
      if (toGenEnt == null || om.toGenEnt == null)
         return false;
      return om.toGenEnt.absFileName.equals(toGenEnt.absFileName);
   }

   public int hashCode() {
      return toGenEnt == null ? 0 : toGenEnt.absFileName.hashCode();
   }

   public String toString() {
      return toGenEnt == null ? "null-model-to-transform" : toGenEnt.toString();
   }
}
