/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.pattern;

import sc.lang.PatternLanguage;
import sc.lang.SemanticNode;
import sc.lang.SemanticNodeList;
import sc.lang.java.BodyTypeDeclaration;
import sc.lang.java.ModelUtil;
import sc.parser.*;

import java.util.ArrayList;

public class Pattern extends SemanticNode {
   // String or VariableDef object
   SemanticNodeList<Object> elements;

   private transient Parselet parselet = null;
   private transient Language language = null;

   /** Initializes a pattern string written in the PatternLanguage.
    * The pattern is applied to a second language and returns a Parselet
    * you can use to parse that string.
    */
   public static Object initPattern(Language language, Object pageType, String pattern) {
      if (pageType == null) {
         System.err.println("Null page type to initPattern");
         return null;
      }
      Object res = PatternLanguage.getPatternLanguage().parseString(pattern);
      if (res instanceof ParseError)
         return res;
      res = ParseUtil.nodeToSemanticValue(res);
      if (!(res instanceof Pattern))
         return res;
      // Need to set the system class loader so we can find the user defined model class in the pattern in case there are properties to set
      if (pageType instanceof BodyTypeDeclaration)
         language.classLoader = ((BodyTypeDeclaration) pageType).getLayeredSystem().getSysClassLoader();
      else
         language.classLoader = ((Class) pageType).getClassLoader();
      return ((Pattern) res).getParselet(language, pageType);
   }

   public Parselet getParselet(Language language, Object pageType) {
      if (!initialized);
         init();
      if (this.language != language)
         parselet = null;
      if (parselet == null) {
         StringBuilder descriptor = new StringBuilder();
         ArrayList<Parselet> parselets = new ArrayList<Parselet>();

         if (pageType != null)
            descriptor.append(ModelUtil.getTypeName(pageType));

         descriptor.append("(");
         boolean first = true;
         for (Object elem:elements) {
            if (!first)
               descriptor.append(",");
            first = false;
            if (PString.isString(elem)) {
               parselets.add(new Symbol(elem.toString()));
            }
            else {
               VariableDef varDef = (VariableDef) elem;
               if (varDef.propertyName != null)
                  descriptor.append(varDef.propertyName);
               parselets.add((Parselet) language.getParselet(varDef.parseletName).clone());
            }
         }
         descriptor.append(")");
         this.language = language;

         parselet = new Sequence(descriptor.toString(), parselets.toArray(new Parselet[parselets.size()]));
         if (pageType instanceof BodyTypeDeclaration)
            parselet.resultDynType = (BodyTypeDeclaration) pageType;
         parselet.setLanguage(language);
      }
      return parselet;
   }
}
