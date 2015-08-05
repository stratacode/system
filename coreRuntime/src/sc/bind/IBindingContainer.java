/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import java.util.List;

/** Implemented by classes which use data binding expressions.  Allows that object to deactivate the bindings */
public interface IBindingContainer {
   void setBindings(List<DestinationListener> bindings);
   List<DestinationListener> getBindings();
}
