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
   public Span(TypeDeclaration concreteType, Object repeatVar, int repeatIx) {
      super(concreteType);
      setRepeatVar(repeatVar);
      setRepeatIndex(repeatIx);
   }
   public Span(Object repeatVar, int repeatIx) {
      super(repeatVar, repeatIx);
   }
}
  
