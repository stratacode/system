/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.dyn;

import sc.bind.Bind;
import sc.bind.Bindable;
import sc.bind.IListener;
import sc.js.JSSettings;
import sc.type.IResponseListener;

/**
 * Used to retrieve the response value from a remote method call.  It is returned by SyncManager.invokeRemote.  Needs to be in the data
 * binding because of the dependencies to and from the data binding code
 */
@JSSettings(jsModuleFile = "js/scbind.js", prefixAlias="sc_")
public class RemoteResult {
   public String callId;

   /** Set this property to an implementation class to be notified of success/errors in the method call. */
   public IResponseListener listener;

   private Object value;
   /** A bindable property you can use to listen to the response value of the method */
   public Object getValue() {
      return value;
   }

   @Bindable(manual=true)
   public void setValue(Object val) {
      value = val;
      Bind.sendEvent(IListener.VALUE_CHANGED, this, "value");
   }
}
