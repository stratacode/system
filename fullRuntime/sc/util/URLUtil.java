/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;

public class URLUtil {

   /** Given a/b/c.x returns a/b */
   public static String getParentPath(String relFileName) {
      if (relFileName == null)
         return null;
      int ix = relFileName.lastIndexOf('/');
      if (ix == -1)
         return null;
      return relFileName.substring(0,ix);
   }

   /** Returns the last component of the path name - the directory name or file name part of the path */
   public static String getFileName(String pathName) {
      while (pathName.endsWith("/"))
         pathName = pathName.substring(0, pathName.length() - 1);

      int ix = pathName.lastIndexOf('/');
      if (ix != -1)
         pathName = pathName.substring(ix+1);

      return pathName;
   }

   public static String concat(String... params) {
      String result = null;
      for (String param:params) {
         if (param != null && param.length() > 0) {
            if (result == null)
               result = param;
            else if (result.endsWith("/"))
               result = result + param;
            else
               result = result + "/" + param;
         }
      }
      return result;
   }

   public static String saveURLToFile(String urlPath, String fileName, boolean unzip, IMessageHandler msg) {
      URL url;
      FileOutputStream fos = null;
      ReadableByteChannel rbc;
      try {
         url = new URL(urlPath);
         MessageHandler.info(msg, "Downloading url: " + urlPath + " into: " + fileName);
         URLConnection conn = url.openConnection();
         if (conn instanceof HttpURLConnection) {
            HttpURLConnection hconn = (HttpURLConnection) conn;
            ((HttpURLConnection) conn).setInstanceFollowRedirects(true);
            ((HttpURLConnection) conn).setFollowRedirects(true);
            System.out.println("*** " + hconn.getResponseCode());
            System.out.println("*** " + hconn.getHeaderField("Location"));
         }
         rbc = Channels.newChannel(conn.getInputStream());
         if (unzip)
            fileName = FileUtil.addExtension(fileName, "zip");
         fos = new FileOutputStream(fileName);
      }
      catch (MalformedURLException exc) {
         return "Bad url: " + urlPath + " error: " + exc.toString();
      }
      catch (FileNotFoundException fexc) {
         return "Unable to create output file: " + fileName + " error: " + fexc.toString();
      }
      catch (IOException ioexc) {
         return "Error opening url: " + urlPath + " error: " + ioexc.toString();
      }
      try {
         long numBytes = fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
         if (numBytes <= 0)
            return "Download returned no data: " + numBytes + " bytes returned";
      }
      catch (IOException exc) {
         return "Error downloading from URL: " + urlPath + " details: " + exc.toString();
      }
      finally {
         FileUtil.safeClose(fos);
      }

      MessageHandler.info(msg, "Completed download of url: " + urlPath + " into: ", fileName);

      if (unzip) {
         String noSuffix = FileUtil.removeExtension(fileName);
         if (noSuffix.equals(fileName))
            MessageHandler.error(msg, "Zip files must have a suffix of .zip or .jar: ", fileName);
         else {
            if (!FileUtil.unzip(fileName, noSuffix))
               return "Failed to unzip: " + fileName + " into: " + noSuffix;
         }
      }
      return null;
   }
}
