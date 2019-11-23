package sc.lang.html;

public abstract class BasePage implements IPage {
   private IPageDispatcher pageDispatcher;
   private int pageVisitCount = 0;
   private boolean cacheEnabled = false;
   private CacheMode cache;

   public void setPageDispatcher(IPageDispatcher dispatcher) {
      this.pageDispatcher = dispatcher;
   }

   public IPageDispatcher getPageDispatcher() {
      return pageDispatcher;
   }

   @Override
   public void setPageVisitCount(int vc) {
      pageVisitCount = vc;
   }

   @Override
   public int getPageVisitCount() {
      return pageVisitCount;
   }

   public boolean getCacheEnabled() {
      return cacheEnabled;
   }

   public void setCacheEnabled(boolean ce) {
      this.cacheEnabled = ce;
   }

   public boolean getPageCached() {
      return getCacheEnabled() && pageVisitCount > 0;
   }

   public void setCache(CacheMode m) {
      cache = m;
   }

   public CacheMode getCache() {
      return cache;
   }

   public abstract StringBuilder output(OutputCtx ctx);
}
