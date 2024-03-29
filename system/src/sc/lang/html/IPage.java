/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.obj.CurrentScopeContext;
import sc.obj.ScopeContext;

import java.util.List;
import java.util.Map;

/**
 * This is a lightweight interface used like Servlet implemented by the Element interface but
 * supporting scopes, properties, caching and most features independent of HTTP
 * (but when needed, use Context to get the HttpServletRequest when running in the servlet environment)
 * For those instances when it does not make sense to use tag objects and HtmlPage, extend PageObject
 * and override the output method.
 */
public interface IPage {
   /**
    * This property is set right after the page object is created to provide access to the
    * IPageDispatcher that will be calling the output method later
    */
   @sc.obj.EditorSettings(visible=false)
   void setPageDispatcher(IPageDispatcher dispatcher);
   IPageDispatcher getPageDispatcher();

   @sc.obj.EditorSettings(visible=false)
   void setPageEntry(IPageEntry dispatcher);
   IPageEntry getPageEntry();

   /** This property is incremented right before the request is invoked */
   void setPageVisitCount(int vc);
   int getPageVisitCount();

   /** Is caching enabled on this page */
   boolean getCacheEnabled();
   void setCacheEnabled(boolean ce);

   /** Is caching enabled and the page already cached */
   boolean getPageCached();

   StringBuilder output(OutputCtx ctx);

   CacheMode getCache();
   void setCache(CacheMode mode);

   void setCurrentScopeContexts(List<CurrentScopeContext> ctxs);
   List<CurrentScopeContext> getCurrentScopeContexts();

   Map<String,Object> getPageProperties();
   void setPageProperties(Map<String,Object> pp);
}
