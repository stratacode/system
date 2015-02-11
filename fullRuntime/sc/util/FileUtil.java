/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;

import sc.util.zip.ExtraFieldUtils;
import sc.util.zip.ZipExtraField;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class FileUtil {
   public static final String LINE_SEPARATOR = System.getProperty("line.separator");
   public static final String PATH_SEPARATOR = System.getProperty("path.separator");
   public static final String FILE_SEPARATOR = System.getProperty("file.separator");
   public static final char PATH_SEPARATOR_CHAR = PATH_SEPARATOR.charAt(0);
   public static boolean nonStandardFileSeparator = !FILE_SEPARATOR.equals("/");

   public static void saveStringAsFile(String fileName, String data, boolean mkdirs) {
      FileUtil.saveStringAsFile(new File(fileName), data, mkdirs);
   }

   /**
    * For files we generate, mark them as 'read only' to avoid accidentally modifying them and as a signal to the editor and IDE.
    * We also can use this flag as another hint as to whether the file has been edited accidentally.
    */
   public static void saveStringAsReadOnlyFile(String fileName, String data, boolean mkdirs) {
      File file = new File(fileName);
      if (file.canRead()) {
         if (file.canWrite()) {
            file.renameTo(new File(fileName + "_savedEdits"));
         }
         else
            file.setWritable(true);
      }
      FileUtil.saveStringAsFile(file, data, mkdirs);
      file.setReadOnly();
   }

   public static void saveStringAsFile(File theFile, String data, boolean mkdirs) {
      FileWriter f = null;
      PerfMon.start("saveStringAsFile");
      try {
         if (mkdirs) {
            File par = theFile.getParentFile();
            if (par != null)
               par.mkdirs();
         }

         f = new FileWriter(theFile, false);

         f.write(data, 0, data.length());

         f.flush();
      }
      catch (IOException exc) {
         String fm = "**** Can't write file: " + theFile + ": " + exc.toString();
         System.out.println(fm);
         throw new IllegalArgumentException(fm);
      }
      finally {
         if (f != null)
            try { f.close(); } catch (IOException exc) {
               System.err.println("*** error closing file: " + exc);
            }
         PerfMon.end("saveStringAsFile");
      }
   }

   public static byte[] getFileAsBytes(String fileName) {
      try {
         File f = new File(fileName);
         long len = f.length();
         byte [] buffer = new byte[(int) len];
         FileInputStream is = new FileInputStream(fileName);
         if (is.read(buffer) != len)
            throw new IllegalArgumentException("Unable to read all of file: " + fileName + ": len");
         return buffer;
      }
      catch (FileNotFoundException exc)
      {
         throw new IllegalArgumentException("File not found: " + exc);

      }
      catch (IOException exc)
      {
         throw new IllegalArgumentException("Error reading file: " + exc);
      }
   }

   public static String getExtension(String srcFileName) {
      int ix = srcFileName.lastIndexOf(".");
      if (ix == -1)
         return null;
      return srcFileName.substring(ix+1);
   }

   public static String replaceExtension(String fileName, String s) {
      int ix = fileName.lastIndexOf(".");
      if (ix == -1)
         return fileName + "." + s;
      else
         return fileName.substring(0, ix+1) + s;
   }

   public static String addExtension(String fileName, String s) {
      return fileName + "." + s;
   }

   public static String removeExtension(String fileName) {
      int ix = fileName.lastIndexOf(".");
      if (ix == -1)
         return fileName;
      else
         return fileName.substring(0, ix);
   }

   public static String concat(String... params) {
      String result = null;
      for (String param:params) {
         if (param != null && param.length() > 0) {
            if (result == null)
               result = param;
            else if (result.endsWith(FileUtil.FILE_SEPARATOR))
               result = result + param;
            else
               result = result + FileUtil.FILE_SEPARATOR + param;
         }
      }
      return result;
   }

   public static String concatNormalized(String... params) {
      String result = null;
      for (String param:params) {
         if (param != null && param.length() > 0) {
            if (result == null)
               result = param;
            else if (result.endsWith("/"))
               result = result + param;
            else
               result = result + '/' + param;
         }
      }
      return result;
   }

   /** Returns the last component of the path name - the directory name or file name part of the path */
   public static String getFileName(String pathName) {
      while (pathName.endsWith(FILE_SEPARATOR))
         pathName = pathName.substring(0, pathName.length() - 1);

      int ix = pathName.lastIndexOf(FILE_SEPARATOR);
      if (ix != -1)
         pathName = pathName.substring(ix+1);

      return pathName;
   }

   public static int countLinesInFile(File file) {
      try {
         BufferedReader reader = new BufferedReader(new FileReader(file));
         int ct = 0;
         while (reader.readLine() != null)
            ct++;
         return ct;
      }
      catch (IOException exc) {
         return -1;
      }
   }

   public static String[] getLinesInFile(File file) {
      try {
         BufferedReader reader = new BufferedReader(new FileReader(file));
         String str;
         ArrayList<String> result = new ArrayList<String>();
         while ((str = reader.readLine()) != null)
            result.add(str);
         return result.toArray(new String[result.size()]);
      }
      catch (IOException exc) {
         return null;
      }
   }

   public static String readFirstLine(File file) {
      BufferedReader reader = null;
      try {
         reader = new BufferedReader(new FileReader(file));
         String str;
         if ((str = reader.readLine()) != null)
            return str;
         return null;
      }
      catch (IOException exc) {
         return null;
      }
      finally {
         try {
            if (reader != null)
               reader.close();
         }
         catch (IOException exc) {}
      }
   }

   /** Given a/b/c.x returns a/b */
   public static String getParentPath(String relFileName) {
      if (relFileName == null)
         return null;
      int ix = relFileName.lastIndexOf(FileUtil.FILE_SEPARATOR);
      if (ix == -1)
         return null;
      return relFileName.substring(0,ix);
   }

   public static boolean caseMatches(File f) {
      // TODO: can skip this for linux systems but how to tell that reliably?  Also, any way to just get the base name's
      // case?  Should we validate directories too?
      try {
         String canonPath = f.getCanonicalPath();
         if (f.getName().equals(FileUtil.getFileName(canonPath)))
            return true;
      }
      catch (IOException exc) {
         System.out.println("*** Error trying to verify path of file: " + f.getPath() + ": " + exc);
      }
      return false;
   }

   public static List<String> getRecursiveFiles(String buildDir, String prefix, FilenameFilter filter) {
      List<String> result = new ArrayList<String>();

      addFilesInDirectory(buildDir, prefix, filter, result);

      return result;
   }

   /** A utility method which lists files in the given directory matching a Java regex pattern */
   public static String listFiles(String dir, String pattern) {
      List<String> result = new ArrayList<String>();

      addFilesInDirectory(dir, "", new PatternFilenameFilter(Pattern.compile(pattern)), result);

      for (int i = 0; i < result.size(); i++)
         result.set(i, FileUtil.concat(dir, result.get(i)));

      return StringUtil.arrayToPath(result.toArray());
   }

   private static void addFilesInDirectory(String buildDir, String prefix, FilenameFilter filter, List<String> result) {
      File buildDirFile = prefix == null ? new File(buildDir) : new File(buildDir, prefix);
      String[] files = buildDirFile.list(filter);
      if (files != null) {
         for (int i = 0; i < files.length; i++) {
            String fileName = files[i];
            File f = new File(buildDirFile, fileName);
            if (f.isDirectory())
               addFilesInDirectory(buildDir, FileUtil.concat(prefix, fileName), filter, result);
            else
               result.add(FileUtil.concat(prefix,fileName));
         }
      }
      else {
         if (!buildDirFile.isDirectory()) {
            if (buildDirFile.canRead())
               System.err.println("Not a directory: " + buildDirFile);
            else
               System.err.println("No directory: " + buildDirFile);
         }
      }
   }

   public static String exec(String input, boolean echoOutput, String... args) {
      return execCommand(Arrays.asList(args), input, 0, echoOutput);
   }

   public static int fork(String inputString, boolean echoOutput, String... args) {
      if (echoOutput)
         System.out.println("Running: " + StringUtil.argsToString(args));
      Process p = startCommand(Arrays.asList(args));
      if (p == null) {
         System.err.println("*** Start process failed for: " + StringUtil.argsToString(args));
         return -1;
      }

      InputThread inputThread = null;
      if (inputString != null) {
         inputThread = new InputThread(p, inputString);
         inputThread.start();
      }

      // Start up a thread to read any output from this command
      new OutputThread(args[0], p, echoOutput).start();

      return 0;
   }

   public static String execCommand(List<String> args, String inputString) {
      return execCommand(args, inputString, 0, false);
   }

   public static StringBuilder readInputStream(InputStream is) {
      StringBuilder sb = new StringBuilder();

      BufferedReader bis = new BufferedReader(new InputStreamReader(is));
      char [] buf = new char[4096];
      int len;
      try {
         while ((len = bis.read(buf, 0, buf.length)) != -1) {
            sb.append(new String(buf, 0, len));
         }
         return sb;
      }
      catch (IOException exc) {
         System.err.println("*** Failed to read from input stream: " + exc);
      }
      return null;
   }

   public static Process startCommand(List<String> args) {
      // Handle simple unix shell scripts so we don't have to have special logic for windows to run as long as
      // cygwin or the shell at least is installed
      args = fixArgsForSystem(args);

      ProcessBuilder pb = new ProcessBuilder(args);

      try {
         return pb.start();
      }
      catch (IOException exc) {
         if (args != null)
            System.err.println("*** command: " + args.toString() + " failed: " + exc);
      }
      return null;
   }

   public static String execCommand(List<String> args, String inputString, int successResult, boolean echoOutput) {
      // Handle simple unix shell scripts so we don't have to have special logic for windows to run as long as
      // cygwin or the shell at least is installed
      args = fixArgsForSystem(args);

      ProcessBuilder pb = new ProcessBuilder(args);

      try {
         Process p = pb.start();

         InputThread inputThread = null;
         if (inputString != null) {
            inputThread = new InputThread(p, inputString);
            inputThread.start();
         }

         BufferedReader bis = new BufferedReader(new InputStreamReader(p.getInputStream()));
         char [] buf = new char[1024];
         StringBuilder sb = new StringBuilder();
         int len;
         while ((len = bis.read(buf, 0, buf.length)) != -1) {
            String out = new String(buf, 0, len);
            if (echoOutput)
               System.out.println(out);
            sb.append(out);
         }
         int stat = p.waitFor();
         if (inputThread != null && inputThread.errorString != null)
            System.err.println("*** exec command: " + StringUtil.argsToString(args) + " - error reading input: " + inputThread.errorString);
         else if (stat == successResult)
            return sb.toString();
         else
            System.err.println("*** exec command: " + StringUtil.argsToString(args) + " - returns status " + stat);
      }
      catch (InterruptedException exc) {
         if (args != null)
            System.err.println("*** exec command: " + StringUtil.argsToString(args) + " - wait interrupted: " + exc);
      }
      catch (IOException exc) {
         if (args != null)
            System.err.println("*** command: " + StringUtil.argsToString(args) + " failed: " + exc);
      }
      return null;
   }

   private static List<String> fixArgsForSystem(List<String> args) {
      String command = args.get(0);
      if (FileUtil.FILE_SEPARATOR.equals("\\")) {
         String firstLine = readFirstLine(new File(command));
         if (firstLine != null && firstLine.startsWith("#!")) {
            firstLine = FileUtil.unnormalize(firstLine.substring(2));
            String cygHome = System.getProperty("CYGWIN_HOME");
            if (cygHome != null)
               firstLine = FileUtil.concat(cygHome, firstLine) + ".exe";
            else
               firstLine = FileUtil.getFileName(firstLine) + ".exe";
            ArrayList<String> newCommands = new ArrayList<String>(args.size()+1);
            newCommands.add(firstLine);
            newCommands.addAll(args);
            return newCommands;
         }
      }
      return args;
   }

   public static String getRelativeFile(String dirName, String relPath) {
      File f = new File(relPath);
      if (!f.isAbsolute() && !relPath.startsWith("/")) {
         while (relPath != null && relPath.startsWith(".")) {
            if (relPath.startsWith("..")) {
               relPath = relPath.length() == 2 ? FileUtil.FILE_SEPARATOR : relPath.substring(3);
               dirName = FileUtil.getParentPath(dirName);
               if (dirName == null)
                  return null;
            }
            else if (relPath.startsWith(".")) {
               relPath = relPath.length() == 1 ? FileUtil.FILE_SEPARATOR : relPath.substring(2);
            }
         }
         return FileUtil.concat(dirName, relPath);
      }
      return relPath;
   }

   public static String getFileAsString(String fileName) {
      return new String(FileUtil.getFileAsBytes(fileName));
   }

   public static byte[] computeHash(String newFile) {
      return StringUtil.computeHash(getFileAsBytes(newFile));
   }

   public static boolean isAbsolutePath(String layerPathName) {
      return new File(layerPathName).isAbsolute();
   }

   public static void renameFile(String oldFileName, String newFileName) {
      new File(oldFileName).renameTo(new File(newFileName));
   }

   public static void touchFile(String file) {
      new File(file).setLastModified(System.currentTimeMillis());
   }

   public static String removeTrailingSlash(String pathName) {
      while (pathName.endsWith(FileUtil.FILE_SEPARATOR))
         pathName = pathName.substring(0, pathName.length()-1);
      return pathName;
   }

   private final static int BUFFER_SIZE = 64*1024;

   public static boolean unzip(String zipFile, String resName) {
      String zipRes = exec(null, true, "unzip", zipFile, "-d", resName);
      if (zipRes == null) {
         System.err.println("*** zip failed");
         return false;
      }
      return true;
   }

   /**
    * TODO: remove this and the zip package?  This does not preserve the execute permissions... I have not found where
    * that metadata exists in the zip file.  There are supposedly two "extra" data sections one in the header and one in the
    * entry itself and I have only found one of them in the Java apis.  I carved out just a little of the apache code
    * that handles the extra data fields and that code seems to work so leaving this in for now in case it's useful in
    * the future.
    */
   public static boolean unzipJava(String zipFile, String resName) {
      File resDir = new File(resName);
      if (resDir.canRead() || resDir.isDirectory()) {
         System.err.println("*** unzip - res dir: " + resName + " exists");
         return false;
      }
      try {
         ZipFile zf = new ZipFile(zipFile);
         Enumeration<? extends ZipEntry> zfe = zf.entries();

         while (zfe.hasMoreElements()) {
            ZipEntry ze = zfe.nextElement();
            String fileRelName = ze.getName();
            String fileAbsName = FileUtil.concat(resName, fileRelName);

            File f = new File(fileAbsName);
            File destParent = f.getParentFile();
            destParent.mkdirs();
            byte[] extra = ze.getExtra();
            int unixFileMode = ExtraFieldUtils.getUnixFileMode(extra);
            if (!ze.isDirectory()) {
               BufferedInputStream is = new BufferedInputStream(zf.getInputStream(ze));
               int bytesRead;
               byte[] data = new byte[BUFFER_SIZE];

               FileOutputStream fos = new FileOutputStream(fileAbsName);
               BufferedOutputStream dst = new BufferedOutputStream(fos, BUFFER_SIZE);

               try {
                  while ((bytesRead = is.read(data, 0, BUFFER_SIZE)) != -1) {
                     dst.write(data, 0, bytesRead);
                  }
                  dst.flush();
               }
               finally {
                  try { dst.close(); } catch (IOException exc) {}
                  try { is.close(); } catch (IOException exc) {}
               }
               if ((unixFileMode & 0111) != 0) {
                  f.setExecutable(true, true);
               }
               if ((unixFileMode & 0444) != 0 && (unixFileMode & 0222) == 0) {
                  f.setReadOnly();
               }
            }
            else
               f.mkdirs();
         }
         return true;
      }
      catch (IOException exc) {
         System.err.println("*** can't read zip file: " + zipFile + ": " + exc);
         return false;
      }
   }

   private static class OutputThread extends Thread {
      Process process;
      String name;
      boolean echoOutput;

      OutputThread(String name, Process p, boolean echoOutput) {
         process = p;
         this.name = name;
         this.echoOutput = echoOutput;
      }

      public void run() {
         BufferedInputStream bis = new BufferedInputStream(process.getInputStream());
         byte [] buf = new byte[1024];
         int len;

         try {
            while ((len = bis.read(buf, 0, buf.length)) != -1) {
               if (len > 0) {
                  String str = new String(buf, 0, len, "UTF-8");
                  if (echoOutput && str.length() > 0) {
                     System.out.println("Output from StrataCode forked command: " + name + ": ");
                     System.out.println(str);
                  }
               }
            }
         }
         catch (IOException e) {
            System.err.println("*** failed to read from command: " + name + ": " + e);
         }

         System.out.println("Command: " + name + " exited:");
      }
   }

   private static class InputThread extends Thread {
      Process process;
      StringReader inputReader;
      String errorString;

      InputThread(Process p, String inputString) {
         inputReader = new StringReader(inputString);
         process = p;
      }

      public void run() {
         char[] buf = new char[1024];
         int len;
         // TODO: get the encoding out of the file and pass it to the output stream writer
         BufferedWriter outputWriter = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
         try {
            while ((len = inputReader.read(buf, 0, 1024)) > 0) {
               outputWriter.write(buf, 0, len);
               outputWriter.flush();
            }
            outputWriter.flush();
            outputWriter.close();
         }
         catch (IOException exc) {
            errorString = exc.toString();
         }
      }
   }

   public static String normalize(String path) {
      if (nonStandardFileSeparator)
         return path.replace(FILE_SEPARATOR, "/");
      return path;
   }

   public static String unnormalize(String path) {
      if (nonStandardFileSeparator)
         return path.replace("/", FILE_SEPARATOR);
      return path;
   }

   private static final int TEMP_DIR_TRYCT = 3;

   public static File createTempDir(String prefix) {
      File tempDir = new File(System.getProperty("java.io.tmpdir"));
      String baseName = prefix + "-" + System.currentTimeMillis() + "-";

      for (int ct = 0; ct < TEMP_DIR_TRYCT; ct++) {
         File newDirName = new File(tempDir, baseName + ct);
         if (newDirName.mkdir()) {
            return newDirName;
         }
      }
      throw new IllegalArgumentException("Failed to create temporary directory in: " + tempDir + " file: " + baseName + "0..." + baseName + (TEMP_DIR_TRYCT - 1));
   }

   public static boolean copyAllFiles(String srcDirName, String dstDirName, boolean mkdirs, FilenameFilter filter) {
      File srcDir = new File(srcDirName);
      File dstDir = new File(dstDirName);
      if (mkdirs)
         dstDir.mkdirs();

      if (!dstDir.isDirectory()) {
         System.err.println("*** Invalid destination directory for copyAllFiles: " + dstDirName);
         return false;
      }

      boolean res = false;

      List<String> allFiles = getRecursiveFiles(srcDirName, null, filter);
      for (String file:allFiles) {
         String srcFile = FileUtil.concat(srcDirName, file);
         String dstFile = FileUtil.concat(dstDirName, file);
         res |= copyFile(srcFile, dstFile, true);

         if (!res) {
            System.err.println("*** Error copying file: " + srcFile + " to: " + dstFile);
            break;
         }
      }
      return res;
   }

   public static boolean copyFile(String srcFileName, String dstFileName, boolean mkdirs) {
      InputStream in = null;
      OutputStream out = null;
      try {
         File srcFile  = new File(srcFileName);
         File dstFile = new File(dstFileName);
         if (mkdirs) {
            File par = dstFile.getParentFile();
            if (par != null)
               par.mkdirs();
         }
         in = new FileInputStream(srcFile);
         out = new FileOutputStream(dstFile);

         byte[] buf = new byte[8*1024];
         int len;
         while ((len = in.read(buf)) > 0){
            out.write(buf, 0, len);
         }
         return true;
      }
      catch(FileNotFoundException ex) {
         return false;
      }
      catch(IOException e) {
         System.err.println("Error copying file: " + srcFileName + " to: " + dstFileName + ": " +  e.getMessage());
      }
      finally {
         if (out != null)
            try { out.close(); } catch (IOException exc) {}
         if (in != null)
            try { in.close(); } catch (IOException exc) {}
      }
      return false;
   }

   public static URL newFileURL(String fileName) {
      try {
         return new URL("file", null, 0, normalize(fileName));
      }
      catch (MalformedURLException exc) {
         System.err.println(exc);
         return null;
      }
   }

   public static void removeDirectory(String dirName) {
      removeFileOrDirectory(new File(dirName));
   }

   public static void removeFileOrDirectory(File f) {
      if (f.isDirectory()) {
         for (File c : f.listFiles())
            removeFileOrDirectory(c);
      }
      if (!f.delete())
         System.err.println("*** Failed to remove: " + f);
   }

   public static String makeAbsolute(String fileName) {
      File file = new File(fileName);
      if (!file.isAbsolute()) {
         try {
            return file.getCanonicalPath();
         }
         catch (IOException exc) {
            return fileName;
         }
      }
      return fileName;
   }

   public static String makeCanonical(String dir) throws IOException {
      File layerDirFile = new File(dir);
      dir = layerDirFile.getCanonicalPath();
      return dir;
   }

   public static String makePathAbsolute(String path) {
      String[] pathDirs = StringUtil.split(path, ':');
      StringBuilder sb = new StringBuilder();
      boolean first = true;
      for (String pathDir:pathDirs) {
         if (!first)
            sb.append(":");
         else
            first = false;
         sb.append(FileUtil.makeAbsolute(pathDir));
      }
      return sb.toString();
   }
}
