/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.List;

/**
 * The regular IdentifierExpression is used to hold simple method invocations - when arguments != null.
 * This is used when there are type arguments to the method.
 */
public class TypedMethodExpression extends IdentifierExpression {
   public String typedIdentifier;
   public List<JavaType> typeArguments;

   // Because the typedIdentifier is an extra identifier but not stored in identifiers we need to tweak the
   // algorithm used by IdentifierExpression in computing the reference for this identifier.
   protected int offset() {
      return 0;
   }

   public boolean isReferenceInitializer() {
      return false;
   }

   // TODO: this should do the work in IdentifierExpression's toStyledString but prepend the typeIdentifier
   // and rearrange how the child parse nodes are organized.
   public CharSequence toStyledString() {
      if (parseNode != null) {
         return parseNode.toStyledString();
      }
      else
         throw new IllegalArgumentException("No parse tree new expression's semantic node");
   }

   public void refreshBoundTypes() {
      for (JavaType jt:typeArguments)
         jt.refreshBoundType();
   }

   public int transformTemplate(int ix, boolean statefulContext) {
      for (JavaType jt:typeArguments)
         ix = jt.transformTemplate(ix, statefulContext);
      return ix;
   }

   public String toGenerateString() {
      StringBuilder sb = new StringBuilder();

      if (identifiers == null || typeArguments == null || typedIdentifier == null || arguments == null)
         return "<uninitialized TypedMethodExpression>";

      generateIdentString(sb, identifiers.size() - 1);

      sb.append(".<");
      int ix = 0;
      for (JavaType typeArg:typeArguments) {
         if (ix != 0)
            sb.append(", ");
         sb.append(typeArg.toGenerateString());
         ix++;
      }
      sb.append("> ");

      sb.append(typedIdentifier);
      sb.append(argsToGenerateString(arguments));
      return sb.toString();
   }

   public boolean applyPartialValue(Object value) {
      if (typedIdentifier == null) {
         // Since this extends from identifier expression we pick up it's behavior but if we do not have
         // an identifier there's nothing to complete
         return false;
      }
      return super.applyPartialValue(value);
   }
}
