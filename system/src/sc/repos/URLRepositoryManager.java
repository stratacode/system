/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import sc.util.FileUtil;
import sc.util.IMessageHandler;
import sc.util.URLUtil;

import java.io.File;

/**
 * For this RepositoryManager, packages are stored from the URL and downloaded via the URL protocol handler built into Java.
 * From there they are transferred to the installedRoot, which is then typically unzipped.
 */
public class URLRepositoryManager extends AbstractRepositoryManager {
   public URLRepositoryManager(RepositorySystem sys, String managerName, String rootDir, IMessageHandler handler, boolean info) {
      super(sys, managerName, rootDir, handler, info);
   }

   public String doInstall(RepositorySource src, DependencyContext ctx, DependencyCollection deps) {
      String installDir = src.pkg.installedRoot;
      String installFile = installDir;
      if (src.pkg.unzip)
         src.pkg.definesClasses = false;
      else if (src.pkg.fileNames.size() == 1) {
         String fileName = src.pkg.fileNames.get(0);
         installFile = FileUtil.concat(installDir, fileName);
      }
      // TODO: should we have an option here to set definesClasses true/false for a package url package?
      if (!system.reinstallSystem) {
         if (new File(src.pkg.installedRoot).isDirectory()) {
            info("Package already downloaded: " + src);
            return null;
         }
      }
      new File(installDir).mkdirs();
      return URLUtil.saveURLToFile(src.url, installFile, src.unzip, msg);
   }

   @Override
   public RepositoryPackage createPackage(String url) {
      return null;
   }
}
