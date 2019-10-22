/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import sc.dyn.DynUtil;
import sc.js.JSSettings;
import sc.obj.CurrentScopeContext;
import sc.obj.IScopeEventListener;
import sc.type.IBeanMapper;
import sc.type.PTypeUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * The BindingContext maintains event queues and dispatches events.  It could be stored in thread local
 * or not depending upon the binding manager.
 * <p>
 * Event dispatching can be immediate, or queued into a BindingContext.  With immediate dispatching, the listeners are
 * executed in the order in which they are added with no BindingContext necessary.  The listener controls the behavior through its
 * sync property, or the code to send an event in BindingManager.
 * <p>
 * For UI toolkits that are single-threaded, you can use one BindingContext to serialize all event streams that communicate with another one by
 * setting up a queued BindingContext in the sender.   On the first change, you schedule a "doLater" operation to send the batch of changes
 * to the other side.  At any point, you can flush the queue and sync with the remote side to deal with any data dependencies.
 * <p>
 * In multi-threaded servers, you typically have the binding context stored associated with some scope and make sure all request processing is
 * synchronized against that scope.   That way, even different threads sharing the same scope stay in sync with respect to binding and you can
 * use data binding across processes or to share data between different scopes.  The queued bindings act like a 'transaction log' for those
 * parts of your application which are just doing 'data exchange'.
 * <p>
 * TODO: performance - it would be nice to add a way to reduce use of thread-context and instead inject method calls into runtime methods
 * or even build a VM which provides support for 'transactional memory' - i.e. a way of managing state which incorporates flexible scopes, locking
 * and visibility of data using data-binding and data-sync between threads, processes, users, and other ways state is shared and synchronized.
 */
@JSSettings(jsLibFiles = "js/scbind.js", prefixAlias="sc_")
public class BindingContext implements IScopeEventListener {
   BindingEvent queuedEvents;

   public BindingContext(IListener.SyncType defaultSyncType) {
      this.defaultSyncType = defaultSyncType;
   }

   public static void setBindingContext(BindingContext ctx) {
      PTypeUtil.setThreadLocal("bindingContext", ctx);
   }

   public int getQueueSize() {
      synchronized (this) {
         BindingEvent e = queuedEvents;
         int ct = 0;
         while (e != null) {
            ct++;
            e = e.next;
         }
         return ct;
      }
   }

   public static BindingContext getBindingContext() {
      return (BindingContext) PTypeUtil.getThreadLocal("bindingContext");
   }

   // Begin queueing either all events, or queue only the validate events. Send the invalidate events immediately.
   public static BindingContext queueEvents(boolean onlyQueueValidate) {
      BindingContext ctx = new BindingContext(onlyQueueValidate ? IListener.SyncType.QUEUE_VALIDATE_EVENTS : IListener.SyncType.QUEUED);
      BindingContext oldBindCtx = BindingContext.getBindingContext();
      BindingContext.setBindingContext(ctx);
      return oldBindCtx;
   }

   public static void flushQueue(BindingContext oldBindCtx) {
      BindingContext ctx = BindingContext.getBindingContext();
      BindingContext.setBindingContext(oldBindCtx);
      ctx.dispatchEvents(null);
   }

   private IListener.SyncType defaultSyncType;
   // TODO: rename this as "currentSyncType" or contextSyncType?  There's already a defaultSyncType at the BindingManager level and it seems like we don't use these two consistently all of the time.
   public IListener.SyncType getDefaultSyncType() {
      return defaultSyncType;
   }
   public void setDefaultSyncType(IListener.SyncType t) {
      defaultSyncType = t;
   }

   /*
   public static IListener.SyncType getCurrentDefaultSyncType() {
      BindingContext ctx = getBindingContext();
      // TODO: This should be set to IMMEDIATE by default so we can avoid the extra queuing step
      // at least for frameworks that don't need the queued behavior.  One option: BindSettings on the
      // base class.  Any bindings src'd on that class will pick up the default listener.  Add a new
      // option or flags arg to the bind call and propagate that through to make it easy to add new
      // flags.
      if (ctx == null)
         return IListener.SyncType.IMMEDIATE;
      else
         return ctx.getDefaultSyncType();
   }
   */

   public void queueEvent(int eventFlag, Object obj, IBeanMapper prop, IListener listener, Object eventDetail, CurrentScopeContext origCtx) {
      BindingEvent newEvent = new BindingEvent(eventFlag, obj, prop, listener, eventDetail, origCtx);
      BindingEvent oldEvent;
      BindingEvent prevEvent = null;

      synchronized (this) {
         for (oldEvent = queuedEvents; oldEvent != null; oldEvent = oldEvent.next) {
            double newPriority = newEvent.listener.getPriority();
            double oldPriority = oldEvent.listener.getPriority();
            // Higher priority numbers are at the top of the list - no need to check for dependencies in this case.   For the same priority, keep the order the same as when the events occurred.
            // It's particularly important that we deliver the invalidate event before the validate event.
            if (newPriority > oldPriority) {
               if (prevEvent == null) {
                  newEvent.next = queuedEvents;
                  queuedEvents = newEvent;
               }
               else {
                  newEvent.next = oldEvent;
                  prevEvent.next = newEvent;
               }
               break;
            }
            else if (newPriority == oldPriority) {
               if (newEvent.sameEvent(oldEvent)) {
                  if (Bind.trace)
                     Bind.logMessage("Ignoring duplicate event: ", obj, prop, eventDetail);
                  return;
               }
            }
            prevEvent = oldEvent;
         }
         if (Bind.trace)
            Bind.logMessage("Queuing event: ", obj, prop, eventDetail);

         if (oldEvent == null) {
            if (prevEvent == null) {
               newEvent.next = queuedEvents;
               queuedEvents = newEvent;
            }
            else {
               prevEvent.next = newEvent;
            }
         }
      }
   }

