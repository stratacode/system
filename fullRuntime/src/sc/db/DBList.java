package sc.db;

import java.util.Collection;
import java.util.List;

/**
 This acts like a java.util.ArrayList but is usable with data binding and persistence
 It does a 'copy on write' to manage a separate per-transaction view of each list that's being
 modified, and allows the underlying list to be updated when the transaction is committed.

 TODO: need to add more ArrayList methods to reflect the current transaction state to support them with lists that have been
 modified by not yet committed.
 */
@sc.obj.Sync(syncMode=sc.obj.SyncMode.Disabled)
public class DBList<E extends IDBObject> extends java.util.ArrayList<E> implements sc.bind.IChangeable {
   DBObject dbObject;
   DBPropertyDescriptor listProp;
   boolean trackingChanges = false;

   public DBList(int initialCapacity, DBObject dbObject, DBPropertyDescriptor listProp) {
      super(initialCapacity);
      this.dbObject = dbObject;
      this.listProp = listProp;
   }

   public DBList(List<E> value, DBObject dbObject, DBPropertyDescriptor listProp) {
      super(value.size());
      this.dbObject = dbObject;
      this.listProp = listProp;
      addAll(value);
   }

   public boolean add(E value) {
      TxListUpdate<E> listUpdate = dbObject.getListUpdate(this, true, false);
      boolean res;
      if (listUpdate != null)
         res = listUpdate.newList.add(value);
      else
         res = super.add(value);
      sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
      return res;
   }

   public boolean remove(Object o) {
      TxListUpdate<E> listUpdate = dbObject.getListUpdate(this, true, false);
      boolean res;
      if (listUpdate != null)
         res = listUpdate.newList.remove(o);
      else
         res = super.remove(o);
      sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
      return res;
   }

   public boolean addAll(Collection<? extends E> c) {
      TxListUpdate<E> listUpdate = dbObject.getListUpdate(this, true, false);
      boolean res;
      if (listUpdate != null)
         res = listUpdate.newList.addAll(c);
      else
         res = super.addAll(c);
      sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
      return res;
   }

   public boolean addAll(int index, Collection<? extends E> c) {
      TxListUpdate<E> listUpdate = dbObject.getListUpdate(this, true, false);
      boolean res;
      if (listUpdate != null)
         res = listUpdate.newList.addAll(index, c);
      else
         res = super.addAll(index, c);
      sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
      return res;
   }

   public boolean removeAll(Collection<?> c) {
      TxListUpdate<E> listUpdate = dbObject.getListUpdate(this, true, false);
      boolean res;
      if (listUpdate != null)
         res = listUpdate.newList.removeAll(c);
      else
         res = super.removeAll(c);
      sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
      return res;
   }

   public boolean retainAll(Collection<?> c) {
      TxListUpdate<E> listUpdate = dbObject.getListUpdate(this, true, false);
      boolean res;
      if (listUpdate != null)
         res = listUpdate.newList.retainAll(c);
      else
         res = super.retainAll(c);
      sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
      return res;
   }

   public void clear() {
      int sz = size();
      if (sz != 0) {
         // Creating the change with an empty list so no need to clear anything
         TxListUpdate<E> listUpdate = dbObject.getListUpdate(this, true, true);
         sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
      }
   }

   public E set(int index, E element) {
      TxListUpdate<E> listUpdate = dbObject.getListUpdate(this, true, false);
      E res;
      if (listUpdate != null)
         res = (E) listUpdate.newList.set(index, element);
      else
         res = (E) set(index, element);
      if (res != element && (res == null || !res.equals(element)))
         sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
      return res;
   }

   public void add(int index, E element) {
      TxListUpdate<E> listUpdate = dbObject.getListUpdate(this, true, false);
      if (listUpdate != null)
         listUpdate.newList.add(index, element);
      else
         super.add(index, element);
      sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
   }

   public E remove(int index) {
      TxListUpdate<E> listUpdate = dbObject.getListUpdate(this, true, false);
      E res;
      if (listUpdate != null)
         res = (E) listUpdate.newList.remove(index);
      else
         res = (E) super.remove(index);
      sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
      return res;
   }

   /** Just like get but returns null if the element is out of range */
   @sc.bind.BindSettings(reverseMethod="set", reverseSlot=1)
   public E ret(int index) {
      TxListUpdate<E> listUpdate = dbObject.getListUpdate(this, false, false);
      if (listUpdate != null) {
         if (index >= listUpdate.newList.size() || index < 0)
            return null;
         return (E) listUpdate.newList.get(index);
      }
      if (index >= size() || index < 0)
         return null;
      return super.get(index);
   }

   public int size() {
      TxListUpdate<E> listUpdate = dbObject.getListUpdate(this, false, false);
      if (listUpdate != null)
         return listUpdate.newList.size();
      return super.size();
   }

   public E get(int index) {
      TxListUpdate<E> listUpdate = dbObject.getListUpdate(this, false, false);
      if (listUpdate != null)
         return (E) listUpdate.newList.get(index);
      return super.get(index);
   }

   public boolean isEmpty() {
      TxListUpdate<E> listUpdate = dbObject.getListUpdate(this, false, false);
      if (listUpdate != null)
         return listUpdate.newList.isEmpty();
      return super.isEmpty();
   }

   public int indexOf(Object o) {
      TxListUpdate<E> listUpdate = dbObject.getListUpdate(this, false, false);
      if (listUpdate != null)
         return listUpdate.newList.indexOf(o);
      return super.indexOf(o);
   }

   public int lastIndexOf(Object o) {
      TxListUpdate<E> listUpdate = dbObject.getListUpdate(this, false, false);
      if (listUpdate != null)
         return listUpdate.newList.lastIndexOf(o);
      return super.lastIndexOf(o);
   }

   public void updateToList(List<E> newList) {
      // TODO: any reason to make this faster or more incremental?
      super.clear();
      super.addAll(newList);
      sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
   }

   // TODO: add subList, iterator, sort, and more - at least the methods that make sense on a modified list
}
