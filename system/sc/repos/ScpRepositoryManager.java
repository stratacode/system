/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import sc.lang.IMessageHandler;
import sc.layer.LayeredSystem;
import sc.util.FileUtil;

import java.util.ArrayList;

/**
 */
public class ScpRepositoryManager extends AbstractRepositoryManager {
   public ScpRepositoryManager(String managerName, String rootDir, IMessageHandler handler, boolean info) {
      super(managerName, rootDir, handler, info);
   }

   public String doInstall(RepositorySource src) {
      ArrayList<String> args = new ArrayList<String>();
      String resFile = src.pkg.installedRoot;
      args.add("scp");
      args.add("-r");
      args.add(src.url);
      args.add(resFile);
      if (info)
         info("Running: " + argsToString(args));
      String res = FileUtil.execCommand(args, null);
      if (res == null)
         return "Error: failed to run install command";
      if (src.unzip) {
         String noSuffix = FileUtil.removeExtension(resFile);
         if (noSuffix.equals(resFile))
            System.err.println("*** Unzip option specified for package: " + src.pkg.packageName + " but rootFile: " + resFile + " is missing the .zip or .jar suffix.");
         else {
            if (!FileUtil.unzip(res, noSuffix))
               return "Failed to unzip: " + res + " into: " + noSuffix;
         }
      }
      if (info)
         info("Completed: " + argsToString(args));
      return null;
   }

}
