/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.dyn;

/**
 * Interface implemented by parent objects that provide named children.  Used for getting the object name
 * from a child and getting a child from the parent by name.
 */
@sc.js.JSSettings(jsLibFiles="js/scdyn.js")
public interface INamedChildren {
   String getNameForChild(Object child);
   Object getChildForName(String name);
}
