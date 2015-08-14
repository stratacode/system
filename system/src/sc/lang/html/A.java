/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.bind.Bind;
import sc.bind.Bindable;

/** The tag base class for the anchor tag.
 *
 * TODO: This class an unfortunate name due to the way the tagPackageList works.  Maybe there should be a "Tag" suffix used for the tag-class
 * package lookup. *  */
@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class A extends HTMLElement {
   public final static sc.type.IBeanMapper _clickCountProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.A.class, "clickCount");

   public A() {
      super();
   }
   public A(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
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
}