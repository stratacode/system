/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.bind.Bind;
import sc.bind.Bindable;
import sc.lang.java.TypeDeclaration;
import sc.type.IBeanMapper;

import java.util.Map;
import java.util.TreeMap;

@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class Form extends HTMLElement {
   public final static sc.type.IBeanMapper _submitEventProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Form.class, "submitEvent");
   private final static sc.type.IBeanMapper _submitCountProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Form.class, "submitCount");
   private final static sc.type.IBeanMapper _submitInProgressProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Form.class, "submitInProgress");
   private final static sc.type.IBeanMapper _submitErrorProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Form.class, "submitError");
   private final static TreeMap<String,IBeanMapper> formServerTagProps = new TreeMap<String,IBeanMapper>();
   static {
      formServerTagProps.put("submitEvent", _submitEventProp);
      formServerTagProps.put("submitCount", _submitCountProp);
   }

   {
      tagName = "form";
   }
   public Form() {
      super();
   }
   public Form(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }
   public Form(TypeDeclaration concreteType, Element parent, Object repeatVar, int repeatIx) {
      super(concreteType, parent, repeatVar, repeatIx);
   }
   public Form(Element parent, Object repeatVar, int repeatIx) {
      super(parent, repeatVar, repeatIx);
   }

   private int submitCount;

   // Now that sync is off by default for the UI don't turn it on just for this property.  This might be best practice if we are sync'ing the UI components.
   // Sync'ing only when changing from the client to the server .  Otherwise, the server will want to reset submitCount when initializing the page, which would then trigger another submit that we don't want.
   //@Sync(syncMode= SyncMode.ClientToServer)
   @Bindable(manual=true) public Object getSubmitCount() {
      return submitCount;
   }
   @Bindable(manual=true) public void setSubmitCount(int _submitCount) {
      submitCount = _submitCount;
      Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, _submitCountProp, _submitCount);
   }

   @sc.obj.EditorSettings(visible=false)
   @Bindable(manual=true) public Event getSubmitEvent() {
      return getDOMEvent(EventType.Submit);
   }
   @Bindable(manual=true) public void setSubmitEvent(Event submitEvent) {
      setDOMEvent(EventType.Submit, submitEvent, _submitEventProp);
   }

   /**
    * Implemented on the client to call the DOM 'submit()' method - which will typically navigate the page away.
    * For test scripts that want to simulate a submitEvent, use sendSubmitEvent below
    */
   @sc.obj.Exec(clientOnly=true)
   public void submit() {
   }

   /** Implemented on the client to send the submit event without actually submitting the form */
   @sc.obj.Exec(clientOnly=true)
   public void sendSubmitEvent() {
   }

   /**
    * Implemented on the client to send the FormData to the given dstURL. Unlike 'submit()' it will not navigate to the action URL.
    * Most useful for file upload forms that cannot be easily implemented using the sync api.
    */
   @sc.obj.Exec(clientOnly=true)
   public void submitFormData(String dstURL) {
   }

   private boolean submitInProgress = false;
   @Bindable(manual=true)
   public boolean getSubmitInProgress() {
      return submitInProgress;
   }
   public void setSubmitInProgress(boolean val) {
      submitInProgress = val;
      Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, _submitInProgressProp, val);
   }

   private String submitError = null;
   @Bindable(manual=true)
   public String getSubmitError() {
      return submitError;
   }
   public void setSubmitError(String val) {
      submitError = val;
      Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, _submitErrorProp, val);
   }

   @sc.obj.EditorSettings(visible=false)
   public Map<String,IBeanMapper> getCustomServerTagProps() {
      return formServerTagProps;
   }

   @sc.obj.EditorSettings(visible=false)
   public boolean isEventSource() {
      return true;
   }
}
