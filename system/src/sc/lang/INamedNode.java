/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

/**
 * A generic way to implement node types that have names.
 */
public interface INamedNode {
   void setNodeName(String newName);

   String getNodeName();

   String toListDisplayString();
}
