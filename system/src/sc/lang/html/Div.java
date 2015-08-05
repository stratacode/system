/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

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
}
  
