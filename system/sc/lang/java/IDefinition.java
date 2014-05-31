/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

public interface IDefinition {
   Object getAnnotation(String annotName);
   boolean hasModifier(String modifierName);
   AccessLevel getAccessLevel(boolean explicitOnly);
   Object getEnclosingIType();
   String modifiersToString(boolean includeAnnotations, boolean includeAccess, boolean includeFinal, boolean includeScopes, boolean abs, JavaSemanticNode.MemberType filterType);
}
