/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

/** Implemented by scope implementations to manage values obtained with this scope's lifecycle (e.g. session, global, request, per-store, per-merchant, etc.) */
@sc.js.JSSettings(jsModuleFile="js/scgen.js", prefixAlias="sc_")
public abstract class ScopeContext {
   ScopeDestroyListener destroyListener;

   public abstract void setValue(String name, Object value);

   public abstract Object getValue(String name);

   public abstract ScopeDefinition getScopeDefinition();

   public abstract String getId();

   public void setDestroyListener(ScopeDestroyListener listener) {
      destroyListener = listener;
   }

   public void scopeDestroyed() {
      if (destroyListener != null)
         destroyListener.scopeDestroyed(this);
   }

   /** Returns true if this scope is active in the current thread state. */
   public boolean isCurrent() {
      return false;
   }
}
