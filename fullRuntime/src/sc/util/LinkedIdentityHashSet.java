/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;

import java.util.*;

public class LinkedIdentityHashSet<E>
    extends AbstractSet<E>
    implements Set<E>, Cloneable, java.io.Serializable {

    private transient LinkedHashMap<IdentityWrapper,E> map;

    public LinkedIdentityHashSet() {
        map = new LinkedHashMap<IdentityWrapper, E>();
    }

    public LinkedIdentityHashSet(Collection<? extends E> c) {
        map = new LinkedHashMap<IdentityWrapper, E>(Math.max((int) (c.size()/.75f) + 1, 16));
        addAll(c);
    }

    public LinkedIdentityHashSet(int initialCapacity) {
        map = new LinkedHashMap<IdentityWrapper, E>(initialCapacity);
    }

    public Iterator<E> iterator() {
        return map.values().iterator();
    }

    public int size() {
        return map.size();
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    public boolean contains(Object o) {
        return map.containsKey(new IdentityWrapper(o));
    }

    public boolean add(E e) {
        return map.put(new IdentityWrapper(e), e)==null;
    }

    public boolean remove(Object o) {
        return map.remove(new IdentityWrapper(o)) != null;
    }

    public void clear() {
        map.clear();
    }

    public Object clone() {
        try {
            LinkedIdentityHashSet<E> newSet = (LinkedIdentityHashSet<E>) super.clone();
            newSet.map = (LinkedHashMap<IdentityWrapper, E>) map.clone();
            return newSet;
        } catch (CloneNotSupportedException e) {
            throw new InternalError();
        }
    }
}
