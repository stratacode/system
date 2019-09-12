/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.bind.Bind;
import sc.bind.Bindable;
import sc.lang.java.TypeDeclaration;

@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class Img extends HTMLElement {
   private final static sc.type.IBeanMapper _srcProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Img.class, "src");
   {
      tagName = "img";
   }
   public Img() {
   }
   public Img(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }
   public Img(TypeDeclaration concreteType, Element parent, Object repeatVar, int repeatIx) {
      super(concreteType, parent, repeatVar, repeatIx);
   }
   public Img(Element parent, Object repeatVar, int repeatIx) {
      super(parent, repeatVar, repeatIx);
   }

   private Object src;
   @Bindable(manual=true) public Object getSrc() {
      return src;
   }
   @Bindable(manual=true) public void setSrc(Object _src) {
      src = _src;
      Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, _srcProp, _src);
   }
}
