/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.bind.Bind;
import sc.bind.Bindable;
import sc.lang.java.TypeDeclaration;

@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class Button extends Input {

   public Button() {
      super();
   }
   public Button(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }
   public Button(TypeDeclaration concreteType, Element parent, Object repeatVar, int repeatIx) {
      super(concreteType, parent, repeatVar, repeatIx);
   }
   public Button(Element parent, Object repeatVar, int repeatIx) {
      super(parent, repeatVar, repeatIx);
   }
   {
      tagName = "button";
      type = "button";
   }

}
