/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

public interface IBindable
{
   BindingListener[] getBindingListeners();
   void setBindingListeners(BindingListener[] bindings);
}
