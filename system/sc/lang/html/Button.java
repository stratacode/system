/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.bind.Bind;
import sc.bind.Bindable;

@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class Button extends Input {
   public static sc.type.IBeanMapper _clickCountProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Button.class, "clickCount");

   public Button() {
      super();
   }
   public Button(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }
   {
      tagName = "button";
      type = "button";
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
