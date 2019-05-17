package sc.sync;

/** Hook for frameworks to be notified after a sync update has been applied. */
@sc.js.JSSettings(jsModuleFile="js/sync.js", prefixAlias="sc_")
public interface IFrameworkListener {
   /** Called after applySync has completed - a time to possibly call refreshBindings on components that need that support */
   void afterApplySync();
}
