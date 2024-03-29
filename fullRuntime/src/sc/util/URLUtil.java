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
import java.util.Arrays;
import java.util.List;

public class URLUtil {
   public static Character[] URL_SPECIAL_CHARS = new Character[] {'$','-', '_', '.', '+', '!', '*', '\'', '(', ')', ',' };
   public static List<Character> URL_SPECIAL_CHARS_LIST = Arrays.asList(URL_SPECIAL_CHARS);

   public static boolean isURLCharacter(char c) {
      return Character.isAlphabetic(c) || Character.isDigit(c) || URL_SPECIAL_CHARS_LIST.contains(c);
   }

   /** Given a/b/c.x returns a/b */
   public static String getParentPath(String relFileName) {
      if (relFileName == null)
         return null;
      int ix = relFileName.lastIndexOf('/');
      if (ix == -1)
         return null;
      return relFileName.substring(0,ix);
   }

   /** Given a/b/c.x returns a */
   public static String getRootPath(String fileName) {
      if (fileName == null)
         return null;
      int ix = fileName.indexOf('/');
      if (ix == -1)
         return null;
      return fileName.substring(0, ix);
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
            // Here we are processing any URLs that start with "../". We're only looking at the first
            // part of the path and then will remove the last directory in the current result as well as the "../"
            while (param.startsWith("../") && result != null) {
               int lastIx = result.lastIndexOf("/");
               param = param.substring(3);
               if (lastIx == -1)
                  result = "/";
               else
                  result = result.substring(0, lastIx);
            }
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
      ReadableByteChannel rbc = null;
      try {
         url = new URL(urlPath);
         if (unzip )
            fileName = FileUtil.addExtension(fileName, "zip");
         MessageHandler.info(msg, "Downloading url: " + urlPath + " into: " + fileName);
         URLConnection conn = url.openConnection();
         if (conn instanceof HttpURLConnection) {
            HttpURLConnection hconn = (HttpURLConnection) conn;
            ((HttpURLConnection) conn).setInstanceFollowRedirects(true);
            ((HttpURLConnection) conn).setFollowRedirects(true);
            // For some reason follow redirects did not work
            if (hconn.getResponseCode() == 302) {
               String newURL = hconn.getHeaderField("Location");
               if (newURL != null) {
                  if (newURL.equals(urlPath)) {
                     msg.reportMessage("redirect loop: " + urlPath, null, -1, -1, MessageType.Error);
                     return "Redirect loop: " + urlPath;
                  }
                  else {
                     return saveURLToFile(newURL, fileName, unzip, msg);
                  }
               }

            }
            MessageHandler.info(msg, "Response: " + hconn.getResponseCode());
            //System.out.println("*** " + hconn.getHeaderField("Location"));
         }
         rbc = Channels.newChannel(conn.getInputStream());
         try {
            fos = new FileOutputStream(fileName);
         }
         catch (FileNotFoundException fexc) {
            return "Unable to create output file: " + fileName + " error: " + fexc.toString();
         }
      }
      catch (MalformedURLException exc) {
         return "Bad url: " + urlPath + " error: " + exc.toString();
      }
      catch (FileNotFoundException fexc) {
         return "URL not found on server: " + urlPath;
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
         FileUtil.safeClose(rbc);
      }

      MessageHandler.info(msg, "Completed download of url: " + urlPath + " into: ", fileName);

      if (unzip) {
         String noSuffix = FileUtil.removeExtension(fileName);
         if (noSuffix.equals(fileName))
            MessageHandler.error(msg, "Zip files must have a suffix of .zip or .jar: ", fileName);
         else {
            if (!FileUtil.unzip(fileName, noSuffix, true))
               return "Failed to unzip: " + fileName + " into: " + noSuffix;
         }
      }
      return null;
   }

   /** Returns false for either http://x.com/y or /x true for just x */
   public static boolean isRelativeURL(String url) {
      if (url.startsWith("/") || url.startsWith("#"))
         return false;
      int queryIx = url.indexOf('?');
      if (queryIx != -1) {
         url = url.substring(0, queryIx);
      }
      if (url.contains("://"))
         return false;
      return true;
   }

   public static String cleanFileName(String inStr) {
      StringBuilder sb = null;
      int len = inStr.length();
      for (int i = 0; i < len; i++) {
         char c = inStr.charAt(i);
         boolean skip = false;
         if (!Character.isAlphabetic(c) && !Character.isDigit(c)) {
            switch (c) {
               case '.':
                  if (i == len-1 || inStr.charAt(i+1) == '.')
                     skip = true;
                  break;
               case '-':
               case '_':
               case '/':
                  break;
               default:
                  skip = true;
                  break;
            }
            if (skip) {
               if (sb == null) {
                  sb = new StringBuilder();
                  if (i > 0)
                     sb.append(inStr.substring(0, i));
               }
               if (c == ' ')
                  sb.append('_');
            }
            else if (sb != null)
               sb.append(c);
         }
      }
      return sb == null ? inStr : sb.toString();
   }
}
