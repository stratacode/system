/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.Map;

public interface IDefinition {
   Object getAnnotation(String annotName);
   Map<String,Object> getAnnotations();
   boolean hasModifier(String modifierName);
   AccessLevel getAccessLevel(boolean explicitOnly);
   Object getEnclosingIType();

   /**
    * Returns the a specific set of of modifiers in string form - including annotations, access, final, or scopes with flags.
    * Used for generating the modifiers string for one definition generated from another (e.g. a setX method from a field)
    * If absoluteTypeNames is true, the absolute path of annotations is used.
    * If filterType is not null, only annotations appropriate for the given member type are included.  If you are generating code from a field for a setX method, you'd set a filter type of MemberType.Method.
    */
   String modifiersToString(boolean includeAnnotations, boolean includeAccess, boolean includeFinal, boolean includeScopes, boolean absoluteTypeNames, JavaSemanticNode.MemberType filterType);
}
