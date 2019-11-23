package sc.lang.html;

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
   void setPageDispatcher(IPageDispatcher dispatcher);
   IPageDispatcher getPageDispatcher();

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
}
