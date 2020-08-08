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

   public static void addPage(String pageTypeName, String pattern, Object pageType, List<QueryParamProperty> queryParamProps,
                              List<Object> urlParts, List<String> constructorProps) {
      PageInfo pi = new PageInfo();
      pi.pageTypeName = pageTypeName;
      pi.pattern = pattern;
      pi.pageType = pageType;
      pi.queryParamProps = queryParamProps;
      pi.urlParts = urlParts;
      pi.constructorProps = constructorProps;
      pages.put(pi.pageTypeName, pi);
   }

   public String pattern;
   public String pageTypeName;
   public Object pageType;

   // Stores the list of query parameters (if any) for the given page - created with @QueryParam
   List<QueryParamProperty> queryParamProps;
   List<Object> urlParts; // List of String, URLParamProperty, and OptionalParamParameter (which itself has an elements list0
   List<String> constructorProps;

   public String toString() {
      if (pattern == null)
         return "<not initialized>";
      return pattern + (pageType == null ? " <no type>" : "(" + pageTypeName + ")");
   }

}
