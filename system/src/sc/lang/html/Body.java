/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class Body extends HTMLElement {
   {
      tagName = "body";
   }
   public Body() {
   }
   public Body(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }
}
