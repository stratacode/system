/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.obj.Constant;
import sc.type.CTypeUtil;
import sc.util.FileUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Represents a src file being processed by the layered system.
 * Stores the layer containing the file and the various path components. 
 */
public class SrcEntry {
   public Layer layer;
   @Constant // eliminate binding warnings
   public String absFileName;  /* The absolute file name of the src entry */
   public String relFileName;  /* The file name relative to the layer's root directory */
   public String baseFileName; /* The name of the file within its directory */
   public boolean prependPackage = true; /* Does this type prepend its layer's package name to the type name */

   public transient byte[] hash = null;

   public SrcEntry() {
   }

   public SrcEntry(Layer lyr, String absDir, String relDir, String baseName) {
      this(lyr, absDir, relDir, baseName, true);
   }

   public SrcEntry(Layer lyr, String absDir, String relDir, String baseName, boolean prepend) {
      layer = lyr;
      absFileName = FileUtil.concat(absDir, baseName);
      relFileName = relDir.length() == 0 ? baseName : FileUtil.concat(relDir,baseName);
      baseFileName = baseName;
      prependPackage = prepend;
   }

   public SrcEntry(Layer lyr, String absFile, String relFile) {
      this(lyr, absFile, relFile, true);
   }

   public SrcEntry(Layer lyr, String absFile, String relFile, boolean prepend) {
      layer = lyr;
      absFileName = absFile;
      relFileName = relFile;
      baseFileName = FileUtil.getFileName(relFileName);
      prependPackage = prepend;
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
      return relFileName + " layer: " + (layer == null ? "<null>" : layer);
   }

   public String toShortString() {
      return relFileName + " layer: " + (layer == null ? "<null>" : layer.getLayerName());
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

   public boolean isZip() {
      return false;
   }

   public boolean isLayerFile() {
      return relFileName != null && layer != null && relFileName.equals(layer.layerBaseName);
   }
}
