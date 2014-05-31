/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.obj.Constant;

/**
 * A Java wrapper for the Javascript window.location object.  You can use it to refer to or modify the URL of the
 * page from Java code.   Right now, the server code just provides read-only access to the URL info.  The client
 * code will pass through to the page.
 * TODO: need to make these properties bindable and settable on the client and server.  When set on the server, they
 * should be sync'd back and update the URL on the client.  This is for bookmarkable, navigation in rich apps.
 * A separae feature is a client/server api to do a redirect to another page.
 */
@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class Location {
   @Constant
   public String hash, host, hostname, href, pathname, protocol, search;
   @Constant
   public String port;
}
