/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.dyn;

/**
 * This interface is implemented by framework components that want to use the StrataCode Object hierarchy with
 * dynamic types and want to manage dynamic instances of those types automatically.  In that case, for each parent/child relationship
 * write a manager class that implements this interface to perform the same logic that you have in your type's objTemplate and newTemplate for managing
 * children - basically the init/add/remove/get operations.  When new instances are added or removed, your IDynChildManager implementation is
 * called.
 */
@sc.js.JSSettings(jsLibFiles="js/scdyn.js", prefixAlias="sc_")
public interface IDynChildManager {
   /** A hook to allow the manager to control whether children are created when the parent object is created or not. */
   boolean getInitChildrenOnCreate();
   /** Called when a new parent is created.  This is equivalent to the hook you may insert into the object template for the getX method when you are initializing the children. */
   void initChildren(Object parent, Object[] children);
   /** Called when the type system discovers a new child object */
   void addChild(Object parent, Object child);
   /** Like above but when the new child object is inserted at a specific location.  -1 means "append" */
   void addChild(int ix, Object parent, Object child);
   /** Remove the child object */
   boolean removeChild(Object parent, Object child);
   /** Return the object children */
   Object[] getChildren(Object parent);
}
