/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import sc.util.IMessageHandler;
import sc.util.URLUtil;

import java.io.File;

/**
 * For this RepoistoryManager, packages are stored from the URL and downloaded via the URL protocol handler built into Java.
 * From there they are transfered to the installedRoot, which is then typically unzipped.
 */
public class URLRepositoryManager extends AbstractRepositoryManager {
   public URLRepositoryManager(RepositorySystem sys, String managerName, String rootDir, IMessageHandler handler, boolean info) {
      super(sys, managerName, rootDir, handler, info);
   }

   public String doInstall(RepositorySource src, DependencyContext ctx, DependencyCollection deps) {
      src.pkg.definesClasses = false;
      if (!system.reinstallSystem) {
         if (new File(src.pkg.installedRoot).isDirectory()) {
            info("Package already downloaded: " + src);
            return null;
         }
      }
      return URLUtil.saveURLToFile(src.url, src.pkg.installedRoot, src.unzip, msg);
   }

   @Override
   public RepositoryPackage createPackage(String url) {
      return null;
   }
}
