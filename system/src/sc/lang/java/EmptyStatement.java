/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.Set;

/** Marker class used for ; in EnumDeclaration to separate constanats from definitions */
public class EmptyStatement extends Statement {
   public boolean refreshBoundTypes(int flags) { return false; }
   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {}
   public void setAccessTimeForRefs(long time) {}
   public Statement transformToJS() { return this; }
}
