/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.css;

import sc.lang.html.OutputCtx;
import sc.lang.html.Style;
import sc.obj.CompilerSettings;
import sc.obj.ResultSuffix;
import sc.obj.Sync;
import sc.obj.SyncMode;

@Sync(syncMode=SyncMode.Disabled)
@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
@CompilerSettings(outputMethodTemplate="sc.lang.html.CSSOutputMethodTemplate")
@ResultSuffix("css")
public class StyleSheet extends Style {
   public StyleSheet() {
   }

   public StyleSheet(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }

   // For the client this will refresh the stylesheet but don't know what we need to do here.
   public void invalidate() {
   }

   public void outputBody(StringBuilder sb, OutputCtx ctx) {
      super.outputBody(sb, ctx);
   }

   public void outputStartTag(StringBuilder sb, OutputCtx ctx) {
      super.outputStartTag(sb, ctx);
   }

   public void outputEndTag(StringBuilder sb, OutputCtx ctx) {
      super.outputEndTag(sb, ctx);
   }
}
