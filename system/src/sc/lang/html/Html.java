/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

/** The tag package for the Html tag but only when it's not the top-level tag in the page.  For the top level tag, we use HtmlPage. */
@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class Html extends HTMLElement {
   {
      tagName = "html";
   }
   public Html() {
   }
   public Html(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }

}
