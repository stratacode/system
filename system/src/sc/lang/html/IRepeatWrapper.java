/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

/**
 * Used to gain more control over the construction of tag objects that
 * correspond to elements of the 'repeat' attribute's value.
 * Implement this interface with your own class and set the repeatWrapper attribute
 * on the same tag that has repeat.
 */
@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public interface IRepeatWrapper {
   /*
    * The createElement method will be called with the current repeatVar element as
    * the value parameter. It returns the Element instance to associate with that
    * value. This method should also set repeatVar and repeatIndex in the Element
    * returned if it can't use the Element(repeatVar, repeatIx) constructor
    * Its best to set the constructor so that repeatVar is never null for any of
    * the bindings that might depend on it in the page.
    * <p>
    * The oldTag parameter can be used for more efficient rendering.
    * The first time that a repeat tag is rendered, the oldTag parameter will
    * always be null. The second time, after the list has changed, when the
    * syncRepeatTags method needs to replace the value for an existing tag in
    * a given slot, it provides oldTag set to the tag in that slot.  You can either
    * ignore oldTag and just construct a new instance or set the repeatVar and repeatIndex
    * in oldTag and return it.
    */
   Element createElement(Object value, int ix, Element oldTag);
   /**
    * Called when elements have been re-ordered in the list, starting at fromIx.
    * In this method, set the repeat index for all elements after fromIx in the list.
    */
   void updateElementIndexes(int fromIx);

   /** Called when refreshing after a repeat value change for each slot where the value is still the same.  */
   //Element validateElement(Object value, int ix, Element oldTag);
}
