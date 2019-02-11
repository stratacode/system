/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.bind.Bind;
import sc.bind.Bindable;
import sc.obj.Constant;
import sc.obj.TypeSettings;
import sc.type.PTypeUtil;

/**
 * A Java wrapper for the Javascript window.location object.  You can use it to refer to or modify the URL of the
 * page from Java code.  By default, this object is synchronized with the client, so changes you make are applied
 * to the JS window.location object.  So for example, if you set href, you can cause the browser to redirect to a
 * new page.
 */
@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
@TypeSettings(objectType=true)
public class Location {
   @Constant
   public String hash, host, hostname, protocol;

   @Constant
   public String port;

   /** These can be changed and are sync'd to the client */
   public String href, pathname, search;

   @Bindable(manual=true)
   public void setHref(String href) {
      this.href = href;
      Bind.sendChangedEvent(this, "href");
   }
   public String getHref() {
      return href;
   }

   @Bindable(manual=true)
   public void setPathname(String pathname) {
      this.pathname = pathname;
      Bind.sendChangedEvent(this, "pathname");
   }
   public String getPathname() {
      return pathname;
   }

   @Bindable(manual=true)
   public void setSearch(String search) {
      this.search = search;
      Bind.sendChangedEvent(this, "search");
   }
   public String getSearch() {
      return search;
   }

   @Bindable(manual=true)
   public static Location getLocation() {
      Window win = (Window) PTypeUtil.getThreadLocal("window");
      return win == null ? null : win.location;
   }

   public static void setLocation(Location loc) {
      throw new UnsupportedOperationException();
   }
}
