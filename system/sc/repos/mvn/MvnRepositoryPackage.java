/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos.mvn;

import sc.repos.IRepositoryManager;
import sc.repos.RepositoryPackage;
import sc.repos.RepositorySource;
import sc.util.FileUtil;

public class MvnRepositoryPackage extends RepositoryPackage {
   transient POMFile pomFile;

   public MvnRepositoryPackage(IRepositoryManager mgr, String pkgName, String fileName, RepositorySource src) {
      super(mgr, pkgName, fileName, src);
   }

   public String getVersionRoot() {
      if (!(currentSource instanceof MvnRepositorySource))
         return installedRoot;
      MvnRepositorySource mvnSrc = (MvnRepositorySource) currentSource;
      if (mvnSrc.desc != null && mvnSrc.desc.version != null)
          return FileUtil.concat(installedRoot, mvnSrc.desc.version);
      return installedRoot;
   }
}
