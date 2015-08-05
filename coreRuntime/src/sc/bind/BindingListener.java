/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

public class BindingListener {
   public IListener listener;
   public int eventMask;
   public BindingListener next;
   public int priority;

   public String toString() {
      return listener.toString();
   }
}

   
