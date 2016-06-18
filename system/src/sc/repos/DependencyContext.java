/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class DependencyContext implements Serializable {
   public int depth;
   public transient RepositoryPackage fromPkg;
   public String fromPkgURL;
   public DependencyContext parent;

   private transient List<RepositoryPackage> incPkgs = null;

   public DependencyContext(int depth, RepositoryPackage initPkg, DependencyContext parent) {
      this.depth = depth;
      fromPkg = initPkg;
      fromPkgURL = fromPkg.getPackageSrcURL();
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

   /** Returns the list of packages that defined this dependency, starting with the root package */
   public List<RepositoryPackage> getIncludingPackages() {
      if (incPkgs != null)
         return incPkgs;
      incPkgs = new ArrayList<RepositoryPackage>();
      if (parent != null) {
         incPkgs.addAll(parent.getIncludingPackages());
      }
      if (fromPkg != null)
         incPkgs.add(fromPkg);
      return incPkgs;
   }

   public void updateAfterRestore(IRepositoryManager manager) {
      if (fromPkgURL != null && fromPkg == null)
         fromPkg = manager.getOrCreatePackage(fromPkgURL, null, false);
      if (parent != null)
         parent.updateAfterRestore(manager);
   }

}
