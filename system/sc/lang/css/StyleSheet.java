/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.css;

import sc.lang.html.Style;
import sc.obj.CompilerSettings;
import sc.obj.Sync;
import sc.obj.SyncMode;

// TODO: This is a placeholder.  Need to optimize things so that: we generate declarative templates which automatically
// refresh (taking code from HTMLLanguage).   Then in the client side class we write some code to intercept the invalidate
// events and refresh the stylesheet.
@Sync(syncMode=SyncMode.Disabled)
@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
@CompilerSettings(outputMethodTemplate="sc.lang.html.CSSOutputMethodTemplate")
public class StyleSheet extends Style {
   public StyleSheet() {
   }

   public StyleSheet(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }

   // For the client this will refresh the stylesheet but don't know what we need to do here.
   public void invalidate() {
   }

   public void outputBody(StringBuilder sb) {
   }

   public void outputStartTag(StringBuilder sb) {
   }

   public void outputEndTag(StringBuilder sb) {
   }

}
