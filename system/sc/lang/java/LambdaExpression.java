/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ILanguageModel;
import sc.lang.ISemanticNode;
import sc.lang.JavaLanguage;
import sc.lang.SemanticNodeList;
import sc.parser.IString;
import sc.parser.PString;
import sc.parser.ParseUtil;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Java 8 lambda expression.  To run these in JS we need to convert them to anonymous classes */
public class LambdaExpression extends BaseLambdaExpression {
   public Object lambdaParams; // An identifier, e.g. x -> y, Parameter list (int a, int b) -> y, or SemanticNodeList<IString> (a, b) -> y
   public Statement lambdaBody; // Either an expression or a BlockStatement

   public Object getLambdaParameters(Object meth) {
      return lambdaParams;
   }

   public Statement getLambdaBody(Object meth) {
      return lambdaBody;
   }

   public String getExprType() {
      return "lambda expression";
   }

   @Override
   public void refreshBoundTypes() {
      if (lambdaParams instanceof Parameter)
         ((Parameter) lambdaParams).refreshBoundType();
      if (lambdaBody instanceof BlockStatement)
         lambdaBody.refreshBoundTypes();
   }

   @Override
   public void addDependentTypes(Set<Object> types) {
      if (lambdaParams instanceof Parameter)
         ((Parameter) lambdaParams).addDependentTypes(types);
      if (lambdaBody != null)
         lambdaBody.addDependentTypes(types);
   }

   public String toGenerateString() {
      StringBuilder sb = new StringBuilder();
      String params = getParamString(lambdaParams);
      sb.append(params);
      if (params.charAt(params.length()-1) != ' ')
         sb.append(" ");
      sb.append("-> ");
      sb.append(lambdaBody == null ? "{}" : lambdaBody.toLanguageString());
      return sb.toString();
   }

   public Object findMember(String memberName, EnumSet<MemberType> type, Object fromChild, Object refType, TypeContext ctx, boolean skipIfaces) {
      if (inferredType != null) {
         if (newExpr == null)
            initNewExpression();
         if (parameters != null) {
            Object v;
            if (type.contains(MemberType.Variable)) {
               for (Parameter p : parameters.getParameterList())
                  if ((v = p.definesMember(memberName, type, refType, ctx, skipIfaces, false)) != null)
                     return v;
            }
         }
      }
      return super.findMember(memberName, type, this, refType, ctx, skipIfaces);
   }
}
