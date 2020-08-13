/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.obj.Scope;

@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class MouseEvent extends Event {
   public boolean altKey;
   public boolean ctrlKey;
   public boolean metaKey;
   public boolean shiftKey;

   public int button; // 0 = left, 1 = middle, 2 = right

   public int clientX, clientY; // mouse coords for the click
   public int screenX, screenY; // mouse coords for the click
}
