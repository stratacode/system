/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.lang.java.TypeDeclaration;

@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class Div extends HTMLElement {
   {
      tagName = "div";
   }
   public Div() {
   }
   public Div(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }
   public Div(TypeDeclaration concreteType, Element parent, String id, Object repeatVar, int repeatIx) {
      super(concreteType, parent, id, repeatVar, repeatIx);
   }
   public Div(Element parent, String id, Object repeatVar, int repeatIx) {
      super(parent, id, repeatVar, repeatIx);
   }
}
  
