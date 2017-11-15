/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.bind.Bind;
import sc.bind.Bindable;

@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class Button extends Input {

   public Button() {
      super();
   }
   public Button(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }
   {
      tagName = "button";
      type = "button";
   }

}
