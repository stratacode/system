/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import sc.util.IMessageHandler;
import sc.util.FileUtil;

import java.util.ArrayList;

/**
 */
public class GitRepositoryManager extends AbstractRepositoryManager {
   public GitRepositoryManager(String managerName, String rootDir, IMessageHandler handler, boolean info) {
      super(managerName, rootDir, handler, info);
   }

   public String doInstall(RepositorySource src) {
      ArrayList<String> args = new ArrayList<String>();
      args.add("git");
      args.add("clone");
      args.add(src.url);
      String resDir = src.pkg.installedRoot;
      args.add(resDir);
      if (info)
         info("Running git install: " + argsToString(args));
      String res = FileUtil.execCommand(args, null);
      if (res == null)
         return "Error: failed to run install command";
      if (info)
         info("Completed git install: " + argsToString(args));
      return null;
   }
}
