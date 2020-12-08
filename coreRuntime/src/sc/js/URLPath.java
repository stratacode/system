/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.js;

import sc.obj.Constant;

/**
 * Used to represent a link to a URL in the system which is associated with a page type.
 * Generated by the @URL annotation, exposed via the LayeredSystem.buildInfo.getURLPaths() API.
 * This class is supported in JS to represent URLs.
 */
@JSSettings(jsLibFiles="js/tags.js", prefixAlias="sc_")
public class URLPath implements Comparable {
   /** The URL to use for this URLPath. This can be a simple string or a pattern using the PatternLanguage */
   @Constant
   public String url;

   /** Visible display name */
   @Constant
   public String name;

   /** Name to uniquely identify this URL path */
   @Constant
   public String keyName;

   /** Class, CFClass, or TypeDeclaration to represent the type of the page object for this URL */
   @Constant
   public Object pageType;

   /** Is realTime enable or disabled by default for this URL via the URL(realTime) annotation  */
   @Constant
   public boolean realTime;

   /** List of test scripts to run after loading this URL, or {"none"} to disable automated testing of this URL */
   @Constant
   public String[] testScripts;

   public URLPath(String templatePathName, Object pageType) {
      keyName = templatePathName;
      name = templatePathName;
      this.pageType = pageType;
      this.realTime = true;

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

   public URLPath(String url, String name, String keyName, Object pageType, boolean realTime, String[] testScripts) {
      this.url = url;
      this.name = name;
      this.keyName = keyName;
      this.pageType = pageType;
      this.realTime = realTime;
      this.testScripts = testScripts;
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
   /*
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
   */

   // TODO: do we need to encode string values into UTF8 here or maybe it should be done downstream?
   public static String addQueryParam(String url, String param, Object value) {
      String veqStr = getValueEqualsString(value);
      if (veqStr == null)
         return url;
      char sep;
      if (url.contains("?"))
         sep = '&';
      else
         sep = '?';
      return url + sep + param + veqStr;
   }

   // TODO: add more data types? should we do List<Type>
   public static String getValueEqualsString(Object value) {
      if (value instanceof Boolean) {
         Boolean bval = (Boolean) value;
         if (bval)
            return "";
         else
            return null;
      }
      if (value instanceof String || value instanceof Number || value instanceof Character)
         return "=" + value;
      else
         throw new IllegalArgumentException("Unsupported query parameter type: " + value);
   }

   public static String setQueryParam(String url, String param, Object value) {
      int qix = url.indexOf("?");
      if (qix != -1) {
         int nameIx = url.indexOf(param, qix+1);
         if (nameIx == -1)
            return value == null ? url : addQueryParam(url, param, value);

         String newValueEqualsStr = getValueEqualsString(value);
         if (newValueEqualsStr == null)
            return removeQueryParam(url, param);

         // Replace the value
         int nameEnd = nameIx + param.length();
         int valEndIx = findValEndInQueryString(url, nameEnd);
         return url.substring(0, nameEnd) + newValueEqualsStr + url.substring(valEndIx);
      }
      else if (value != null)
         return addQueryParam(url, param, value);
      return url;
   }

   public static String removeQueryParam(String url, String param) {
      int qix = url.indexOf("?");
      if (qix != -1) {
         int nameIx = url.indexOf(param, qix+1);
         if (nameIx == -1)
            return url;
         int nameEnd = nameIx + param.length();
         int valEndIx = findValEndInQueryString(url, nameEnd);
         String start = url.substring(0, nameIx);
         String rem = url.substring(valEndIx);
         if (rem.length() > 0) {
            if (start.endsWith("?") || start.endsWith("&")) {
               if (rem.startsWith("&"))
                  rem = rem.substring(1);
               return start + rem;
            }
            return start + "?" + rem;
         }
         else if (start.endsWith("&") || start.endsWith("?"))
            start = start.substring(0, start.length()-1);
         return start;
      }
      return url;
   }

   private static int findNameInQueryString(String url, int qix, String param) {
      int nameIx = url.indexOf(param, qix+1);
      if (nameIx == -1)
         return -1;
      char before = url.charAt(nameIx-1);
      if (before != '?' && before != '&')
         return -1;
      int plen = param.length();
      int afterIx = nameIx + plen;
      char after = afterIx == url.length() ? '&' : url.charAt(afterIx);
      if (after == '=' || after == '&') {
         return nameIx;
      }
      return -1;
   }

   private static int findValEndInQueryString(String url, int nameEnd) {
      int sepIx = url.indexOf("&", nameEnd);
      if (sepIx == -1) {
         return url.length();
      }
      return sepIx;
   }

   /** Returns Boolean.TRUE for present with no value, otherwise a String */
   public static Object getQueryParam(String url, String param) {
      int qix = url.indexOf("?");
      if (qix != -1) {
         int nameIx = findNameInQueryString(url, qix, param);
         if (nameIx == -1)
            return null;

         int nameEnd = nameIx + param.length();
         int valEndIx = findValEndInQueryString(url, nameEnd);
         if (valEndIx == nameEnd) // Just paramName& so we return a boolean of true for that situation
            return Boolean.TRUE;
         return url.substring(nameEnd+1, valEndIx);
      }
      return null;
   }

   public String toString() {
      return name + ":" + url + " (key=" + keyName + ", realTime=" + realTime + ")";
   }

   public void convertToRelativePath() {
      if (url.startsWith("/") && url.length() > 1)
         url = url.substring(1);
   }

   public int compareTo(Object o) {
      if (!(o instanceof URLPath))
         return -1;
      return name.compareTo(((URLPath) o).name);
   }
}
