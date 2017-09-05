/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */
package sc.dyn;

/**
 * Implement this interface in components that need to be notified when types change during refresh.
 * For example, the servlet PageDispatcher needs to be notified when new page types are created, updated or removed
 * so it can update it's dispatch table.
 * The types will be probably be TypeDeclarations (unless we can figure out a way to do more with compiled classes)
 * but this class can't depend on the implementation class
 * without adding a dependency on the dynamic runtime.  If you implement this class, you still can run
 * your code without the dynamic runtime - you just can't respond to type changes and TypeDeclaration is not defined
 * there.  Use the DynUtil methods with these type objects rather than casting to TypeDeclaration to avoid adding
 * a fixed dependency on the dynamic runtime.
 */
public interface ITypeChangeListener {
   void updateType(Object oldType, Object newType);
   void typeCreated(Object newType);
   void typeRemoved(Object oldType);
}
