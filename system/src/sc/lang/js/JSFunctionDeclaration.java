package sc.lang.js;

import sc.lang.java.*;
import sc.parser.IString;

import java.util.List;
import java.util.Set;

/** The semantic node for each Javascript function statement */
public class JSFunctionDeclaration extends Expression {
   public String name;
   public List<IString> parameterNames;
   public BlockStatement body;

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("function ");
      if (name != null)
         sb.append(name);
      sb.append("(");
      sb.append(parameterNames);
      sb.append(")");
      return sb.toString();
   }

   @Override
   public boolean isStaticTarget() {
      return true;
   }

   @Override
   public Object getTypeDeclaration() {
      return null;
   }

   @Override
   public Object eval(Class expectedType, ExecutionContext ctx) {
      return null;
   }

   @Override
   public void refreshBoundTypes(int flags) {

   }

   @Override
   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
   }

   public void setAccessTimeForRefs(long time) {
   }

   @Override
   public Statement transformToJS() {
      return null;
   }

   public String toGenerateString() {
      StringBuilder sb = new StringBuilder();
      sb.append("function");
      if (name != null) {
         sb.append(" ");
         sb.append(name);
      }
      sb.append("(");
      if (parameterNames != null) {
         boolean first = true;
         for (IString pname:parameterNames) {
            if (!first)
               sb.append(", ");
            else
               first = false;
            sb.append(pname);
         }
      }
      sb.append(")");

      if (body != null) {
         body.validateParseNode(false);
         sb.append(body.toLanguageString(JSLanguage.getJSLanguage().block));
      }
      return sb.toString();
   }
}
