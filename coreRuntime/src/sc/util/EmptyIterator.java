/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;

import java.util.Iterator;
import java.util.NoSuchElementException;

public class EmptyIterator<E> implements Iterator<E> {

   public static final EmptyIterator<Object> EMPTY_ITERATOR = new EmptyIterator<Object>();

   public E next() {
      throw new NoSuchElementException();
   }

   public boolean hasNext() {
      return false;
   }

   public void remove() {
      throw new IllegalStateException();
   }

}
