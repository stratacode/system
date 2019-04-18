/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class KeyboardEvent extends Event {
   public boolean altKey;
   public boolean ctrlKey;
   public boolean metaKey;
   public boolean shiftKey;

   public String key;
}
