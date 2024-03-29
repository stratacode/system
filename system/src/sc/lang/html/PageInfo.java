/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.dyn.DynUtil;

import java.util.HashMap;
import java.util.List;

/**
 * Table storing the type-name to PageInfo table, so a given page object can find it's query param properties.
 * This is right now only used in Javascript but at some point we should
 * probably merge this with the PageDispatcher's pages table.
 */
@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class PageInfo {
   private static HashMap<String,PageInfo> pages = new HashMap<String,PageInfo>();

   public static void addPage(String pageTypeName, String pattern, Object pageType, boolean needsSync, List<QueryParamProperty> queryParamProps,
                              List<Object> urlParts, List<String> constructorProps, String constructorPropSig) {
      PageInfo pi = new PageInfo();
      pi.pageTypeName = pageTypeName;
      pi.pattern = pattern;
      pi.pageType = pageType;
      pi.needsSync = needsSync;
      pi.queryParamProps = queryParamProps;
      pi.urlParts = urlParts;
      pi.constructorProps = constructorProps;
      pi.constructorPropSig = constructorPropSig;
      pages.put(pi.pageTypeName, pi);
   }

   public String pattern;
   public String pageTypeName;
   public Object pageType;
   public boolean needsSync;

   // Stores the list of query parameters (if any) for the given page - created with @QueryParam
   List<QueryParamProperty> queryParamProps;
   List<Object> urlParts; // List of String, URLParamProperty, and OptionalParamParameter (which itself has an elements list0
   List<String> constructorProps;
   String constructorPropSig;
   /** Names of query parameters to propagate onto relative links from the current page. If any of these parameters
    * are set in the page's URL, any dynamic URL changes with window.location.href = "..." or any getRe */
   // TODO: implement this? We already propagate the scopeContextName parameter
   //List<String> propagateQueryParams;

   public String toString() {
      if (pattern == null)
         return "<not initialized>";
      return pattern + (pageType == null ? " <no type>" : "(" + pageTypeName + ")");
   }

   /**
    * This is used on the client side to fetch constructor properties from the URL. The server code in PageDispatcher
    * will retrieve these values and use them to construct the page object skipping the object's getX method
    */
   public static Object getURLProperty(String className, String propName, Object defaultValue) {
      return defaultValue;
   }
}
