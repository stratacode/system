/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class KeyboardEvent extends Event {
   public int altKey;  // 1 if pressed, 0 otherwise
   public int ctrlKey; // 1 if pressed, 0 otherwise
   public int metaKey; // 1 if pressed, 0 otherwise
   public int shiftKey; // 1 if pressed, 0 otherwise

   public String key;
}
