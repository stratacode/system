/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;

import java.io.File;
import java.io.FilenameFilter;
import java.util.regex.Pattern;

public class PatternFilenameFilter implements FilenameFilter {
   Pattern pattern;

   public PatternFilenameFilter(Pattern pattern) {
      this.pattern = pattern;
   }

   public boolean accept(File file, String s) {
      return pattern.matcher(s).matches();
   }
}
