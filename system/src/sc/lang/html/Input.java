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
public class Input extends HTMLElement {
   private final static sc.type.IBeanMapper _checkedProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Input.class, "checked");
   private final static sc.type.IBeanMapper _disabledProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Input.class, "disabled");
   private final static sc.type.IBeanMapper _valueProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Input.class, "value");
   private final static sc.type.IBeanMapper _sizeProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Input.class, "size");
   public final static sc.type.IBeanMapper _changeEventProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Input.class, "changeEvent");
   public final static sc.type.IBeanMapper _clickCountProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Input.class, "clickCount");

   private final static TreeMap<String,IBeanMapper> inputServerTagProps = new TreeMap<String,IBeanMapper>();
   static {
      inputServerTagProps.put("checked", _checkedProp);
      inputServerTagProps.put("value", _valueProp);
      inputServerTagProps.put("changeEvent", _changeEventProp);
      inputServerTagProps.put("clickCount", _clickCountProp);
   }

   public Input() {
      super();
   }
   public Input(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }
   public Input(TypeDeclaration concreteType, Element parent, String id, Object repeatVar, int repeatIx) {
      super(concreteType, parent, id, repeatVar, repeatIx);
   }
   public Input(Element parent, String id, Object repeatVar, int repeatIx) {
      super(parent, id, repeatVar, repeatIx);
   }
   {
      tagName = "input";
   }
   public String type; // "text", "button", etc

   public String name;

   private int size = 20;
   @Bindable(manual=true) public int getSize() {
      return size;
   }
   @Bindable(manual=true) public void setSize(int _s) {
      size = _s;
      Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, _sizeProp, _s);
   }

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
   private boolean disabled;
   @Bindable(manual=true) public boolean getDisabled() {
      return disabled;
   }
   @Bindable(manual=true) public void setDisabled(boolean _disabled) {
      disabled = _disabled;
      Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, _disabledProp, _disabled);
   }

   private boolean checked;
   @Bindable(manual=true) public boolean getChecked() {
      return checked;
   }
   @Bindable(manual=true) public void setChecked(boolean _checked) {
      checked = _checked;
      Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, _checkedProp, _checked);
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

   private int clickCount;
   @Bindable(manual=true) public Object getClickCount() {
      return clickCount;
   }
   @Bindable(manual=true) public void setClickCount(int _clickCount) {
      clickCount = _clickCount;
      Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, _clickCountProp, _clickCount);
   }

   @sc.obj.EditorSettings(visible=false)
   public Map<String,IBeanMapper> getCustomServerTagProps() {
      return inputServerTagProps;
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
