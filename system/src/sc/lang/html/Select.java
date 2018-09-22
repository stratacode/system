/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.bind.Bind;
import sc.bind.Bindable;
import sc.type.IBeanMapper;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;


@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class Select<RE> extends HTMLElement<RE> {
   private final static sc.type.IBeanMapper _selectedValueProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Select.class, "selectedValue");
   private final static sc.type.IBeanMapper _selectedIndexProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Select.class, "selectedIndex");
   private final static TreeMap<String,IBeanMapper> selectServerTagProps = new TreeMap<String,IBeanMapper>();
   static {
      selectServerTagProps.put("selectedIndex", _selectedIndexProp);
   }
   {
      tagName = "select";
   }
   public String name;

   public boolean disabled;

   public int size;

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
      selectedIndex = _selectedIndex;
      Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, _selectedIndexProp, _selectedIndex);
   }

   public boolean multiple = false;

   List<RE> optionDataSource;

   public List<RE> getOptionDataSource() {
      return optionDataSource;
   }

   public void setOptionDataSource(List values) {
      optionDataSource = values;
   }

   private Object selectedValue;
   @Bindable(manual=true) public RE getSelectedValue() {
      return (RE) selectedValue;
   }
   @Bindable(manual=true) public void setSelectedValue(Object _selectedValue) {
      selectedValue = _selectedValue;
      Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, _selectedValueProp, _selectedValue);
   }

   /** Output the contents of the select tag.  If there are no options, render any option tags with the 'empty' id, or if none, render the default body of the tag. */
   public void outputTag(StringBuilder sb) {
      if (optionDataSource == null || optionDataSource.size() == 0) {
           Element[] emptyIds = getChildrenById("empty");
           if (emptyIds == null) {
              // No data source specified - do the default action so this conforms to what the static HTML would produce
              if (optionDataSource == null)
                 super.outputTag(sb);
              else {
                 // If there's a data source and no elements and no empty tag, just output the start and end tags.
                 outputStartTag(sb);
                 outputEndTag(sb);
              }
           }
           else {
              outputStartTag(sb);
              for (Element emptyElement:emptyIds) {
                 emptyElement.outputTag(sb);
              }
              outputEndTag(sb);
           }
           return;
      }

      Element[] defChildren = getChildrenByIdAndType(null, Option.class);

      outputStartTag(sb);
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
            // TODO: here and in JS should we have a way to inject the 'selected' attribute if it's not already defined
            // in the canonical tag?
            Option subOption = (Option) defChildren[ix % defChildren.length];
            subOption.setOptionData(val);
            subOption.outputTag(sb);
         }
         ix++;
      }
      outputEndTag(sb);
   }

   public Map<String,IBeanMapper> getCustomServerTagProps() {
      return selectServerTagProps;
   }

   public boolean isEventSource() {
      return true;
   }
}
