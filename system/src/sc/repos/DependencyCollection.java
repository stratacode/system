/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import java.util.LinkedHashSet;

/** Contains the set of packages which we need to install to satisfy dependencies of the installed packages */
public class DependencyCollection {
   public LinkedHashSet<PackageDependency> neededDeps = new LinkedHashSet<PackageDependency>();

   public void addDependency(RepositoryPackage pkg, DependencyContext ctx) {
      neededDeps.add(new PackageDependency(pkg, ctx));
   }
}
