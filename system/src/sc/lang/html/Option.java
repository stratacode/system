/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.bind.Bind;
import sc.bind.Bindable;
import sc.type.IBeanMapper;

import java.util.Map;
import java.util.TreeMap;

@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class Option<T> extends HTMLElement {
   public final static sc.type.IBeanMapper _selectedProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Option.class, "selected");
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

   public boolean disabled;

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

   @Bindable(manual=true) public void setOptionData(T values) {
      optionData = values;
      Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, _optionDataProp, values);
   }

   public Map<String,IBeanMapper> getCustomServerTagProps() {
      return optionServerTagProps;
   }

   public boolean isEventSource() {
      return true;
   }
}
