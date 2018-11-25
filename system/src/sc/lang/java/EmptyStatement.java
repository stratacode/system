/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.Set;

/** Marker class used for ; in EnumDeclaration to separate constanats from definitions */
public class EmptyStatement extends Statement {
   public void refreshBoundTypes(int flags) {}
   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {}
   public Statement transformToJS() { return this; }
}
