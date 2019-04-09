/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.bind.Bind;
import sc.bind.IListener;
import sc.obj.CompilerSettings;
import sc.obj.Sync;
import sc.obj.SyncMode;

import java.util.HashMap;
import java.util.LinkedHashMap;

/**
 * We are setting the dependentTypes here to ensure these classes are compiled into the JS runtime when HtmlPage is used.
 * We always have an HtmlPage loaded in each client so this just a convenient place to put this dependency.
 * Currently we always include this class in each page but might need to add this dependency elsewhere if it gets skipped for
 * a JS runtime that uses some of these classes.
 */
@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js", dependentTypes="sc.obj.IChildInit,sc.js.ServerTagManager,sc.type.CTypeUtil")
/** Used as the tag class for the html tag it is the top level tag in the page. */
// TODO: on the client, this uses the js_Page_c constructor which we can't easily replicate in Java.  But maybe it should extend Page
// and have it just set tagName to 'html'?
@Sync(syncMode= SyncMode.Default) // Turn back on sync mode for user defined page types so that any fields they defined will be synchronized by default. - TODO call this SyncMode.Unset?
@CompilerSettings(liveDynamicTypes=true) // An important component - nice to track instances for the command line editor
public class HtmlPage extends Html {
   private final static sc.type.IBeanMapper _pageVisitProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.HtmlPage.class, "pageVisitCount");

   protected boolean isPageElement() {
      return true;
   }

   public HtmlPage() {
   }
   public HtmlPage(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }

   public IPageDispatcher pageDispatcher;

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
            pageURL = w.location.getHref();
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

   private int pageVisitCount = 0;
   public int getPageVisitCount() {
      return pageVisitCount;
   }

   /** Incremented before rendering for each page view */
   public void setPageVisitCount(int ct) {
      pageVisitCount = ct;
      Bind.sendEvent(IListener.VALUE_CHANGED, this, _pageVisitProp);
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
