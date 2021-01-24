/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;

import java.util.*;

public class IdentityHashSet<E>
    extends AbstractSet<E>
    implements Set<E>, Cloneable, java.io.Serializable {

    private transient IdentityHashMap<E,Object> map;

    private static final Object PRESENT = new Object();

    public IdentityHashSet() {
        map = new IdentityHashMap<E,Object>();
    }

    public IdentityHashSet(Collection<? extends E> c) {
        map = new IdentityHashMap<E,Object>(Math.max((int) (c.size()/.75f) + 1, 16));
        addAll(c);
    }

    public IdentityHashSet(int initialCapacity) {
        map = new IdentityHashMap<E,Object>(initialCapacity);
    }

    public Iterator<E> iterator() {
        return map.keySet().iterator();
    }

    public int size() {
        return map.size();
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    public boolean contains(Object o) {
        return map.containsKey(o);
    }

    public boolean add(E e) {
        return map.put(e, PRESENT)==null;
    }

    public boolean remove(Object o) {
        return map.remove(o)==PRESENT;
    }

    public void clear() {
        map.clear();
    }

    public Object clone() {
        try {
            IdentityHashSet<E> newSet = (IdentityHashSet<E>) super.clone();
            newSet.map = (IdentityHashMap<E, Object>) map.clone();
            return newSet;
        } catch (CloneNotSupportedException e) {
            throw new InternalError();
        }
    }

}
