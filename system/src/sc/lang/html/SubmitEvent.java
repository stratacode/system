/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class SubmitEvent extends Event {
   public Element target;
}
