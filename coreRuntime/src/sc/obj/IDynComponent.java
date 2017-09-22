/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

/**
 * A marker interface used for dynamic stubs which back components.   Used for the DynUtil.isComponentType
 * method - we should be able to tell from the compiled class whether or not the class is a component so we
 * can construct it properly.
 */
public interface IDynComponent {
}
