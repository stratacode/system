/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ZipSrcEntry extends SrcEntry {
   ZipFile zipFile;
   ZipEntry zipEntry;
   String zipFileName;

   public ZipSrcEntry(Layer layer, String absPathName, String relFile, boolean prepend, String zipFileName, ZipFile zipFile, ZipEntry zipEntry) {
      super(layer, absPathName, relFile, prepend);
      this.zipFile = zipFile;
      this.zipEntry = zipEntry;
      this.zipFileName = zipFileName;
   }

   public boolean canRead() {
      return new File(zipFileName).canRead();
   }

   public long getLastModified() {
      return new File(zipFileName).lastModified();
   }

   public long getFileSize() {
      return zipEntry.getSize();
   }

   public InputStream getInputStream() {
      try {
         return zipFile.getInputStream(zipEntry);
      }
      catch (IOException exc) {
         return null;
      }
   }

   public boolean isZip() {
      return true;
   }

   public String getJarUrl() {
      return "jar://" + zipFileName + "!/" + zipEntry.getName();
   }
}
