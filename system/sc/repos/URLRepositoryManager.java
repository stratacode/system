/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos;

import sc.layer.LayeredSystem;
import sc.util.FileUtil;

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
   public URLRepositoryManager(LayeredSystem sys, String managerName, String rootDir) {
      super(sys, managerName, rootDir);
   }

   public String doInstall(RepositorySource src) {
      URL url;
      FileOutputStream fos;
      ReadableByteChannel rbc;
      String fileName;
      try {
         url = new URL(src.url);
         if (rootSystem.options.verbose)
            System.out.println("Downloading url: " + src.url + " into: " + src.pkg.installedRoot);
         rbc = Channels.newChannel(url.openStream());
         fileName = src.pkg.installedRoot;
         if (src.unzip)
            fileName = FileUtil.addExtension(fileName, "zip");
         fos = new FileOutputStream(fileName);
      }
      catch (MalformedURLException exc) {
         return "Bad url: " + src.url + " error: " + exc.toString();
      }
      catch (FileNotFoundException fexc) {
         return "Unable to create output file: " + src.pkg.installedRoot + " error: " + fexc.toString();
      }
      catch (IOException ioexc) {
         return "Error opening url: " + src.url + " error: " + ioexc.toString();
      }
      try {
         long numBytes = fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
         if (numBytes <= 0)
            return "Download returned no data: " + numBytes + " bytes returned";
      }
      catch (IOException exc) {
         return "Error downloading from URL: " + src.url + " details: " + exc.toString();
      }

      if (src.unzip) {
         String noSuffix = FileUtil.removeExtension(fileName);
         if (noSuffix.equals(fileName))
            System.err.println("*** Zip files must have a suffix of .zip or .jar: " + fileName);
         else {
            if (!FileUtil.unzip(fileName, noSuffix))
               return "Failed to unzip: " + fileName + " into: " + noSuffix;
         }
      }
      return null;
   }
}
