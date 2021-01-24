/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import sc.util.IMessageHandler;
import sc.util.FileUtil;
import sc.util.StringUtil;

import java.io.File;
import java.util.ArrayList;

/**
 * Implements the repository interface for retrieving files from a git repository
 * The repository src url is of the form:
 *   cloneAddress[#branch-selector]
 *
 *  where cloneAddress can be an ssh or https git repository URL and branch-selector, if provided, specifies the branch, commit hash or other arg to 'checkout'.
 */
public class GitRepositoryManager extends AbstractRepositoryManager {
   public GitRepositoryManager(RepositorySystem sys, String managerName, String rootDir, IMessageHandler handler, boolean info) {
      super(sys, managerName, rootDir, handler, info);
      srcRepository = true;
   }
   public String doInstall(RepositorySource src, DependencyContext ctx, DependencyCollection deps) {
      String res = gitInstall(src, this);
      if (res == null && src.pkg != null && src.pkg.subPackages != null) {
         for (RepositoryPackage subPkg:src.pkg.subPackages) {
            deps.addDependency(subPkg, ctx);
         }
      }
      return res;
   }

   public static String gitInstall(RepositorySource src, AbstractRepositoryManager mgr) {
      String srcURL = src.getPackageSrcURL();
      int branchIx = srcURL.lastIndexOf('#');
      String branchSel = null;
      if (branchIx != -1) {
         branchSel = srcURL.substring(branchIx+1);
         srcURL = srcURL.substring(0, branchIx);
      }

      ArrayList<String> args = new ArrayList<String>();
      args.add("git");
      args.add("clone");
      // TODO: make this configurable - we need the whole history if we are going to checkout a branch, tag, etc
      if (branchSel == null) {
         args.add("--depth");
         args.add("1");
      }
      args.add(srcURL);
      String resDir = src.pkg.installedRoot;
      args.add(resDir);
      File resDirFile = new File(resDir);
      if (resDirFile.isDirectory() && !isEmptyDir(resDirFile)) {
         if (mgr.info)
            mgr.info("Using existing install of: " + src.pkg.packageName + " in: " + resDir);
         return null;
      }
      if (mgr.info) {
         mgr.info("Installing git package: " + src.pkg.packageName + " in: " + resDir + (branchSel == null ? " with branch selector: " + branchSel : ""));
         mgr.info("Running: " + mgr.argsToString(args));
      }
      StringBuilder errors = new StringBuilder();
      String res = FileUtil.execCommand(null, args, null, null, 0, false, errors);
      if (res == null)
         return "Git repository: " + src.pkg.packageName + " install cmd: " + StringUtil.arrayToString(args.toArray()) + " failed: " + errors;

      if (branchSel != null) {
         args = new ArrayList<String>();
         args.add("git");
         args.add("checkout");
         args.add(branchSel);
         res = FileUtil.execCommand(resDir, args, null, null, 0, false, errors);
         if (res == null)
            return "Git repository: " + src.pkg.packageName + " checkout cmd: " + StringUtil.arrayToString(args.toArray()) + " failed: " + errors;
      }
      if (mgr.info)
         mgr.info("Completed git install: " + mgr.argsToString(args));
      return null;
   }
}
