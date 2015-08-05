/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;

import java.util.Iterator;

/* Placeholder - expand to include full Set implementation */
public interface ISet<T> 
{
   int size();
   boolean containsAny(ISet<T> other);

   boolean contains(T other);

   boolean add(T object);

   Iterator<T> iterator();

}
