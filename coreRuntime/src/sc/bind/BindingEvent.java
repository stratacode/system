/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import sc.js.JSSettings;
import sc.obj.CurrentScopeContext;
import sc.type.IBeanMapper;

import java.util.ArrayList;
import java.util.List;

@JSSettings(jsLibFiles = "js/scbind.js", prefixAlias="sc_")
class BindingEvent {
   final static int DISPATCHED = 1 << 0;

   int eventType;
   int flags; /* DISPATCHED */
   Object obj;
   IBeanMapper prop;
   Object eventDetail; // Details for the event (if any).  For array index changes, this is an int specifing the element that changed
   IListener listener;
   List dependencies;
   BindingEvent next;
   CurrentScopeContext origCtx;

   BindingEvent(int ev, Object o, IBeanMapper p, IListener l, Object detail, CurrentScopeContext octx) {
      eventType = ev;
      obj = o;
      prop = p;
      listener = l;
      eventDetail = detail;
      origCtx = octx;
   }

   void addDependency(BindingEvent to) {
      List deps = dependencies;
      if (deps == null)
         dependencies = deps = new ArrayList(3);
      deps.add(to);
   }

   void dispatch() {
      Bind.dispatchEvent(eventType, obj, prop, listener, eventDetail, origCtx);
   }

   public boolean sameEvent(BindingEvent other) {
      if (obj == other.obj && prop == other.prop && listener == other.listener && eventType == other.eventType)
         return true;
      return false;
   }

   public String toString() {
      return "queued event: " + prop + " -> " + listener + " (" + eventDetail + ")" + " type: " + eventType;
   }

}
