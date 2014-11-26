/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import sc.util.FileUtil;

/**
 * Represents a third party package that's managed by a RepositoryManager
 */
public class RepositoryPackage {
   /** A unique name of this package within the layered system */
   public String packageName;

   public boolean installed = false;

   RepositorySource[] sources;
   RepositorySource currentSource;

   public String installedRoot;

   public String fileName = null;

   public RepositoryPackage(IRepositoryManager mgr, String pkgName, RepositorySource src) {
      this.fileName = this.packageName = pkgName;
      this.sources = new RepositorySource[1];
      src.pkg = this;
      this.sources[0] = src;
      updateInstallRoot(mgr);
   }

   public RepositoryPackage(IRepositoryManager mgr, String pkgName, RepositorySource[] srcs) {
      this.fileName = this.packageName = pkgName;
      this.sources = srcs;
      for (RepositorySource src:srcs)
         src.pkg = this;
      updateInstallRoot(mgr);
   }

   public String install() {
      installed = false;
      StringBuilder errors = null;
      for (RepositorySource src:sources) {
         if (src.repository.isActive()) {
            String err = src.repository.install(src);
            if (err == null) {
               installed = true;
               currentSource = src;
               break;
            }
            else {
               if (errors == null)
                  errors = new StringBuilder();
               errors.append(err);
            }
         }
      }
      if (!installed && errors == null) {
         errors = new StringBuilder();
         errors.append("No active repository manager to install package: " + packageName);
      }
      return errors == null ? null : errors.toString();
   }

   public String update() {
      if (!installed || currentSource == null)
         return "Package: " + packageName + " not installed - skipping update";

      return currentSource.repository.update(currentSource);
   }

   public void updateInstallRoot(IRepositoryManager mgr) {
      String resName;
      if (fileName == null)
         resName = mgr.getPackageRoot();
      else
         resName = FileUtil.concat(mgr.getPackageRoot(), fileName);
      installedRoot = resName;
   }

}
