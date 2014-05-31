/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

/**
 * A wrapper class used to represent the javascript document object in the Java api.  Currently no features of the
 * document are supported on the server.
 */
@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class Document extends HTMLElement {
}
