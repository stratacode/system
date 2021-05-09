package sc.lang.html;

import sc.bind.AbstractListener;
import sc.js.ServerTag;

/**
 * Listens for new bindings to be added to the tag object and checks if any new server tag props need to be added for these new listeners
 */
public class STagBindingListener extends AbstractListener {
   ServerTag serverTag;

   STagBindingListener(ServerTag serverTag) {
      this.serverTag = serverTag;
   }

   public boolean listenerAdded(Object obj, Object prop, Object listener, int eventMask, int priority) {
      serverTag.listenersValid = false;
      return true;
   }
}
