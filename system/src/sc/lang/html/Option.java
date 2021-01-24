/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.bind.Bind;
import sc.bind.Bindable;
import sc.lang.java.TypeDeclaration;
import sc.type.IBeanMapper;

import java.util.Map;
import java.util.TreeMap;

@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class Option<T> extends HTMLElement {
   public final static sc.type.IBeanMapper _selectedProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Option.class, "selected");
   private final static sc.type.IBeanMapper _disabledProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Option.class, "disabled");
   public final static sc.type.IBeanMapper _optionDataProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Option.class, "optionData");
   private final static TreeMap<String,IBeanMapper> optionServerTagProps = new TreeMap<String,IBeanMapper>();
   static {
      optionServerTagProps.put("selected", _selectedProp);
      optionServerTagProps.put("optionData", _optionDataProp);
   }
   {
      tagName = "option";
   }

   public Option() {
   }
   public Option(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }
   public Option(TypeDeclaration concreteType, Element parent, String id, Object repeatVar, int repeatIx) {
      super(concreteType, parent, id, repeatVar, repeatIx);
   }
   public Option(Element parent, String id, Object repeatVar, int repeatIx) {
      super(parent, id, repeatVar, repeatIx);
   }

   private boolean selected;
   @Bindable(manual=true) public boolean getSelected() {
      return selected;
   }
   @Bindable(manual=true) public void setSelected(boolean _selected) {
      selected = _selected;
      Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, _selectedProp, _selected);
   }

   T optionData;

   @Bindable(manual=true) public T getOptionData() {
      return optionData;
   }

   @Bindable(manual=true) public void setOptionData(T value) {
      optionData = value;
      Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, _optionDataProp, value);
   }

   @sc.obj.EditorSettings(visible=false)
   public Map<String,IBeanMapper> getCustomServerTagProps() {
      return optionServerTagProps;
   }

   @sc.obj.EditorSettings(visible=false)
   public boolean isEventSource() {
      return true;
   }

   private boolean disabled;
   @Bindable(manual=true) public boolean getDisabled() {
      return disabled;
   }
   @Bindable(manual=true) public void setDisabled(boolean _disabled) {
      disabled = _disabled;
      Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, _disabledProp, _disabled);
   }
}
