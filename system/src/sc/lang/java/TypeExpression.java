/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ILanguageModel;
import sc.parser.ParseUtil;

import java.util.Set;

public class TypeExpression extends Statement {
   public String typeIdentifier;

   public transient JavaType type;
   public transient IdentifierExpression expression;

   public void start() {
      super.start();
      if (findType(typeIdentifier) != null || getJavaModel().findTypeDeclaration(typeIdentifier, true) != null) {
         type = ClassType.create(typeIdentifier);
         type.setParentNode(parentNode);
         ParseUtil.realInitAndStartComponent(type);
      }
      else {
         expression = IdentifierExpression.create(typeIdentifier);
         expression.setParentNode(parentNode);
         ParseUtil.realInitAndStartComponent(expression);
      }
   }

   public void validate() {
      super.validate();
      if (type != null)
         type.validate();
      if (expression != null)
         expression.validate();
   }

   public void process() {
      super.process();
      if (type != null)
         type.process();
      if (expression != null)
         expression.process();
   }

   public boolean transform(ILanguageModel.RuntimeType runtime) {
      boolean res = super.transform(runtime);
      if (type != null)
         res |= type.transform(runtime);
      if (expression != null)
         res |= expression.transform(runtime);
      return res;
   }

   @Override
   public boolean refreshBoundTypes(int flags) {
      boolean res = false;
      if (type != null)
         if (type.refreshBoundType(flags))
            res = true;
      if (expression != null)
         if (expression.refreshBoundTypes(flags))
            res = true;
      return res;
   }

   @Override
   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      if (type != null)
         type.addDependentTypes(types, mode);
      if (expression != null)
         expression.addDependentTypes(types, mode);
   }

   @Override
   public void setAccessTimeForRefs(long time) {
      if (type != null)
         type.setAccessTimeForRefs(time);
      if (expression != null)
         expression.setAccessTimeForRefs(time);
   }

   public Statement transformToJS() {
      Statement res = null;
      if (type != null) {
         type.transformToJS();
         res = this;
      }
      if (expression != null)
         res = expression.transformToJS();
      return res;
   }

   public Object resolveReference() {
      if (!started)
         ParseUtil.realInitAndStartComponent(this);
      return type != null ? type : expression;
   }

   public String toGenerateString() {
      return typeIdentifier;
   }
}
