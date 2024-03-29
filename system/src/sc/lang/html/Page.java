/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js", extendsJSFiles="js/jvsys.js,js/scbind.js,js/sync.js")
public class Page extends HTMLElement {
   public Page() {
   }
   public Page(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }

   protected boolean isPageElement() {
      return true;
   }
}
