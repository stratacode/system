/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.bind.Bindable;
import sc.obj.TypeSettings;
import sc.type.PTypeUtil;

@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
@TypeSettings(objectType=true)
public class History {
   Window window;

   History(Window window) {
      this.window = window;
   }

   @sc.obj.Exec(clientOnly=true)
   public void back() {
      go(-1);
   }

   @sc.obj.Exec(clientOnly=true)
   public void forward() {
      go(1);
   }

   @sc.obj.Exec(clientOnly=true)
   public void go(int num) {
      if (window.pageDispatcher == null) // Maybe rendering a static html from a template
         System.err.println("*** Unable to invoke replaceState with no current window in the context");
      else
         // Update the browser's URL to match the change of URL properties made here on the server
         window.pageDispatcher.invokeRemote(this, History.class, "go", Void.class,"I", num);
   }

   @sc.obj.Exec(clientOnly=true)
   public void pushState(Object state, String title, String url) {
      if (window.pageDispatcher == null) // Maybe rendering a static html from a template
         System.err.println("*** Unable to invoke replaceState with no current window in the context");
      else
         // Update the browser's URL to match the change of URL properties made here on the server
         window.pageDispatcher.invokeRemote(this, History.class, "pushState", Void.class,
                 "Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String", state, title, url);
   }

   @sc.obj.Exec(clientOnly=true)
   public void replaceState(Object state, String title, String url) {
      if (window.pageDispatcher == null) // Maybe rendering a static html from a template
         System.err.println("*** Unable to invoke replaceState with no current window in the context");
      else
         // Update the browser's URL to match the change of URL properties made here on the server
         window.pageDispatcher.invokeRemote(this, History.class, "replaceState", Void.class,
                                   "Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String", state, title, url);
   }

   @Bindable(manual=true)
   public static History getHistory() {
      Window win = (Window) PTypeUtil.getThreadLocal("window");
      return win == null ? null : win.history;
   }
}
