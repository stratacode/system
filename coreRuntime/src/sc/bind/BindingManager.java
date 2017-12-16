/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import sc.js.JSSettings;
import sc.type.IBeanMapper;

@JSSettings(jsLibFiles = "js/scbind.js", prefixAlias="sc_")
public class BindingManager {
   /**
    * This is called once the data binding system has detected a change for a given listener.
    * It might choose to deliver this event immediately by calling dispatchEvent or
    * This default implementation will dispatch the event immediately unless the sync type for
    * the listener is queued.  In that case,
    */
   public void sendEvent(IListener listener, int event, Object obj, IBeanMapper prop, Object eventDetail) {
      BindingContext ctx = null;

      if ((event & IListener.VALUE_CHANGED_MASK) != 0) {
         // For queued events, we don't want to go through the work of queuing if the value has not changed.
         // without requiring synchronization from the remote scope.   If that turns out not to be the
         if (listener.getSync() != IListener.SyncType.IMMEDIATE) {
            // If the value has not changed, don't apply the change
            if (listener.valueInvalidated(obj, prop, eventDetail, false)) {
               ctx = BindingContext.getBindingContext();  // TODO... get this first and only call valueChanged if it is not null
            }
            else // no change: ignore
               return;
         }
      }

      if (ctx != null && ctx.getDefaultSyncType() != IListener.SyncType.IMMEDIATE)
         ctx.queueEvent(event, obj, prop, listener, eventDetail);
      else
         Bind.dispatchEvent(event, obj, prop, listener, eventDetail);
   }

   public IListener.SyncType getDefaultSyncType() {
      return IListener.SyncType.IMMEDIATE;
   }
}
