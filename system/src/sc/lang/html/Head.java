/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.js.ServerTag;
import sc.js.ServerTagContext;
import sc.lang.css.StyleSheet;
import sc.obj.ScopeDefinition;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/** The tag base class for the head tag. */
@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class Head extends HTMLElement<Object> {
   {
      tagName = "head";
   }
   public Head() {
   }
   public Head(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }

   // For any stylesheets that might depend on dynamic logic (i.e. sccss files that are not generated at compile time),
   // the code-gen will set this property. The page can then cache the old styles, and refresh the styles if any logic
   // in the page changes.
   public transient String[] styleSheetPaths;

   public void addServerTags(ScopeDefinition scopeDef, ServerTagContext stCtx, boolean defaultServerTag) {
      if (styleSheetPaths != null) {
         Element page = getEnclosingTag();
         if (page instanceof HtmlPage) {
            // If there's no JS version of this page, there's no JS version of this style sheet.
            if (page.serverTag) {
               IPageDispatcher dispatcher = ((HtmlPage) page).pageDispatcher;
               for (int i = 0; i < styleSheetPaths.length; i++) {
                  IPageEntry pageEnt = dispatcher.lookupPageType("/" +  styleSheetPaths[i]);
                  if (pageEnt != null) {
                     Object pageInst = pageEnt.getCurrentInstance();
                     if (pageInst instanceof StyleSheet) {
                        StyleSheet curSheet = (StyleSheet) pageInst;
                        curSheet.serverTag = true;
                        if (curSheet.getId() == null)
                           curSheet.setId("_css." + curSheet.getClass().getSimpleName());
                        curSheet.addServerTags(scopeDef, stCtx, defaultServerTag);
                     }
                  }
               }
            }
         }
      }
      super.addServerTags(scopeDef, stCtx, defaultServerTag);
   }
}
