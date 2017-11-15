/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.bind.Bind;
import sc.bind.Bindable;

@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class Input extends HTMLElement {
   private final static sc.type.IBeanMapper _checkedProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Input.class, "checked");
   private final static sc.type.IBeanMapper _disabledProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Input.class, "disabled");
   private final static sc.type.IBeanMapper _valueProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Input.class, "value");
   public final static sc.type.IBeanMapper _changeEventProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Input.class, "changeEvent");
   public static sc.type.IBeanMapper _clickCountProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Input.class, "clickCount");

   public Input() {
      super();
   }
   public Input(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }
   {
      tagName = "input";
   }
   public String type; // "text", "button", etc

   public String name;
   public int size;

   private String value;
   @Bindable(manual=true) public String getValue() {
      return value;
   }
   @Bindable(manual=true) public void setValue(String _value) {
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
}
