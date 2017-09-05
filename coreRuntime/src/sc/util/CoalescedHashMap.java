/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;

import java.io.Serializable;

/**
 * This should be a faster implementation of the basic operations for a table whose
 * size is known roughly before populating, elements are never removed, hash functions
 * are relatively good.  No hash-table entries are created for adding a new item.
 * Items are put into a slot based on their hash code.  If that spot is filled, we
 * start at the top of the list and put it in the first entry spot.  So a full list 
 * can have O(N) insert and O(N) lookup.  Resizing is expensive so avoid using this if
 * you do not have a good estimate of the size when you create the object.
 *
 * TODO: adjust these tuning parameters for table size and pad amount, compare to existing implementations for speed and space efficiency
 *
 * TODO: performance suggestion: when inserting a new entry, into a populated slot, swap the entry
 * with the existing one, choosing the one with the shortest probe size (i.e. delta between key % tabSize and the index of the slot).
 * When looking for an entry that's not in the table, if we pass an element whose probe-size is longer than the element we are looking for, we can be sure it's not
 * in the table.
 */
public class CoalescedHashMap<K,T> implements Cloneable, Serializable {
   public Object[] keyTable;
   public Object[] valueTable;
   public int size;

   public CoalescedHashMap(int sz) {
      if (sz == 0)
         sz = 1;
      else
         sz = Math.round(sz * 1.3f);
      init(sz);
   }

   private int getPad() {
      return Math.round(keyTable.length * 0.10f);
   }

   private int hash(K key) {
      int len = keyTable.length;
      int pad = getPad();
      // Reserve some space at the top for collisions
      return Math.abs(key.hashCode()) % (len - pad) + pad;
   }

   public T get(K key) {
      int h = hash(key);
      Object tableKey = keyTable[h];
      if (tableKey == null)
         return null;

      if (tableKey.equals(key))
         return (T) valueTable[h];

      int len = keyTable.length;
      for (int i = 0; i < len; i++) {
         if (keyTable[i] == null)
            return null;
         if (keyTable[i].equals(key))
            return (T) valueTable[i];
      }
      return null;
   }

   public boolean contains(K key) {
      int h = hash(key);
      Object tableKey = keyTable[h];
      if (tableKey == null)
         return false;

      if (tableKey.equals(key))
         return true;

      int len = keyTable.length;
      for (int i = 0; i < len; i++) {
         if (keyTable[i] == null)
            return false;
         if (keyTable[i].equals(key))
            return true;
      }
      return false;
   }

   public T put(K key, T value) {
      int h = hash(key);

      if (keyTable[h] == null) {
         keyTable[h] = key;
         valueTable[h] = value;
         size++;
         return null;
      }
      else if (keyTable[h].equals(key)) {
         Object oldVal = valueTable[h];
         valueTable[h] = value;
         return (T) oldVal;
      }
      else {
         int len = keyTable.length;
         for (int i = 0; i < len; i++) {
            if (keyTable[i] == null) {
               keyTable[i] = key;
               valueTable[i] = value;
               size++;
               return null;
            }
            else if (keyTable[i].equals(key)) {
               Object oldVal = valueTable[i];
               valueTable[i] = value;
               return (T) oldVal;
            }
         }
         size++;
         resize(keyTable.length * 2);
         return put(key, value);
      }
   }

   private void init(int size) {
      keyTable = new Object[size];
      valueTable = new Object[size];
   }

   private void resize(int newLen) {
      Object[] oldKeyTable = keyTable;
      Object[] oldValueTable = valueTable;

      int oldLen = keyTable.length;
      int oldSize = size;

      // Create the new table
      init(newLen);

      // Re-add all of the items to the new table.
      for (int i = 0; i < oldLen; i++) {
         if (oldKeyTable[i] != null)
            put((K) oldKeyTable[i], (T) oldValueTable[i]);
      }
      size = oldSize;
   }

   public void putAll(CoalescedHashMap<? extends K, ? extends T> m) {
      int len = m.keyTable.length;
      for (int i = 0; i < len; i++) {
         Object key = m.keyTable[i];
         if (key != null) {
            Object val = m.valueTable[i];
            put((K) key, (T) val);
         }
      }
   }

}

