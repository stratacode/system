package sc.db;

import java.util.ArrayList;
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

   /** For associations, if we know the id but don't know the type yet, this list parallels the underlying list
    * storing the refId */
   ArrayList<Object> refIds = null;

   public DBList() {
      super();
   }

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
      TxListUpdate<E> listUpdate = dbObject == null ? null : dbObject.getListUpdate(this, true, false);
      boolean res;
      if (listUpdate != null)
         res = listUpdate.newList.add(value);
      else
         res = super.add(value);
      sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
      return res;
   }

   public boolean remove(Object o) {
      TxListUpdate<E> listUpdate = dbObject == null ? null : dbObject.getListUpdate(this, true, false);
      boolean res;
      if (listUpdate != null)
         res = listUpdate.newList.remove(o);
      else {
         if (refIds != null) {
            int ix = indexOf(o);
            if (ix == -1)
               return false;
            super.remove(ix);
            refIds.remove(ix);
            res = true;
         }
         else
            res = super.remove(o);
      }
      if (res) {
         sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
         refIds = null;
      }
      return res;
   }

   public boolean addAll(Collection<? extends E> c) {
      TxListUpdate<E> listUpdate = dbObject == null ? null : dbObject.getListUpdate(this, true, false);
      boolean res;
      if (listUpdate != null)
         res = listUpdate.newList.addAll(c);
      else
         res = super.addAll(c);
      sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
      return res;
   }

   public boolean addAll(int index, Collection<? extends E> c) {
      TxListUpdate<E> listUpdate = dbObject == null ? null : dbObject.getListUpdate(this, true, false);
      boolean res;
      if (listUpdate != null)
         res = listUpdate.newList.addAll(index, c);
      else
         res = super.addAll(index, c);
      sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
      return res;
   }

   public boolean removeAll(Collection<?> c) {
      TxListUpdate<E> listUpdate = dbObject == null ? null : dbObject.getListUpdate(this, true, false);
      boolean res;
      if (listUpdate != null)
         res = listUpdate.newList.removeAll(c);
      else
         res = super.removeAll(c);
      sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
      refIds = null;
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
      refIds = null;
      return res;
   }

   public void clear() {
      int sz = size();
      if (sz != 0) {
         // Creating the change with an empty list so no need to clear anything
         TxListUpdate<E> listUpdate = dbObject == null ? null : dbObject.getListUpdate(this, true, true);
         sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
      }
      refIds = null;
   }

   public E set(int index, E element) {
      TxListUpdate<E> listUpdate = dbObject == null ? null : dbObject.getListUpdate(this, true, false);
      E res;
      if (listUpdate != null)
         res = (E) listUpdate.newList.set(index, element);
      else
         res = (E) set(index, element);
      if (res != element && (res == null || !res.equals(element))) {
         sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
         if (refIds != null)
            refIds.set(index, null);
      }
      return res;
   }

   public void add(int index, E element) {
      TxListUpdate<E> listUpdate = dbObject == null ? null : dbObject.getListUpdate(this, true, false);
      if (listUpdate != null)
         listUpdate.newList.add(index, element);
      else {
         super.add(index, element);
         if (refIds != null) {
            while (index > refIds.size())
               refIds.add(null);
            refIds.add(index, null);
         }
      }
      sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
   }

   public E remove(int index) {
      TxListUpdate<E> listUpdate = dbObject == null ? null : dbObject.getListUpdate(this, true, false);
      E res;
      if (listUpdate != null)
         res = (E) listUpdate.newList.remove(index);
      else {
         res = (E) super.remove(index);
         if (refIds != null && index < refIds.size())
            refIds.remove(index);
      }
      sc.bind.Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, null);
      return res;
   }

   /** Just like get but returns null if the element is out of range */
   @sc.bind.BindSettings(reverseMethod="set", reverseSlot=1)
   public E ret(int index) {
      TxListUpdate<E> listUpdate = dbObject == null ? null : dbObject.getListUpdate(this, false, false);
      if (listUpdate != null) {
         if (index >= listUpdate.newList.size() || index < 0)
            return null;
         return (E) listUpdate.newList.get(index);
      }
      if (index >= size() || index < 0)
         return null;
      return getAndCheckRefId(index);
   }

   private E getAndCheckRefId(int index) {
      E res = super.get(index);
      if (res == null && refIds != null) {
         Object refId = refIds.get(index);
         if (refId != null) {
            res = (E) listProp.refDBTypeDesc.findById(refId);
            if (res != null)
               super.set(index, res);
         }
      }
      return res;
   }

   public int size() {
      TxListUpdate<E> listUpdate = dbObject == null ? null : dbObject.getListUpdate(this, false, false);
      if (listUpdate != null)
         return listUpdate.newList.size();
      return super.size();
   }

   public E get(int index) {
      TxListUpdate<E> listUpdate = dbObject == null ? null : dbObject.getListUpdate(this, false, false);
      if (listUpdate != null)
         return (E) listUpdate.newList.get(index);
      return getAndCheckRefId(index);
   }

   public boolean isEmpty() {
      TxListUpdate<E> listUpdate = dbObject == null ? null : dbObject.getListUpdate(this, false, false);
      if (listUpdate != null)
         return listUpdate.newList.isEmpty();
      return super.isEmpty();
   }

   public int indexOf(Object o) {
      TxListUpdate<E> listUpdate = dbObject == null ? null : dbObject.getListUpdate(this, false, false);
      if (listUpdate != null)
         return listUpdate.newList.indexOf(o);
      return super.indexOf(o);
   }

   public int lastIndexOf(Object o) {
      TxListUpdate<E> listUpdate = dbObject == null ? null : dbObject.getListUpdate(this, false, false);
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

   public void setRefId(int ix, Object refId) {
      if (refIds == null) {
         refIds = new ArrayList<Object>(size());
      }
      while (refIds.size() < ix)
         refIds.add(null);
      if (ix == refIds.size())
         refIds.add(refId);
      else
         refIds.set(ix, refId);
   }

   public Object[] toArray() {
      if (refIds != null) {
         for (int i = 0; i < refIds.size(); i++) {
            Object refId = refIds.get(i);
            if (refId != null) {
               Object cur = getAndCheckRefId(i);
               // ignoring cur here - need to resolve this reference and call super.set(.) on the value so it gets
               // returned below...
            }
         }
      }
      return super.toArray();
   }
   // TODO: add subList, iterator, sort, and more - at least the methods that make sense on a modified list
}
