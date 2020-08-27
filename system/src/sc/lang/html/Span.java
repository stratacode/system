/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.lang.java.TypeDeclaration;

@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class Span extends HTMLElement {
   {
      tagName = "span";
   }
   public Span() {
   }
   public Span(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }
   public Span(TypeDeclaration concreteType, Element parent, String id, Object repeatVar, int repeatIx) {
      super(concreteType, parent, id, repeatVar, repeatIx);
   }
   public Span(Element parent, String id, Object repeatVar, int repeatIx) {
      super(parent, id, repeatVar, repeatIx);
   }
}
  
