/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import java.util.List;

// TODO: do we need this? It's not used currently
/** Implemented by classes which use data binding expressions.  Allows that object to deactivate the bindings */
public interface IBindingContainer {
   void setBindings(List<DestinationListener> bindings);
   List<DestinationListener> getBindings();
}
