/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import java.io.Serializable;

public class DependencyContext implements Serializable {
   public int depth;

   public DependencyContext(int depth) {
      this.depth = depth;
   }

   public DependencyContext child() {
      return new DependencyContext(depth + 1);
   }

   public static DependencyContext child(DependencyContext prev) {
      return prev == null ? new DependencyContext(1) : prev.child();
   }

   public static int val(DependencyContext ctx) {
      return ctx == null ? 0 : ctx.depth;
   }

   public static DependencyContext merge(DependencyContext ctx1, DependencyContext ctx2) {
      return val(ctx1) <= val(ctx2) ? ctx1 : ctx2;
   }

   public static boolean hasPriority(DependencyContext ctx1, DependencyContext ctx2) {
      // lower dependency depth takes precedence
      return val(ctx1) < val(ctx2);
   }
}
