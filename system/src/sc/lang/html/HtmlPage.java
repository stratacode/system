/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.obj.CompilerSettings;
import sc.obj.Sync;
import sc.obj.SyncMode;

import java.util.HashMap;

/**
 * We are setting the dependentTypes here because we always have an HtmlPage loaded in each client.  We might need it on other types as well cause it's
 * only processed on the first 'extends' class from Java source we are processing.
 */
@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js", dependentTypes="sc.obj.IChildInit")
/** Used as the tag class for the html tag it is the top level tag in the page. */
// TODO: on the client, this uses the js_Page_c constructor which we can't easily replicate in Java.  But maybe it should extend Page
// and have it just set tagName to 'html'?
@Sync(syncMode= SyncMode.Default) // Turn back on sync mode for user defined page types so that any fields they defined will be synchronized by default. - TODO call this SyncMode.Unset?
@CompilerSettings(liveDynamicTypes=true) // An important component - nice to track instances for the command line editor
public class HtmlPage extends Html {
   protected boolean isPageElement() {
      return true;
   }

   public HtmlPage() {
   }
   public HtmlPage(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
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
      if (pageURL == null) {
         Window w = Window.getWindow();
         if (w != null)
         pageURL = w.location.href;
      }
      return pageURL;
   }

   public String getPageBaseURL() {
      String pgurl = getPageURL();
      if (pgurl == null)
         return null;
      int ix = pgurl.indexOf("?");
      if (ix == -1)
         return pgurl;
      return pgurl.substring(0, ix);
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
