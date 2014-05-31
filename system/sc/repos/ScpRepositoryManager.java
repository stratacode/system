/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import sc.layer.LayeredSystem;
import sc.util.FileUtil;

import java.util.ArrayList;

/**
 */
public class ScpRepositoryManager extends AbstractRepositoryManager {
   public ScpRepositoryManager(LayeredSystem sys, String managerName, String rootDir) {
      super(sys, managerName, rootDir);
   }

   public String doInstall(RepositorySource src) {
      ArrayList<String> args = new ArrayList<String>();
      String resFile = src.pkg.installedRoot;
      args.add("scp");
      args.add("-r");
      args.add(src.url);
      args.add(resFile);
      if (rootSystem.options.verbose)
         System.out.println("Running: " + argsToString(args));
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
      return null;
   }

}
