/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import sc.util.FileUtil;
import sc.util.StringUtil;

import java.io.*;
import java.util.ArrayList;

/**
 * Represents a third party package that's managed by a RepositoryManager
 */
public class RepositoryPackage implements Serializable {
   /** A unique name of this package within the layered system */
   public String packageName;

   public long installedTime;

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
         resName = FileUtil.concat(mgr.getPackageRoot(), packageName);
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
         if (fileName != null)
            sb.append(FileUtil.concat(installedRoot, fileName));
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

   public static RepositoryPackage readFromFile(File f) {
      ObjectInputStream ios = null;
      FileInputStream fis = null;
      try {
         ios = new ObjectInputStream(fis = new FileInputStream(f));
         RepositoryPackage res = (RepositoryPackage) ios.readObject();
         return res;
      }
      catch (InvalidClassException exc) {
         System.err.println("RepositoryPackage saved info - version changed");
         f.delete();
      }
      catch (IOException exc) {
         System.err.println("Failed to read RepositoryPackage info file: " + exc);
         f.delete();
      }
      catch (ClassNotFoundException exc) {
         System.err.println("Error reading RepositoryPackage info file: " + exc);
         f.delete();
      }
      finally {
         FileUtil.safeClose(fis);
         FileUtil.safeClose(ios);
      }
      return null;
   }

   // Check for any changes in the package - if so, we'll re-install.  Otherwise, we'll
   // update this package with the saved info.
   public boolean updateFromSaved(RepositoryPackage oldPkg) {
      if (!packageName.equals(oldPkg.packageName))
         return false;
      if (!StringUtil.equalStrings(fileName, oldPkg.fileName))
         return false;

      if (sources.length != oldPkg.sources.length)
         return false;

      for (int i = 0; i < sources.length; i++)
         if (!sources[i].equals(oldPkg.sources[i]))
            return false;

      // These fields are computed during the install so we update them here when we skip the install
      dependencies = oldPkg.dependencies;
      currentSource = oldPkg.currentSource;

      return true;
   }

   public void saveToFile(File tagFile) {
      ObjectOutputStream os = null;
      FileOutputStream fos = null;
      try {
         os = new ObjectOutputStream(fos = new FileOutputStream(tagFile));
         os.writeObject(this);
      }
      catch (IOException exc) {
         System.err.println("Unable to write RepositoryPackage info file: " + tagFile + ": " + exc);
      }
      finally {
         FileUtil.safeClose(os);
         FileUtil.safeClose(fos);
      }
   }
}
