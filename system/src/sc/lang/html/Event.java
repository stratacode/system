/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.obj.Scope;

@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
// Make this window scope so that it lines up with the process allocating event obj names - i.e. we don't want MouseEvent__0
// to pick up an old event or shared with an inappropriate scope
@Scope(name="window")
public class Event {
   public String type;
   // On the server currentTarget and currentTag are the same.  On the client, currentTag is the js_Element_c instance and currentTarget is the standard JS DOM object.
   public Element currentTarget;
   public Element currentTag;
   public Element target;
   public double timeStamp;

   // These are stubs so you can use these apis in Java code but they don't do anything.
   public void preventDefault() {
   }
   public void stopPropagation() {
   }
}
