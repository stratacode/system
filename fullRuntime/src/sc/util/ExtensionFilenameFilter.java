/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Collection;
import java.util.Collections;

public class ExtensionFilenameFilter implements FilenameFilter
{
   private Collection<String> exts;
   private boolean includeDirectories = false;
   public ExtensionFilenameFilter(String s, boolean id)
   {
      exts = Collections.singletonList(s);
      includeDirectories = id;
   }
   public ExtensionFilenameFilter(Collection<String> s, boolean id)
   {
      exts = s;
      includeDirectories = id;
   }

   public boolean accept(File dir, String name)
   {
      for (String ext:exts)
         if (name.endsWith(ext))
            return true;

      if (includeDirectories && new File(dir, name).isDirectory())
         return true;
      return false;
   }
}
