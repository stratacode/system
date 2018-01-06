/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

/**
 * Implement this interface to add hooks to how array values are synchronized with the repeat tag - i.e. one tag
 * per value in the list.
 * You can create a new element, or decide whether or not to reuse an element, and be notified when you need to renumber
 * elements in the list because an element was removed.
 */
@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public interface IRepeatWrapper {
   Element createElement(Object value, int ix, Element oldTag);
   void updateElementIndexes(int fromIx);
}
