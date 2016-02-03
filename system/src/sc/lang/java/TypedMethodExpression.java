/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.parser.IString;
import sc.parser.IStyleAdapter;
import sc.parser.PString;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The regular IdentifierExpression is used to hold simple method invocations - when arguments != null.
 * This is used when there are type arguments to the method.
 */
public class TypedMethodExpression extends IdentifierExpression {
   public String typedIdentifier; // the name of the method being called with explicit type parameters
   public List<JavaType> typeArguments;

   // This contains a copy of inherited identifiers member variable's list with typeIdentifier appended at the end (so it includes
   // all of the identifiers of this expression).
   private transient List<IString> aidentifiers;

   public List<IString> getAllIdentifiers() {
      if (aidentifiers == null) {
         aidentifiers = new ArrayList<IString>(identifiers == null ? 1 : identifiers.size() + 1);
         if (identifiers != null)
            aidentifiers.addAll(identifiers);
         if (typedIdentifier == null)
            return null;
         aidentifiers.add(PString.toPString(typedIdentifier));
      }
      return aidentifiers;
   }

   // Because the typedIdentifier is an extra identifier but not stored in identifiers we need to tweak the
   // algorithm used by IdentifierExpression in computing the reference for this identifier.
   /*
   protected int offset() {
      return 0;
   }
   */

   public List<JavaType> getMethodTypeArguments() {
      return typeArguments;
   }

   private Object[] getMethodTypeParams() {
      if (boundTypes != null && typeArguments != null) {
         int last = boundTypes.length - 1;
         Object method = boundTypes[last];
         if (method != null) {
            return ModelUtil.getMethodTypeParameters(method);
         }
      }
      return null;
   }

   public void start() {
      super.start();

      Object[] methTypeParams = getMethodTypeParams();
      if (methTypeParams != null && methTypeParams.length != typeArguments.size()) {
         displayError("Mismatching number of method type parameters: " + Arrays.asList(methTypeParams) + " and supplied types: " + typeArguments + " for: ");
      }
   }

   public boolean isReferenceInitializer() {
      return false;
   }

   // TODO: this should do the work in IdentifierExpression's styleNode but prepend the typeIdentifier
   // and rearrange how the child parse nodes are organized.
   public void styleNode(IStyleAdapter adapter) {
      if (parseNode != null) {
         parseNode.styleNode(adapter, null, null, -1);
      }
      else
         throw new IllegalArgumentException("No parse tree new expression's semantic node");
   }

   public void refreshBoundTypes(int flags) {
      super.refreshBoundTypes(flags);
      for (JavaType jt:typeArguments)
         jt.refreshBoundType(flags);
   }

   public int transformTemplate(int ix, boolean statefulContext) {
      super.transformTemplate(ix, statefulContext);
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

   public Object getTypeDeclaration() {
      return getGenericType();
   }

   public Object getGenericType() {
      Object type = super.getGenericType();
      if (type instanceof ParamTypeDeclaration) {
         ParamTypeDeclaration ptd = ((ParamTypeDeclaration) type).copy();
         int numParams = ptd.types.size();
         if (numParams == typeArguments.size()) {
            for (int i = 0; i < numParams; i++) {
               ptd.types.set(i, typeArguments.get(i).getTypeDeclaration());
            }
         }
         return ptd;
      }
      return type;
   }
}
