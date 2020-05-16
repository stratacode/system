/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.bind.Bind;
import sc.bind.Bindable;
import sc.type.IBeanMapper;
import sc.type.PTypeUtil;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;


@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class Select<RE> extends HTMLElement<RE> {
   private final static sc.type.IBeanMapper _selectedValueProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Select.class, "selectedValue");
   private final static sc.type.IBeanMapper _disabledProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Select.class, "disabled");
   private final static sc.type.IBeanMapper _multipleProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Select.class, "multiple");
   private final static sc.type.IBeanMapper _selectedIndexProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Select.class, "selectedIndex");
   private final static sc.type.IBeanMapper _optionDataSourceProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Select.class, "optionDataSource");
   private final static TreeMap<String,IBeanMapper> selectServerTagProps = new TreeMap<String,IBeanMapper>();
   private final static sc.type.IBeanMapper _sizeProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Select.class, "size");
   static {
      selectServerTagProps.put("selectedIndex", _selectedIndexProp);
   }
   {
      tagName = "select";
   }
   public String name;

   public Select() {
   }
   public Select(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }

   private int selectedIndex;
   @Bindable(manual=true) public int getSelectedIndex() {
      return selectedIndex;
   }
   @Bindable(manual=true) public void setSelectedIndex(int _selectedIndex) {
      if (_selectedIndex != selectedIndex) {
         selectedIndex = _selectedIndex;
         Object ds = optionDataSource;
         int len = ds == null ? 0 : PTypeUtil.getArrayLength(ds);
         if (_selectedIndex >= 0 && _selectedIndex < len)
            setSelectedValue(PTypeUtil.getArrayElement(ds, _selectedIndex));
         else
            setSelectedValue(null);
         Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, _selectedIndexProp, _selectedIndex);
      }
   }

   private boolean multiple = false;
   @Bindable(manual=true) public boolean getMultiple() {
      return multiple;
   }
   @Bindable(manual=true) public void setMultiple(boolean _multiple) {
      multiple = _multiple;
      Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, _multipleProp, _multiple);
   }

   List<RE> optionDataSource;

   public List<RE> getOptionDataSource() {
      return optionDataSource;
   }

   @Bindable(manual=true)
   public void setOptionDataSource(List values) {
      optionDataSource = values;
      if (values != optionDataSource) {
         if (values != null && selectedIndex != -1 && selectedIndex < values.size() && selectedValue == null) {
            setSelectedValue(values.get(selectedIndex));
         }
         Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, _optionDataSourceProp, values);
      }
   }

   private Object selectedValue;
   @Bindable(manual=true) public RE getSelectedValue() {
      return (RE) selectedValue;
   }
   @Bindable(manual=true) public void setSelectedValue(Object _selectedValue) {
      selectedValue = _selectedValue;
      Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, _selectedValueProp, _selectedValue);
   }

   private boolean disabled;
   @Bindable(manual=true) public boolean getDisabled() {
      return disabled;
   }
   @Bindable(manual=true) public void setDisabled(boolean _disabled) {
      disabled = _disabled;
      Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, _disabledProp, _disabled);
   }

   private int size = 20;
   @Bindable(manual=true) public int getSize() {
      return size;
   }
   @Bindable(manual=true) public void setSize(int _s) {
      size = _s;
      Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, _sizeProp, _s);
   }

   /** Output the contents of the select tag.  If there are no options, render any option tags with the 'empty' id, or if none, render the default body of the tag. */
   public void outputTag(StringBuilder sb, OutputCtx ctx) {
      if (optionDataSource == null || optionDataSource.size() == 0) {
           Element[] emptyIds = getChildrenById("empty");
           if (emptyIds == null) {
              // No data source specified - do the default action so this conforms to what the static HTML would produce
              if (optionDataSource == null)
                 super.outputTag(sb, ctx);
              else {
                 // If there's a data source and no elements and no empty tag, just output the start and end tags.
                 outputStartTag(sb, ctx);
                 outputEndTag(sb, ctx);
              }
           }
           else {
              outputStartTag(sb, ctx);
              for (Element emptyElement:emptyIds) {
                 emptyElement.outputTag(sb, ctx);
              }
              outputEndTag(sb, ctx);
           }
           return;
      }

      Element[] defChildren = getChildrenByIdAndType(null, Option.class);

      outputStartTag(sb, ctx);
      int ix = 0;
      int selIndex = getSelectedIndex();
      for (Object val: optionDataSource) {
         if (defChildren == null) {
            sb.append("<option");
            if (ix == selIndex)
               sb.append(" selected");
            sb.append(">");
            sb.append(val.toString());
            sb.append("</option>");
         }
         else {
            Option subOption = (Option) defChildren[ix % defChildren.length];
            subOption.setSelected(ix == selIndex);
            subOption.setOptionData(val);
            subOption.outputTag(sb, ctx);
         }
         ix++;
      }
      outputEndTag(sb, ctx);
   }

   @sc.obj.EditorSettings(visible=false)
   public Map<String,IBeanMapper> getCustomServerTagProps() {
      return selectServerTagProps;
   }

   @sc.obj.EditorSettings(visible=false)
   public boolean isEventSource() {
      return true;
   }
}
