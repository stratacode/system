/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
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
