/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ISemanticNode;
import sc.lang.SCLanguage;
import sc.lang.SemanticNodeList;
import sc.parser.ParseUtil;

import java.util.List;

public class ConstructorPropInfo {
   String typeName;
   public List<String> propNames;

   public List<JavaType> propJavaTypes;
   public List<Object> propTypes;
   public SemanticNodeList<Statement> initStatements;

   public ConstructorDefinition constr;

   String getMethodName = null;

   public String getBeforeNewObject() {
      if (initStatements == null || initStatements.size() == 0)
         return "";

      return ParseUtil.toLanguageString(SCLanguage.INSTANCE.blockStatements, initStatements);
   }

   public ConstructorPropInfo copy() {
      ConstructorPropInfo cpi = new ConstructorPropInfo();
      cpi.propNames = propNames;
      cpi.propJavaTypes = propJavaTypes;
      cpi.propTypes = propTypes;
      cpi.initStatements = (SemanticNodeList<Statement>) initStatements.deepCopy(ISemanticNode.CopyNormal, null);
      if (constr != null) {
         cpi.constr = (ConstructorDefinition) constr.deepCopy(ISemanticNode.CopyNormal, null);
         cpi.constr.fromStatement = null;
      }
      return cpi;
   }

   /** For top-level page objects we want to inject property values from the URL. We do this by adding a wrapper expression around
    * the initializer as set by the constructorPropGet */
   public Expression wrapInitExpr(String propName, Expression expr) {
      if (getMethodName != null && getMethodName.length() > 0) {
         SemanticNodeList<Expression> args = new SemanticNodeList<Expression>();
         args.add(StringLiteral.create(typeName));
         args.add(StringLiteral.create(propName));
         args.add(expr);
         IdentifierExpression getMethod = IdentifierExpression.createMethodCall(args, getMethodName);
         JavaType propType = getJavaTypeForProp(propName);
         CastExpression cast = CastExpression.create(propType, getMethod);
         return cast;
      }
      return expr;
   }

   private JavaType getJavaTypeForProp(String propName) {
      for (int i = 0; i < propNames.size(); i++)
         if (propNames.get(i).equals(propName))
            return propJavaTypes.get(i);
      return null;
   }

   public String getSignature() {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < propJavaTypes.size(); i++) {
         sb.append(propJavaTypes.get(i).getSignature(true));
      }
      return sb.toString();
   }
}
