/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.bind.Bind;
import sc.bind.Bindable;
import sc.obj.Constant;

/**
 * A Java wrapper for the Javascript window.location object.  You can use it to refer to or modify the URL of the
 * page from Java code.   Right now, the server code just provides read-only access to the URL info.  The client
 * code will pass through to the page.
 */
@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
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
}
