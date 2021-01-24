/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class KeyboardEvent extends Event {
   public boolean altKey;
   public boolean ctrlKey;
   public boolean metaKey;
   public boolean shiftKey;
   public boolean repeat;

   public String key;
}
