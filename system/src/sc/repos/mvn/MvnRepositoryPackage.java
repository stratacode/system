/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos.mvn;

import sc.layer.Layer;
import sc.repos.DependencyContext;
import sc.repos.IRepositoryManager;
import sc.repos.RepositoryPackage;
import sc.repos.RepositorySource;
import sc.util.FileUtil;

import java.io.File;
import java.util.ArrayList;

public class MvnRepositoryPackage extends RepositoryPackage {
   {
      type = "mvn";
   }
   transient POMFile pomFile;

   public boolean includeOptional = false;
   public boolean includeProvided = false;
   // The list of types of files we are to install for this package - null = (the default POM packaging)
   // other values of the type field for a depenency reference - e.g. test-jar
   public ArrayList<String> installFileTypes = new ArrayList<String>();

   /** Is this a sub-module of a parent module */
   public boolean subModule = false;

   public MvnRepositoryPackage(Layer layer) {
      super(layer);
   }

   public MvnRepositoryPackage(IRepositoryManager mgr, String pkgName, String fileName, RepositorySource src, RepositoryPackage parentPkg) {
      super(mgr, pkgName, fileName, src, parentPkg);

      addInstallFileType(src);
   }

   private void addInstallFileType(RepositorySource src) {
      String type = null;
      if (src instanceof MvnRepositorySource) {
         MvnRepositorySource msrc = (MvnRepositorySource) src;
         type = msrc.desc.type;
      }
      // We'll represent null as the default.
      if (type != null && type.equals("jar"))
         type = null;
      if (!installFileTypes.contains(type))
         installFileTypes.add(type);
   }

   public RepositorySource addNewSource(RepositorySource repoSrc) {
      RepositorySource res = super.addNewSource(repoSrc);
      addInstallFileType(repoSrc);
      return res;
   }

   public String getVersionSuffix() {
      if (!(currentSource instanceof MvnRepositorySource))
         return null;
      // If this package has a parent, we are in it's directory and so do not include the version in the package dir
      if (parentPkg != null)
         return null;
      MvnRepositorySource mvnSrc = (MvnRepositorySource) currentSource;
      if (mvnSrc.desc != null && mvnSrc.desc.version != null)
         return mvnSrc.desc.version;
      return null;

   }

   public String getVersionRoot() {
      return FileUtil.concat(getInstalledRoot(), getVersionSuffix());
   }

   public String getGroupId() {
      if (currentSource instanceof MvnRepositorySource)
         return ((MvnRepositorySource) currentSource).desc.groupId;
      else {
         return pomFile.getGroupId();
      }
   }

   public void updateCurrentSource(RepositorySource src) {
      if (!(src instanceof MvnRepositorySource)) {
         super.updateCurrentSource(src);
         return;
      }
      MvnRepositorySource msrc = (MvnRepositorySource) src;
      setCurrentSource(src);

      fileNames.clear();
      if (installFileTypes.size() == 0)
         installFileTypes.add(null);
      for (String installFileType:installFileTypes) {
         if (installFileType == null)
            addFileName(msrc.desc.getJarFileName());
         else if (installFileType.equals("test-jar"))
            addFileName(msrc.desc.getTestJarFileName());
      }
   }

   public boolean getReusePackageDirectory() {
      File versionRoot = new File(getVersionRoot());
      if (versionRoot.isDirectory()) {
         File[] files = versionRoot.listFiles();
         if (files == null)
            return true;
         for (File file:files) {
            String fileName = file.getName();
            if (fileName.equals("pom.xml") || fileName.startsWith(".") || fileName.equals("pom.xml.notFound"))
               continue;
            return false;
         }
         return true;
      }
      return false;
   }

   public boolean updateFromSaved(IRepositoryManager mgr, RepositoryPackage oldPkg, boolean install, DependencyContext ctx) {
      if (!super.updateFromSaved(mgr, oldPkg, install, ctx))
         return false;
      if (oldPkg instanceof MvnRepositoryPackage)
         installFileTypes = ((MvnRepositoryPackage) oldPkg).installFileTypes;
      return true;
   }
}
