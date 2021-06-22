/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.bind.Bind;
import sc.bind.Bindable;
import sc.js.ServerTag;
import sc.lang.java.TypeDeclaration;
import sc.type.IBeanMapper;

import java.util.Map;
import java.util.TreeMap;

@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class Textarea extends HTMLElement {
   private final static sc.type.IBeanMapper _valueProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Textarea.class, "value");
   public final static sc.type.IBeanMapper _changeEventProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Textarea.class, "changeEvent");

   private final static TreeMap<String,IBeanMapper> textareaServerTagProps = new TreeMap<String,IBeanMapper>();
   static {
      textareaServerTagProps.put("value", _valueProp);
      textareaServerTagProps.put("changeEvent", _changeEventProp);
   }

   public Textarea() {
      super();
   }
   public Textarea(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }
   public Textarea(TypeDeclaration concreteType, Element parent, String id, Object repeatVar, int repeatIx) {
      super(concreteType, parent, id, repeatVar, repeatIx);
   }
   public Textarea(Element parent, String id, Object repeatVar, int repeatIx) {
      super(parent, id, repeatVar, repeatIx);
   }
   {
      tagName = "textarea";
   }
   public String type = "textarea"; 

   public String name;

   private String value = "";
   @Bindable(manual=true) public String getValue() {
      return value;
   }
   @Bindable(manual=true) public void setValue(String _value) {
      if (_value == null) {
         if (value.length() == 0) // Don't send an event for this since the value did not change
            return;
         _value = "";
      }
      value = _value;
      Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, _valueProp, _value);
   }

   /** Method implemented on the client only to generate a simulated value change event  */
   @sc.obj.Exec(clientOnly=true)
   public void changeValue(String newVal) {
   }

   @sc.obj.EditorSettings(visible=false)
   @Bindable(manual=true) public Event getChangeEvent() {
      return (Event) getDOMEvent(EventType.Change);
   }
   @Bindable(manual=true) public void setChangeEvent(Event changeEvent) {
      setDOMEvent(EventType.Change, changeEvent, _changeEventProp);
   }

   @sc.obj.EditorSettings(visible=false)
   public Map<String,IBeanMapper> getCustomServerTagProps() {
      return textareaServerTagProps;
   }

   @sc.obj.EditorSettings(visible=false)
   public boolean isEventSource() {
      return true;
   }

   public String liveEdit = "on";
   public int liveEditDelay = 0;

   public void addServerTagFlags(ServerTag st) {
      st.liveEdit = liveEdit;
      st.liveEditDelay = liveEditDelay;
   }
}
