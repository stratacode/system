/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.obj.Sync;
import sc.obj.SyncMode;

@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
/** Used as the tag class for the html tag it is the top level tag in the page. */
@Sync(syncMode= SyncMode.Automatic) // Turn back on sync mode for user defined page types so that any fields they defined will be synchronized by default.
public class HtmlPage extends Html {
   protected boolean isPageElement() {
      return true;
   }

   public HtmlPage() {
   }
   public HtmlPage(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }
}
