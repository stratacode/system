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
   public long timeStamp;

   // TODO: these are stubs for the client right now since there's no real event delivery on the server
   public void preventDefault() {
   }
   public void stopPropagation() {
   }
}
