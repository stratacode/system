/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import sc.util.FileUtil;

import java.util.ArrayList;

/**
 * Represents a third party package that's managed by a RepositoryManager
 */
public class RepositoryPackage {
   /** A unique name of this package within the layered system */
   public String packageName;

   public boolean installed = false;

   RepositorySource[] sources;
   RepositorySource currentSource;

   // Optional list of dependencies this package has on other packages
   public ArrayList<RepositoryPackage> dependencies;

   public String installedRoot;

   public String fileName = null;

   public RepositoryPackage(IRepositoryManager mgr, String pkgName, RepositorySource src) {
      this(mgr, pkgName, pkgName, src);
   }

   public RepositoryPackage(IRepositoryManager mgr, String pkgName, String fileName, RepositorySource src) {
      this.fileName = fileName;
      this.packageName = pkgName;
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

   public void addNewSource(RepositorySource repoSrc) {
      if (repoSrc.equals(currentSource))
         return;

      RepositorySource[] newSrcs = new RepositorySource[sources.length + 1];
      System.arraycopy(sources, 0, newSrcs, 0, sources.length);
      newSrcs[sources.length] = repoSrc;
      sources = newSrcs;
   }

   public String getClassPath() {
      StringBuilder sb = new StringBuilder();
      if (installed) {
         sb.append(installedRoot);
         if (dependencies != null) {
            for (RepositoryPackage depPkg : dependencies) {
               String depCP = depPkg.getClassPath();
               if (depCP != null && depCP.length() > 0) {
                  sb.append(":");
                  sb.append(depCP);
               }
            }
         }
         return sb.toString();
      }
      return null;
   }
}
