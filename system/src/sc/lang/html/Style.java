/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class Style extends HTMLElement {
   {
      tagName = "style";
   }
   public Style() {
   }
   public Style(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }
}
