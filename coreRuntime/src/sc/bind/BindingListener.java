/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

public class BindingListener {
   public IListener listener;
   public int eventMask;
   public BindingListener next;
   public int priority;
   public int flags;

   public BindingListener(int eventMask, IListener listener, int priority) {
      this.eventMask = eventMask;
      this.listener = listener;
      this.priority = priority;
      this.flags |= (listener.getVerbose() ? Bind.VERBOSE : 0) | (listener.getTrace() ? Bind.TRACE : 0);
   }

   public String toString() {
      return listener.toString();
   }
}

   
