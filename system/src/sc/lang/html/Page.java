/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import java.util.Map;

@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js", dependentJSFiles="js/jvsys.js,js/scbind.js,js/sync.js")
public class Page extends HTMLElement {
   public Page() {
   }
   public Page(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }

   protected boolean isPageElement() {
      return true;
   }

   private QueryParamProperty[] queryParamProperties;

   /** List of associations between URL query parameters and properties of the page object */
   public QueryParamProperty[] getQueryParamProperties() {
      return queryParamProperties;
   }

   private String pageURL;

   public void setPageURL(String pageURL) {
      this.pageURL = pageURL;
   }
   public String getPageURL() {
      return pageURL;
   }

   public String getPageBaseURL() {
      if (pageURL == null)
         return null;
      int ix = pageURL.indexOf("?");
      if (ix == -1)
         return pageURL;
      return pageURL.substring(0, ix);
   }

   // TODO: this only works for the most rudimentary cases and should do escaping etc.
   /*
   public static String query(String url, String param, String value) {
      if (url.contains("?")) {
         // Need to replace this parameter?
         if (url.contains(param)) {
            Map<String,String> queryParams = parseQueryParams(url);
            queryParams.put()

         }
         url = url + "&" + param + "=" + value;
      }
      else
         url = url + "?" + param + "=" + value;
      return url;
   }
   */

}
