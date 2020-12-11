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

   public Object returnType;

   public int errorCode = -1;
   public String exceptionStr;

   /**
    * Set this property to an implementation class to be notified of success/errors in the method call immediately as the return value is processed.  Use this listener
    * when you need notification of a remote method response that is called in sequence with the other changes made during the 'sync'
    */
   public IResponseListener responseListener;

   /**
    * Set this property to an implementation class to be notified of success/errors in the method call after the current sync or other transaction has been completed.
    * Use this when you need a "do later" type operation such as knowing when the start the next command in a script listener.
    */
   public IResponseListener postListener;

   public Object object;

   public String instName;

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

   public void notifyResponseListener() {
      if (responseListener != null) {
         if (exceptionStr == null)
            responseListener.response(getValue());
         else
            responseListener.error(errorCode, exceptionStr);
      }
   }

   public void notifyPostListener() {
      if (postListener != null) {
         if (exceptionStr == null)
            postListener.response(getValue());
         else
            postListener.error(errorCode, exceptionStr);
      }
   }

   public void notifyAllListeners() {
      notifyResponseListener();
      notifyPostListener();
   }
}

