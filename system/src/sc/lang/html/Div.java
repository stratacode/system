/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
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
   public Div(TypeDeclaration concreteType, Object repeatVar, int repeatIx) {
      super(concreteType);
      setRepeatVar(repeatVar);
      setRepeatIndex(repeatIx);
   }
   public Div(Object repeatVar, int repeatIx) {
      super(repeatVar, repeatIx);
   }
}
  
