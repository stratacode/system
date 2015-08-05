/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import sc.js.JSSettings;

/** A marker interface used to tag objects like the sc.util.ArrayList class - which send binding events for the value changing */
@JSSettings(jsLibFiles = "js/sccore.js", prefixAlias="sc_")
public interface IChangeable {
}
