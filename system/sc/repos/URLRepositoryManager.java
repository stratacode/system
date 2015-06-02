/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import sc.util.IMessageHandler;
import sc.util.FileUtil;
import sc.util.URLUtil;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.Channels;

/**
 * For this RepoistoryManager, packages are stored from the URL and downloaded via the URL protocol handler built into Java.
 * From there they are transfered to the installedRoot, which is then typically unzipped.
 */
public class URLRepositoryManager extends AbstractRepositoryManager {
   public URLRepositoryManager(String managerName, String rootDir, IMessageHandler handler, boolean info) {
      super(managerName, rootDir, handler, info);
   }

   public String doInstall(RepositorySource src) {
      return URLUtil.saveURLToFile(src.url, src.pkg.installedRoot, src.unzip, messageHandler);
   }
}
