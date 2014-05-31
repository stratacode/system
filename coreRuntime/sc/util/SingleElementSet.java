/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;

import java.io.Serializable;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A lightweight set implementation for just one element.
 */
public class SingleElementSet<T> implements Cloneable, Serializable, ISet<T> {
   T element;

   public SingleElementSet(T elem) {
      element = elem;
   }

   public boolean contains(Object obj)  {
      return element == obj || (element != null && element.equals(obj));
   }

   public boolean containsAny(ISet<T> other) {
      return other.contains(element);
   }

   public Iterator<T> iterator() {
      return new Iterator<T>() {
         boolean advanced = false;

         public boolean hasNext() {
            return !advanced;
         }

         public T next() {
            if (advanced)
               throw new NoSuchElementException();
            advanced = true;
            return element;
         }

         public void remove() {
            throw new UnsupportedOperationException();
         }
      };
   }

   public int size() {
      return element != null ? 1 : 0;
   }

   public boolean add(Object obj) {
      throw new UnsupportedOperationException("Can't add to a single element set");
   }
}

