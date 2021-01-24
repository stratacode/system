/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

// TODO: remove this interface? It's a placeholder to support storing the listeners for properties on an object
// without using the WeakHashMap
public interface IBindable {
   BindingListener[] getBindingListeners();
   void setBindingListeners(BindingListener[] bindings);
}
