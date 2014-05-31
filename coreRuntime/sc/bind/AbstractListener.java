/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import sc.dyn.DynUtil;
import sc.js.JSSettings;
import sc.util.ISet;

@JSSettings(jsLibFiles = "js/scbind.js", prefixAlias="sc_")
public abstract class AbstractListener implements IListener {
   boolean activated = true;  // Set to false on nested bindings deactic
   protected ISet<Object> reads = null;
   public ISet<Object> getReads() {
      return reads;
   }

   protected ISet<Object> writes = null;
   public ISet<Object> getWrites() {
      return writes;
   }

   // A thread-local lookup to determine whether the framework managing this object requires
   // queuing or not.  We do this to avoid thread-local lookups in each sendEvent method under the
   // theory that this will vanish in the overhead of the binding setup.
   IListener.SyncType sync = Bind.bindingManager.getDefaultSyncType();
   public IListener.SyncType getSync() {
      return sync;
   }

   float priority = 0.0f;
   public float getPriority() {
      return priority;
   }

   public void valueRequested(Object obj, Object prop) {
   }

   public boolean valueInvalidated(Object obj, Object prop, Object eventDetail, boolean apply) {
      return true;
   }

   public boolean valueValidated(Object obj, Object prop, Object eventDetail, boolean apply) {
      return true;
   }

   public boolean valueChanged(Object obj, Object prop, Object eventDetail, boolean apply) {
      return valueInvalidated(obj, prop, eventDetail, apply) && valueValidated(obj, prop, eventDetail, apply);
   }

   public boolean arrayElementChanged(Object obj, Object prop, Object dims, boolean apply) {
      return arrayElementInvalidated(obj, prop, dims, apply) && arrayElementValidated(obj, prop, dims, apply);
   }

   public boolean arrayElementValidated(Object obj, Object prop, Object dims, boolean apply) {
      return valueValidated(obj, prop, dims, apply);
   }

   public boolean arrayElementInvalidated(Object obj, Object prop, Object dims, boolean apply) {
      return valueInvalidated(obj, prop, dims, apply);
   }

   /** Remap Java bean style events */
   /*
   public void propertyChange(PropertyChangeEvent evt) {
      Object source = evt.getSource();
      // TODO: If sync == queued, should we queue this here too?
      valueChanged(source, TypeUtil.getPropertyMapping(source.getClass(), evt.getPropertyName()), null, true);
   }
   */

   public void parentBindingChanged() {
   }

   String objectToString(Object obj) {
      if (obj == null) return "null";
      return DynUtil.getInstanceName(obj);
   }

   /**
    * This is called by the parent binding before we apply a reverse binding.
    * It might need to do some conversion of the value before it can set it to the destination (i.e. like
    * a float to int conversion).
    */
   public Object performCast(Object value) {
      return value;
   }

   public void activate(boolean state, Object obj, boolean chained) {
      activated = state;
   }
}
