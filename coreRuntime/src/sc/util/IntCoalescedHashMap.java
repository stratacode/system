/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;

import sc.js.JSSettings;

import java.io.Serializable;

/**
 * This is a faster implementation of the basic operations for a table whose
 * size is known roughly before populating, elements are never removed, hash functions
 * are relatively good.  No hash-table entries are created for adding a new item.
 * Items are put into a slot based on their hash code.  If that spot is filled, we
 * start at the top of the list and put it in the first entry spot.  So a full list 
 * can have O(N) insert and O(N) lookup.  Resizing is expensive so avoid that.
 */
@JSSettings(jsModuleFile="js/scgen.js", prefixAlias="sc_")
public class IntCoalescedHashMap implements Cloneable, Serializable {
   Object [] keyTable;
   int [] valueTable;

   public IntCoalescedHashMap(int size) {
      if (size == 0)
         size = 1;
      init(size);
   }

   public int get(Object key)  {
      int len = keyTable.length;
      int h = Math.abs(key.hashCode()) % len;
      Object tableKey = keyTable[h];
      if (tableKey == null)
         return -1;

      if (tableKey.equals(key))
         return valueTable[h];

      for (int i = 0; i < len; i++) {
         if (keyTable[i] == null)
            return -1;
         if (keyTable[i].equals(key))
            return valueTable[i];
      }
      return -1;
   }

   public boolean containsKey(Object key)  {
      int len = keyTable.length;
      int h = Math.abs(key.hashCode()) % len;
      Object tableKey = keyTable[h];
      if (tableKey == null)
         return false;

      if (tableKey.equals(key))
         return true;

      for (int i = 0; i < len; i++) {
         if (keyTable[i] == null)
            return false;
         if (keyTable[i].equals(key))
            return true;
      }
      return false;
   }

   public int put(Object key, int value) {
      int len = keyTable.length;
      int h = Math.abs(key.hashCode()) % len;

      if (keyTable[h] == null)
      {
         keyTable[h] = key;
         valueTable[h] = value;
         return -1;
      }
      else if (keyTable[h].equals(key))
      {
         int oldVal = valueTable[h];
         valueTable[h] = value;
         return oldVal;
      }
      else
      {
         for (int i = 0; i < len; i++)
         {
            if (keyTable[i] == null)
            {
               keyTable[i] = key;
               valueTable[i] = value;
               return -1;
            }
            else if (keyTable[i].equals(key))
            {
               int oldVal = valueTable[i];
               valueTable[i] = value;
               return oldVal;
            }
         }
         resize(keyTable.length * 2);
         return put(key, value);
      }
   }

   private void init(int size) {
      keyTable = new Object[size];
      valueTable = new int[size];
   }

   private void resize(int newLen) {
      Object[] oldKeyTable = keyTable;
      int[] oldValueTable = valueTable;

      int oldLen = keyTable.length;

      // Create the new table
      init(newLen);

      // Re-add all of the items to the new table.
      for (int i = 0; i < oldLen; i++)
      {
         if (oldKeyTable[i] != null)
            put(oldKeyTable[i], oldValueTable[i]);
      }
   }

   public void putAll(IntCoalescedHashMap m) {
      int len = m.keyTable.length;
      for (int i = 0; i < len; i++) {
         Object key = m.keyTable[i];
         if (key != null) {
            int val = m.valueTable[i];
            put(key, val);
         }
      }
   }
}

