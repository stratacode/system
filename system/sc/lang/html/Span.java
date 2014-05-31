/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

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
}
  
