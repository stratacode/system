/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.js;

import sc.obj.Constant;
import sc.type.CTypeUtil;

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
   @Constant
   public String keyName;

   public URLPath(String templatePathName) {
      keyName = templatePathName;
      name = templatePathName;

      // Turn /path/foo.html into path/foo as a display name
      if (name.startsWith("/"))
         name = name.substring(1);
      int lastSlashIx = name.lastIndexOf('/');
      if (lastSlashIx == -1)
         lastSlashIx = 0;
      int dotIx = name.indexOf('.', lastSlashIx);
      if (dotIx != -1)
         name = name.substring(0, dotIx);
      url = templatePathName;
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

   public String cleanURL(boolean expandIndex) {
      String res = url;
      if (res.equals("") && expandIndex)
         res = "index.html";
      if (res.startsWith("/"))
         res = res.substring(1);
      return res;
   }

   /** Cleans the first / and extension out of the URL and handles the default case */
   public static String getAppNameFromURL(String url) {
      String app = url;
      int ix = app.indexOf("/");
      if (ix != -1)
         app = app.substring(ix + 1);
      if (app.length() == 0)
         app = "index";
      ix = app.lastIndexOf(".");
      if (ix != -1)
         app = app.substring(0, ix);
      return app;
   }

   // TODO: this only works for the most rudimentary cases and should do escaping etc.
   public static String addQueryParam(String url, String param, String value) {
      if (url.contains("?"))
         url = url + "&" + param + "=" + value;
      else
         url = url + "?" + param + "=" + value;
      return url;
   }

   public String toString() {
      return name + ":" + url + " (key=" + keyName + ")";
   }
}
