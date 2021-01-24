/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of a weak hash map that always uses object identity, not equals/hashCode.
 * Also preserves the insertion order, so when we iterate over lists of instances, we get them
 * in the order in which they were registered.
 *
 * Useful to associate data structures with any user defined class without requiring them
 * to adhere to a contract.
 */
public class WeakIdentityHashMap<K, V> implements Map<K, V> {
   private final ReferenceQueue<K> queue = new ReferenceQueue<K>();

   private Map<IdentityWeakRef, V> map;

   public WeakIdentityHashMap(int initialCapacity) {
      // Originally used concurrent hashmap here because the LayeredSystem properties like objectNameIndex, dynInnerToOuterIndex
      // all can be accessed by multiple threads.  We could synchronize LayeredSystem itself but that's probably more
      // expensive and more code so taking this short cut.
      //map = new ConcurrentHashMap<IdentityWeakRef, V>(initialCapacity);
      // Now we also need ordering so we can retrieve instances in the order in which they were created so just going to synchronize on the map itself
      map = Collections.synchronizedMap(new LinkedHashMap<IdentityWeakRef, V>(initialCapacity));
   }

   public WeakIdentityHashMap() {
      this(16);
   }

   public V get(Object key) {
      cleanup();
      return map.get(new IdentityWeakRef(key));
   }

   public V put(K key, V value) {
      cleanup();
      return map.put(new IdentityWeakRef(key), value);
   }

    public void clear() {
       map.clear();
       cleanup();
    }

    public Set<Map.Entry<K, V>> entrySet() {
       cleanup();
       Set<Map.Entry<K, V>> ret = new LinkedHashSet<Map.Entry<K, V>>();
       for (Map.Entry<IdentityWeakRef, V> ref : map.entrySet()) {
          final K key = ref.getKey().get();
          final V value = ref.getValue();
          Map.Entry<K, V> entry = new Map.Entry<K, V>() {
              public K getKey() {
                 return key;
              }
              public V getValue() {
                 return value;
              }
              public V setValue(V value) {
                 throw new UnsupportedOperationException();
              }
          };
          ret.add(entry);
       }
       return Collections.unmodifiableSet(ret);
    }

    public Set<K> keySet() {
        cleanup();
        Set<K> ret = new LinkedHashSet<K>();
        for (IdentityWeakRef ref:map.keySet()) {
            ret.add(ref.get());
        }
        return Collections.unmodifiableSet(ret);
    }

    public boolean equals(Object o) {
       return map.equals(((WeakIdentityHashMap)o).map);
    }

    public int hashCode() {
       return map.hashCode();
    }
    public boolean isEmpty() {
       return map.isEmpty();
    }

    public boolean containsKey(Object key) {
       return map.containsKey(new IdentityWeakRef(key));
    }

    public boolean containsValue(Object value)  {
       return map.containsValue(value);
    }

    public void putAll(Map<? extends K,? extends V> t) {
       for (Map.Entry<? extends K,? extends V> ent:t.entrySet()) {
          put(ent.getKey(), ent.getValue());
       }
    }

    public V remove(Object key) {
       return map.remove(new IdentityWeakRef(key));
    }

    public int size() {
       return map.size();
    }

    public Collection<V> values() {
       cleanup();
       return map.values();
    }

    private synchronized void cleanup() {
       Object zombie = queue.poll();

       while (zombie != null) {
           IdentityWeakRef victim = (IdentityWeakRef)zombie;
           map.remove(victim);
           zombie = queue.poll();
       }
    }

   class IdentityWeakRef extends WeakReference<K> {
       int hash;
        
        @SuppressWarnings("unchecked")
      IdentityWeakRef(Object obj) {
         super((K)obj, queue);
         hash = System.identityHashCode(obj);
      }

      public int hashCode() {
         return hash;
      }

      public boolean equals(Object o) {
         if (this == o) {
            return true;
         }
         IdentityWeakRef ref = (IdentityWeakRef)o;
         if (this.get() == ref.get()) {
            return true;
         }
         return false;
      }
   }
}
