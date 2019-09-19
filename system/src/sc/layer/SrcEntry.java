/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.obj.Constant;
import sc.type.CTypeUtil;
import sc.util.FileUtil;

import java.io.*;

/**
 * Represents a src file being processed by the layered system.
 * Stores the layer containing the file and the various path components. 
 */
public class SrcEntry implements Cloneable {
   public Layer layer;
   @Constant // eliminate binding warnings
   public String absFileName;  /* The absolute file name of the src entry */
   public String relFileName;  /* The file name relative to the layer's root directory */
   public String baseFileName; /* The name of the file within its directory */
   public boolean prependPackage = true; /* Does this type prepend its layer's package name to the type name */
   public String srcRootName; /* For src paths outside of the layer directory, the name of the source root directory. for layer source paths, it should be null */

   public transient byte[] hash = null;

   public SrcEntry() {
   }

   public SrcEntry(Layer lyr, String absDir, String relDir, String baseName, String srcRootName) {
      this(lyr, absDir, relDir, baseName, true, srcRootName);
   }

   public SrcEntry(Layer lyr, String absDir, String relDir, String baseName, boolean prepend, String srcRootName) {
      layer = lyr;
      absFileName = FileUtil.concat(absDir, baseName);
      relFileName = relDir.length() == 0 ? baseName : FileUtil.concat(relDir,baseName);
      baseFileName = baseName;
      prependPackage = prepend;
      this.srcRootName = srcRootName;
   }

   public SrcEntry(Layer lyr, String absFile, String relFile) {
      this(lyr, absFile, relFile, true, null);
   }

   public SrcEntry(Layer lyr, String absFile, String relFile, boolean prepend) {
      this(lyr, absFile, relFile, prepend, null);
   }

   public SrcEntry(Layer lyr, String absFile, String relFile, boolean prepend, String srcRootName) {
      layer = lyr;
      absFileName = absFile;
      relFileName = relFile;
      baseFileName = FileUtil.getFileName(relFileName);
      prependPackage = prepend;
      this.srcRootName = srcRootName;
   }

   public int hashCode() {
      return absFileName.hashCode();
   }
   public boolean equals(Object o) {
      if (!(o instanceof SrcEntry))
         return false;
      return absFileName.equals(((SrcEntry) o).absFileName);
   }

   public static String getTypeNameFromRelDirAndBaseName(String relDir, String baseName) {
      String relFileName = relDir.length() == 0 ? baseName : FileUtil.concat(relDir, baseName);
      return getTypeNameFromRelName(relFileName);
   }

   public static String getTypeNameFromRelName(String relFileName) {
      return FileUtil.removeExtension(relFileName.replace(FileUtil.FILE_SEPARATOR, "."));
   }

   public String getTypeName() {
      return CTypeUtil.prefixPath(layer == null || !prependPackage ? "" : layer.packagePrefix, getTypeNameFromRelName(relFileName));
   }

   public void setTypeName(String typeName, boolean renameFile) {
      String oldAbsFileName = absFileName;
      String dirName = FileUtil.getParentPath(absFileName);
      String ext = FileUtil.getExtension(baseFileName);
      baseFileName = FileUtil.addExtension(typeName, ext);
      absFileName = FileUtil.concat(dirName, baseFileName);
      relFileName = FileUtil.concat(FileUtil.getParentPath(relFileName), baseFileName);
      if (renameFile)
         FileUtil.renameFile(oldAbsFileName, absFileName);
   }

   /** Occasionally you need the type name without the prefix, i.e. when not prepending the type name on generated files */
   public String getRelTypeName() {
      return getTypeNameFromRelName(relFileName);
   }

   public String getRelDir() {
      return FileUtil.getParentPath(relFileName);
   }

   public String getExtension() {
      return FileUtil.getExtension(baseFileName);
   }

   public String toString() {
      return relFileName + " layer: " + (layer == null ? "<null>" : layer + (srcRootName == null ? "" : "(" + srcRootName + "}"));
   }

   public String toShortString() {
      return relFileName + " layer: " + (layer == null ? "<null>" : layer.getLayerName()) + (srcRootName == null ? "" : "(" + srcRootName + ")");
   }

   public boolean canRead() {
      return new File(absFileName).canRead();
   }

   public long getLastModified() {
      return new File(absFileName).lastModified();
   }

   public InputStream getInputStream() {
      try {
         return new FileInputStream(new File(absFileName));
      }
      catch (IOException exc) {
         return null;
      }
   }

   public String getFileAsString() {
      long len = getFileSize();
      byte [] buffer = new byte[(int) len];
      InputStream is = getInputStream();
      if (is == null)
         return null;
      int start = 0;
      try {
         while (len > 0) {
            // Even though read is supposed to be blocking, for Zip files it's doing some buffering so need this loop.
            int readLen = is.read(buffer, start, (int) len);
            if (readLen == -1)
               throw new IllegalArgumentException("Unable to read all of file - read for: " + start + ":" + len + " failed for: " + absFileName);
            if (readLen == len)
               return new String(buffer);
            start += readLen;
            len -= readLen;
         }
      }
      catch (IOException exc) {
         throw new IllegalArgumentException("Unable to read all of file: " + this + ":" + len + " exc: " + exc);
      }
      finally {
         FileUtil.safeClose(is);
      }
      throw new IllegalArgumentException("Invalid read return from input stream");
   }

   public long getFileSize() {
      return new File(absFileName).length();
   }

   public boolean isZip() {
      return false;
   }

   public boolean isLayerFile() {
      return relFileName != null && layer != null && relFileName.equals(layer.layerBaseName);
   }

   public String getJarUrl() {
      return "file://" + absFileName;
   }

   public SrcEntry clone() {
      try {
         return (SrcEntry) super.clone();
      }
      catch (CloneNotSupportedException exc) {}
      return null;
   }
}
