/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import java.io.Serializable;
import java.util.ArrayList;

public class DependencyContext implements Serializable {
   public int depth;
   public RepositoryPackage fromPkg;
   public DependencyContext parent;

   public DependencyContext(int depth, RepositoryPackage initPkg, DependencyContext parent) {
      this.depth = depth;
      fromPkg = initPkg;
      this.parent = parent;
   }

   public DependencyContext child(RepositoryPackage fromPkg) {
      return new DependencyContext(depth + 1, fromPkg, this);
   }

   public static DependencyContext child(DependencyContext prev, RepositoryPackage pkg) {
      return prev == null ? new DependencyContext(1, pkg, null) : prev.child(pkg);
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

   public String toString() {
      StringBuilder sb = new StringBuilder();
      boolean hasParent = false;
      if (parent != null) {
         sb.append(parent.toString());
         hasParent = true;
      }
      if (fromPkg != null) {
         if (hasParent)
            sb.append(" -> ");
         sb.append(fromPkg.toString());
      }
      return sb.toString();
   }
}
