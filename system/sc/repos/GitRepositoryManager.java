/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import sc.layer.LayeredSystem;
import sc.util.FileUtil;

import java.util.ArrayList;

/**
 */
public class GitRepositoryManager extends AbstractRepositoryManager {
   public GitRepositoryManager(String managerName, String rootDir, boolean verbose) {
      super(managerName, rootDir, verbose);
   }

   public String doInstall(RepositorySource src) {
      ArrayList<String> args = new ArrayList<String>();
      args.add("git");
      args.add("clone");
      args.add(src.url);
      String resDir = src.pkg.installedRoot;
      args.add(resDir);
      if (verbose)
         System.out.println("Running: " + argsToString(args));
      String res = FileUtil.execCommand(args, null);
      if (res == null)
         return "Error: failed to run install command";
      return null;
   }
}
