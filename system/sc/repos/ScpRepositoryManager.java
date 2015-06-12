/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import sc.util.IMessageHandler;
import sc.util.FileUtil;

import java.io.File;
import java.util.ArrayList;

/**
 */
public class ScpRepositoryManager extends AbstractRepositoryManager {
   public ScpRepositoryManager(RepositorySystem sys, String managerName, String rootDir, IMessageHandler handler, boolean info) {
      super(sys, managerName, rootDir, handler, info);
   }

   public String doInstall(RepositorySource src, DependencyContext ctx) {
      ArrayList<String> args = new ArrayList<String>();
      String resFile = src.pkg.installedRoot;
      String srcURL = src.url;
      boolean isZip = srcURL.endsWith(".jar") || srcURL.endsWith(".zip");
      if (isZip) {
         resFile = FileUtil.addExtension(resFile, FileUtil.getExtension(srcURL));
      }
      args.add("scp");
      if (!isZip)
         args.add("-r");
      // Want to eliminate the extra directory - so we are copying one to the other
      // "cp" and rsync support a trailing / here to mean copy the directory but not so with scp so we need to use
      // the wildcard
      if (!isZip) {
         new File(resFile).mkdirs();
         args.add(srcURL + "/*");
         args.add(resFile + "/");
      }
      else {
         args.add(srcURL);
         args.add(resFile);
      }
      if (info)
         info("Running: " + argsToString(args));
      String res = FileUtil.execCommand(args, null);
      if (res == null)
         return "Error: failed to run install command";
      if (src.unzip) {
         if (!FileUtil.unzip(resFile, src.pkg.installedRoot, false))
            return "Failed to unzip: " + src.pkg.installedRoot + " into: " + resFile;
      }
      if (info)
         info("Completed: " + argsToString(args));
      return null;
   }

}
