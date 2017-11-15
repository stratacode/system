/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.bind.Bind;
import sc.bind.Bindable;

@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class Form extends HTMLElement {
   public final static sc.type.IBeanMapper _submitEventProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Form.class, "submitEvent");
   private final static sc.type.IBeanMapper _submitCountProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Form.class, "submitCount");
   {
      tagName = "form";
   }
   public Form() {
      super();
   }
   public Form(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
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

   @Bindable(manual=true) public Event getSubmitEvent() {
      return getDOMEvent(EventType.Submit);
   }
   @Bindable(manual=true) public void setSubmitEvent(Event submitEvent) {
      setDOMEvent(EventType.Submit, submitEvent, _submitEventProp);
   }

   /** Implemented on the client to simulate a click on the submit button */
   public void submit() {
   }
}
