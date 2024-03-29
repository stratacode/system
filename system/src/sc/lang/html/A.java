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

/** The tag base class for the anchor tag.
 *
 * TODO: This class an unfortunate name due to the way the tagPackageList works.  Maybe there should be a "Tag" suffix used for the tag-class
 * package lookup. *  */
@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class A extends HTMLElement {
   public final static sc.type.IBeanMapper _clickCountProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.A.class, "clickCount");
   private final static sc.type.IBeanMapper _disabledProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.A.class, "disabled");
   private final static TreeMap<String,IBeanMapper> aServerTagProps = new TreeMap<String,IBeanMapper>();
   static {
      aServerTagProps.put("clickCount", _clickCountProp);
   }

   public A() {
      super();
   }
   public A(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }
   public A(TypeDeclaration concreteType, Element parent, String id, Object repeatVar, int repeatIx) {
      super(concreteType, parent, id, repeatVar, repeatIx);
   }
   public A(Element parent, String id, Object repeatVar, int repeatIx) {
      super(parent, id, repeatVar, repeatIx);
   }
   {
      tagName = "a";
   }

   private int clickCount;
   /** An alternative to the clickEvent property.  TODO: Probably should be deprecated or just removed? */
   @Bindable(manual=true) public Object getClickCount() {
      return clickCount;
   }
   @Bindable(manual=true) public void setClickCount(int _clickCount) {
      clickCount = _clickCount;
      Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, _clickCountProp, _clickCount);
   }

   @sc.obj.EditorSettings(visible=false)
   public Map<String,IBeanMapper> getCustomServerTagProps() {
      return aServerTagProps;
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