   float UNSET_PRIORITY = Float.MIN_VALUE;

   /**
    * Called when we want to dispatch some of the pending events  We go through and pull out the events which
    * match the sync flag.  All events of the same priority are batched up in this method and sent to doDispatch
    * where dependencies are resolved.
    *
    * @param sync
    */
   public boolean dispatchEvents(Object sync) {
      BindingEvent oldEvent;
      BindingEvent toExecute = null, lastToExecute = null;
      float priority = UNSET_PRIORITY;
      BindingEvent prevEvent = null, nextEvent;
      List<BindingEvent> runList = null;
      boolean queuedLastEvent = false;
      boolean any = false;

      synchronized (this) {
         /**
          * Go through the list and pull out any events which match this sync flag.
          */
         for (oldEvent = queuedEvents; oldEvent != null; oldEvent = nextEvent) {
            nextEvent = oldEvent.next;

            // If we are executing this event, pull it out of the list and put it on the toExecute list
            if (sync == null || oldEvent.listener.getSync() == null || oldEvent.listener.getSync().equals(sync)) {
               if (priority != oldEvent.listener.getPriority()) {
                  if (toExecute != null) {
                     if (runList == null)
                        runList = new ArrayList<BindingEvent>();
                     runList.add(toExecute);
                  }

                  // Set the new priority and start out with the new next guy
                  priority = oldEvent.listener.getPriority();
                  toExecute = oldEvent;
                  lastToExecute = oldEvent;
               }
               else {
                  // Should only get here the second time once we've run the above
                  assert lastToExecute != null;
                  lastToExecute.next = oldEvent;
                  lastToExecute = oldEvent;
               }

               if (prevEvent == null)
                   queuedEvents = nextEvent;
               else {
                  if (queuedEvents == oldEvent)
                     queuedEvents = nextEvent;

                  // Only if it is still in the queued events list, need to remove it
                  if (!queuedLastEvent)
                     prevEvent.next = nextEvent;
               }
               oldEvent.next = null;
               queuedLastEvent = true;
            }
            else
               queuedLastEvent = false;
            prevEvent = oldEvent;
         }
      }
      // If we have more than one priority, the list stores the lists that need to be run first
      if (runList != null) {
         int sz = runList.size();
         any = true;
         for (int i = 0; i < sz; i++) {
            BindingEvent be = runList.get(i);
            // Dispatch all of the events with the same priority.
            doDispatch(be);
         }
      }
      if (toExecute != null) {
         doDispatch(toExecute);
         any = true;
      }
      return any;
   }

   /**
    * At this point we have the list of events with the same priority.  We need to figure out which to run first
    * by looking for any dependencies between the listeners.
    */
   private void doDispatch(BindingEvent list) {
      BindingEvent outer;
      IListener.SyncType oldSyncType = defaultSyncType;

      // Need this to be in immediate mode to send these events
      defaultSyncType = IListener.SyncType.IMMEDIATE;
      try {

         /* Make a pass through all pairs and compute the dependencies */
         /*
         for (outer = list; outer != null; outer = outer.next) {
            for (inner = outer.next; inner != null; inner = inner.next) {
               IListener innerListener = inner.listener;
               IListener outerListener = outer.listener;
               // Not sure we should be queueing the same listener more than once but if so, it should not
               // be a dependency.
               if (innerListener != outerListener) {
                  ISet readsOI = outerListener.getReads();
                  ISet readsIO = innerListener.getReads();
                  if (readsOI != null && readsIO != null) {
                     boolean depOI = readsOI.containsAny(innerListener.getWrites());
                     boolean depIO = readsIO.containsAny(outerListener.getWrites());
                     if (depOI) {
                        if (!depIO) {
                           outer.addDependency(inner);
                        }
                     }
                     else if (depIO)
                        inner.addDependency(outer);
                  }
               }
            }
         }
         */

         for (outer = list; outer != null; outer = outer.next) {
            dispatchDependencies(outer);
         }
      }
      finally {
         defaultSyncType = oldSyncType;
      }
   }

   final static List<BindingEvent> VISITED_MARKER = new ArrayList<BindingEvent>(0);

   private void dispatchDependencies(BindingEvent event) {
      List<BindingEvent> deps = event.dependencies;
      if ((event.flags & BindingEvent.DISPATCHED) == 0) {
         event.flags |= BindingEvent.DISPATCHED;

         if (deps != null) {
            for (int i = 0; i < deps.size(); i++)
               dispatchDependencies(deps.get(i));
         }

         event.dispatch();
      }
   }

   /**
    * Called when this BindingContext is used in the CurrentScopeContext as an eventListener, to dispatch the events then run any doLaters.
    */
   public void startContext() {
      dispatchEvents(null);
   }

   public boolean queueEnabledForEvent(int event) {
      if (event == IListener.VALUE_CHANGED)
         System.out.println("*** Warning - should be called with validate or invalidate only - not both flags set");
      switch (defaultSyncType) {
         case QUEUED:
            return true;
         case QUEUE_VALIDATE_EVENTS:
            return event == IListener.VALUE_VALIDATED;
         case IMMEDIATE:
            return false;
      }
      return false;
   }
}
