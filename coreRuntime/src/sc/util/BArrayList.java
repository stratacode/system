/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;

import sc.js.JSSettings;

import java.util.Collection;

/** 
  This acts like a java.util.ArrayList but is usable with data binding and data synchronization.
  It sends change events as you modify it.
  <p>
  Because it is synchronizable as a property and sends change events we disable the auto-sync stuff for it.  
  */
@sc.obj.Sync(syncMode=sc.obj.SyncMode.Disabled)
@JSSettings(jsModuleFile="js/scutil.js", prefixAlias="sc_")
public class BArrayList<E> extends java.util.ArrayList<E> implements sc.bind.IChangeable {

    public BArrayList(int initialCapacity) {
        super(initialCapacity);
    }

    public BArrayList() {
        super(10);
    }

    public BArrayList(Collection<? extends E> c) {
        super(c);
    }

   public boolean add(E value) {
      boolean res = super.add(value);
      sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
      return res;
   }
 
   public boolean remove(Object o) {
      boolean res = super.remove(o);
      sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
      return res;
   }
 
   public boolean addAll(Collection<? extends E> c) {
      boolean res = super.addAll(c);
      sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
      return res;
  }

  public boolean addAll(int index, Collection<? extends E> c) {
      boolean res = super.addAll(index, c);
      sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
      return res;
  }

  public boolean removeAll(Collection<?> c) {
      boolean res = super.removeAll(c);
      sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
      return res;
  }

  public boolean retainAll(Collection<?> c) {
      boolean res = super.retainAll(c);
      sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
      return res;
  }

  public void clear() {
     int sz = size();
     super.clear();
     if (sz != 0)
        sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
  }
      
  public E set(int index, E element) {
     E res = super.set(index, element);
     if (res != element && (res == null || !res.equals(element)))
        sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
     return res;
  }

  public void add(int index, E element) {
     super.add(index, element);
     sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
  }

  public E remove(int index) {
     E res = super.remove(index);
     sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
     return res;
  }

  /** Just like get but returns null if the element is out of range */
  @sc.bind.BindSettings(reverseMethod="set", reverseSlot=1)
  public E ret(int index) {
     if (index >= size() || index < 0)
        return null;
     return super.get(index);
  }

}
