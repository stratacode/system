/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

/** The tag base class for the head tag. */
@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class Head extends HTMLElement {
   {
      tagName = "head";
   }
   public Head() {
   }
   public Head(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }
}
