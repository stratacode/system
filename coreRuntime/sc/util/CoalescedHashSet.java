/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;

import java.io.Serializable;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * This is a faster implementation of the basic operations for a table whose
 * size is known roughly before populating, elements are never removed, hash functions
 * are relatively good.  No hash-table entries are created for adding a new item.
 * Items are put into a slot based on their hash code.  If that spot is filled, we
 * start at the top of the list and put it in the first entry spot.  So a full list 
 * can have O(N) insert and O(N) lookup.  Resizing is expensive so avoid that.
 */
public class CoalescedHashSet<T> implements Cloneable, Serializable, ISet<T> {
   T[] valueTable;
   int size;

   public CoalescedHashSet(T[] elements) {
      int sz = elements.length;
      int len = sz;
      sz += sz; 
      init(sz);
      for (int i = 0; i < len; i++)
         add(elements[i]);
   }

   public CoalescedHashSet(int sz) {
      init(sz);
   }

   public boolean contains(T obj) {
      if (obj == null)
         return false;

      int len = valueTable.length;
      int h = hash(obj);
      Object tableKey = valueTable[h];
      if (tableKey == null)
         return false;

      if (tableKey.equals(obj))
         return true;

      for (int i = 0; i < len; i++) {
         if (valueTable[i] == null)
            return false;
         if (valueTable[i].equals(obj))
            return true;
      }
      return false;
   }

   public int size() {
      return size;
   }

   public boolean add(T obj) {
      int len = valueTable.length;
      int h = hash(obj);

      if (valueTable[h] == null) {
         valueTable[h] = obj;
         size++;
         return true;
      }
      else if (valueTable[h].equals(obj)) {
         return false;
      }
      else {
         for (int i = 0; i < len; i++) {
            if (valueTable[i] == null) {
               valueTable[i] = obj;
               size++;
               return true;
            }
            else if (valueTable[i].equals(obj)) {
               return false;
            }
         }
         resize(valueTable.length * 2);
         return add(obj);
      }
   }

   private void init(int size) {
      valueTable = (T[]) new Object[size];
   }

   private void resize(int newLen) {
      T[] oldValueTable = valueTable;

      int oldLen = valueTable.length;

      // Create the new table
      init(newLen);

      // Re-add all of the items to the new table.
      for (int i = 0; i < oldLen; i++) {
         if (oldValueTable[i] != null)
            add(oldValueTable[i]);
      }
   }


   public boolean containsAny(ISet<T> other) {
      for (int i = 0; i < valueTable.length; i++) {
         T v = valueTable[i];
         if (v != null && other.contains(v))
            return true;
      }
      return false;
   }

   private int hash(T val) {
      if (val == null)
         return 0;
      int len = valueTable.length;
      // Reserve some space at the top for collisions
      return Math.abs(val.hashCode()) % (len);
   }

   class ISetIterator implements Iterator<T> {
      int ix = 0;

      public boolean hasNext() {
         while (ix < valueTable.length) {
            if (valueTable[ix] != null)
               return true;
         }
         return false;  //To change body of implemented methods use File | Settings | File Templates.
      }

      public T next() {
         while (ix < valueTable.length) {
            if (valueTable[ix] != null) {
               T res = valueTable[ix];
               ix++;
               return res;
            }
         }
         throw new NoSuchElementException();
      }

      public void remove() {
         throw new UnsupportedOperationException();
      }
   }

   public Iterator<T> iterator() {
      return new ISetIterator();
   }

   public void addAll(ISet<T> s) {
      Iterator<T> it = s.iterator();
      while (it.hasNext())
         add(it.next());
   }
}

