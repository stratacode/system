/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos.mvn;

import sc.repos.IRepositoryManager;
import sc.repos.RepositoryPackage;
import sc.repos.RepositorySource;

public class MvnRepositoryPackage extends RepositoryPackage {
   transient POMFile pomFile;

   public MvnRepositoryPackage(IRepositoryManager mgr, String pkgName, String fileName, RepositorySource src) {
      super(mgr, pkgName, fileName, src);
   }
}
