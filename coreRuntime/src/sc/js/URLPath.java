/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.js;

import sc.obj.Constant;

/**
 * A simple model object used to represent the information required to render a link for a URL
 * for the URLPaths property in a tag object.
 */
@JSSettings(jsLibFiles="js/tags.js", prefixAlias="sc_")
public class URLPath {
   @Constant
   public String url;
   @Constant
   public String name;
   public URLPath(String typeName) {
      url = typeName + ".html";
      name = typeName;
   }

   public int hashCode() {
      return url.hashCode();
   }

   public boolean equals(Object other) {
      if (!(other instanceof URLPath))
         return false;
      URLPath op = (URLPath) other;

      return url.equals(op.url) && name.equals(op.name);
   }
}
