/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import sc.js.JSSettings;
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

   BindingEvent(int ev, Object o, IBeanMapper p, IListener l, Object detail) {
      eventType = ev;
      obj = o;
      prop = p;
      listener = l;
      eventDetail = detail;
   }

   void addDependency(BindingEvent to) {
      List deps = dependencies;
      if (deps == null)
         dependencies = deps = new ArrayList(3);
      deps.add(to);
   }

   void dispatch() {
      Bind.dispatchEvent(eventType, obj, prop, listener, eventDetail);
   }

}
