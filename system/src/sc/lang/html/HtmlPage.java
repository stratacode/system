/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.bind.Bind;
import sc.bind.Bindable;
import sc.bind.IListener;
import sc.obj.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * We are setting the dependentTypes here to ensure these classes are compiled into the JS runtime when HtmlPage is used.
 * We always have an HtmlPage loaded in each client so this just a convenient place to put this dependency.
 * Currently we always include this class in each page but might need to add this dependency elsewhere if it gets skipped for
 * a JS runtime that uses some of these classes.
 */
@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js", dependentTypes="sc.obj.IChildInit,sc.js.ServerTagManager,sc.type.CTypeUtil")
@SyncTypeFilter(typeNames={"sc.lang.html.History"})
/** Used as the tag class for the html tag it is the top level tag in the page. */
// TODO: on the client, this uses the js_Page_c constructor which we can't easily replicate in Java.  But maybe it should extend Page
// and have it just set tagName to 'html'?
@Sync(syncMode= SyncMode.Default) // Turn back on sync mode for user defined page types so that any fields they defined will be synchronized by default. - TODO call this SyncMode.Unset?
// Set liveDynamicTypes here since this an important set of types that are nice to track instances for the command line editor
// Set initConstructorPropertyMethod so that @URL constructor properties can be used to override the default value of constructor properties.
@CompilerSettings(liveDynamicTypes=true,initConstructorPropertyMethod="sc.lang.html.PageInfo.getURLProperty")
public class HtmlPage extends Html implements IPage {
   private final static sc.type.IBeanMapper _pageVisitProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.HtmlPage.class, "pageVisitCount");
   private Map<String,Object> pageProperties;

   protected boolean isPageElement() {
      return true;
   }

   public HtmlPage() {
   }
   public HtmlPage(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }

   public IPageDispatcher pageDispatcher;

   @sc.obj.EditorSettings(visible=false)
   public void setPageDispatcher(IPageDispatcher pd) {
      pageDispatcher = pd;
   }
   public IPageDispatcher getPageDispatcher() {
      return pageDispatcher;
   }

   private List<CurrentScopeContext> currentScopeContexts;

   @sc.obj.EditorSettings(visible=false)
   public void setCurrentScopeContexts(List<CurrentScopeContext> ctxs) {
      currentScopeContexts = ctxs;
   }
   public List<CurrentScopeContext> getCurrentScopeContexts() {
      return currentScopeContexts;
   }

   @sc.obj.EditorSettings(visible=false)
   public boolean getCacheEnabled() {
      CacheMode cacheMode = getCache();
      return cacheMode == CacheMode.Enabled || cacheMode == CacheMode.Unset || cacheMode == null;
   }

   public void setCacheEnabled(boolean cacheEnabled) {
      setCache(CacheMode.Enabled);
   }

   @sc.obj.EditorSettings(visible=false)
   public boolean getPageCached() {
      return bodyCache != null && getCacheEnabled();
   }

   private QueryParamProperty[] queryParamProperties;

   /** List of associations between URL query parameters and properties of the page object */
   @sc.obj.EditorSettings(visible=false)
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

   private List<String> pageJSFiles;

   public void setPageJSFiles(List<String> jsFiles) {
      pageJSFiles = jsFiles;
   }
   @sc.obj.EditorSettings(visible=false)
   public List<String> getPageJSFiles() {
      return pageJSFiles;
   }

   /** Incremented before rendering for each page view */
   @Bindable(manual = true)
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

   public void refreshTags(boolean parentBodyChanged) {
      CurrentScopeContext curScopeCtx = CurrentScopeContext.getThreadScopeContext();
      // If the context for the current thread is part of this page, just run the refresh tags now. Otherwise, mark
      // all of the elements as needing refresh and notify them to wake up.
      int ix = -1;
      if (curScopeCtx == null || currentScopeContexts == null || (ix = curScopeCtx.indexInList(currentScopeContexts)) != -1) {
         super.refreshTags(parentBodyChanged);
      }
      if (currentScopeContexts != null) {
         for (int i = 0; i < currentScopeContexts.size(); i++) {
            if (i == ix)
               continue;

            // For any non-current contexts built off this page add a job for another thread to refresh this page there too
            CurrentScopeContext otherCtx = currentScopeContexts.get(i);
            otherCtx.getEventScopeContext().addInvokeLater(new Runnable() {
                  public void run() {
                     refreshTags(false);
                  }
               }, Element.REFRESH_TAG_PRIORITY, otherCtx);
         }
      }
   }

   public Map<String,Object> getPageProperties() {
      return pageProperties;
   }
   public void setPageProperties(Map<String,Object> pp) {
      pageProperties = pp;
   }
}
