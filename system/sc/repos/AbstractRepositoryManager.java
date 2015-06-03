/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import sc.util.IMessageHandler;
import sc.util.MessageHandler;
import sc.util.MessageType;
import sc.util.FileUtil;

import java.io.File;
import java.util.List;

public abstract class AbstractRepositoryManager implements IRepositoryManager {
   public String managerName;
   public String packageRoot;

   public RepositorySystem system;
   public IMessageHandler msg;
   public boolean info;

   public boolean active = true;

   public final static String INSTALLED_TAG_DIR_NAME = "_installed";

   public boolean isActive() {
      return active;
   }

   public String getManagerName() {
      return managerName;
   }

   public String getPackageRoot() {
      return packageRoot;
   }

   public AbstractRepositoryManager(RepositorySystem sys, String mn, String reposRoot, IMessageHandler handler, boolean info) {
      this.system = sys;
      this.managerName = mn;
      packageRoot = reposRoot;

      this.msg = handler;
      this.info = info;
   }

   public String install(RepositorySource src) {
      // Putting this into the installed root so it more reliably gets removed if the folder itself is removed
      File tagFile = new File(src.pkg.installedRoot, ".scPkgInstallInfo");
      File rootFile = new File(src.pkg.installedRoot);
      // TODO: right now, we are only installing directories but would we ever install files as well?
      boolean rootDirExists = rootFile.canRead() || rootFile.isDirectory();
      String rootParent = FileUtil.getParentPath(src.pkg.installedRoot);
      if (rootParent != null)
         new File(rootParent).mkdirs();
      long installedTime = -1;
      if (rootDirExists && tagFile.canRead()) {
         RepositoryPackage oldPkg = RepositoryPackage.readFromFile(tagFile);
         if (oldPkg != null && src.pkg.updateFromSaved(oldPkg))
            installedTime = oldPkg.installedTime;
      }
      long packageTime = getLastModifiedTime(src);
      // No last modified time for this source... assume it's up to date unless it's not installed
      if (packageTime == -1) {
         if (installedTime != -1) {
            if (info)
               info("Package: " + src.pkg.packageName + " already installed");
            return null;
         }
      }
      else if (installedTime > packageTime) {
         if (info)
            info("Package: " + src.pkg.packageName + " uptodate");
         return null;
      }
      if (info)
         info("Installing package: " + src.pkg.packageName + " from: " + src.url);
      String err = doInstall(src);
      if (err != null) {
         tagFile.delete();
         System.err.println("Installing package: " + src.pkg.packageName + " failed: " + err);
         return err;
      }
      else {
         src.pkg.installedTime = System.currentTimeMillis();
         src.pkg.saveToFile(tagFile);
         //FileUtil.saveStringAsFile(tagFile, String.valueOf(System.currentTimeMillis()), true);
      }
      return null;
   }

   public abstract String doInstall(RepositorySource src);

   public void info(String infoMessage) {
      if (msg != null) {
         msg.reportMessage(infoMessage, null, -1, -1, MessageType.Info);
      }
      if (info)
         System.out.println(infoMessage);
   }

   public long getLastModifiedTime(RepositorySource src) {
      return -1;
   }

   public String update(RepositorySource src) {
      // TODO - not yet implemented
      return null;
   }

   protected String argsToString(List<String> args) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < args.size(); i++) {
         if (i != 0)
            sb.append(" ");
         sb.append(args.get(i));
      }
      return sb.toString();
   }

   public void setMessageHandler(IMessageHandler handler) {
      this.msg = handler;
   }

   public RepositoryPackage createPackage(String url) {
      // TODO: can we support this URL scheme for other repositories?
      MessageHandler.error(msg, "URL based packages not supported for repository type: " + getClass().getName());
      return null;
   }
}
