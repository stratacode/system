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
public class GitRepositoryManager extends AbstractRepositoryManager {
   public GitRepositoryManager(RepositorySystem sys, String managerName, String rootDir, IMessageHandler handler, boolean info) {
      super(sys, managerName, rootDir, handler, info);
   }
   public String doInstall(RepositorySource src, DependencyContext ctx, DependencyCollection deps) {
      return gitInstall(src, this);
   }

   public static String gitInstall(RepositorySource src, AbstractRepositoryManager mgr) {
      ArrayList<String> args = new ArrayList<String>();
      args.add("git");
      args.add("clone");
      args.add(src.url);
      String resDir = src.pkg.installedRoot;
      args.add(resDir);
      if (mgr.system.installExisting && new File(resDir).isDirectory()) {
         if (mgr.info)
            mgr.info("Using existing install: " + resDir);
         return null;
      }
      if (mgr.info)
         mgr.info("Running git install: " + mgr.argsToString(args));
      String res = FileUtil.execCommand(args, null);
      if (res == null)
         return "Error: failed to run install command";
      if (mgr.info)
         mgr.info("Completed git install: " + mgr.argsToString(args));
      return null;
   }
}
