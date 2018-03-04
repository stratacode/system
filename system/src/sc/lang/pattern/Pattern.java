/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.pattern;

import sc.dyn.DynUtil;
import sc.lang.PatternLanguage;
import sc.lang.SemanticNode;
import sc.lang.SemanticNodeList;
import sc.lang.java.BodyTypeDeclaration;
import sc.lang.java.ModelUtil;
import sc.parser.*;

import java.util.ArrayList;

public class Pattern extends SemanticNode {
   // String, VariableDef, or OptionalPattern objects
   SemanticNodeList<Object> elements;

   private transient Parselet parselet = null;
   private transient Language language = null;

   /**
    * Initializes a pattern string written in the PatternLanguage.  Returns either a Pattern object or a ParseError if the pattern does not match.
    * The supplied pattern can use parselets from a second language to parse a chunk in the pattern (e.g. {integerLiteral}, or {identifier} if you supply the JavaLanguage)
    * or the pattern can be matched to properties of the pageType (e.g. {blogId=integerLiteral}).
    */
   public static Object initPattern(Language language, Object pageType, String pattern) {
      Object res = PatternLanguage.getPatternLanguage().parseString(pattern);
      if (res instanceof ParseError)
         return res;
      res = ParseUtil.nodeToSemanticValue(res);
      if (!(res instanceof Pattern))
         return res;
      // Need to set the system class loader so we can find the user defined model class in the pattern in case there are properties to set
      if (pageType instanceof BodyTypeDeclaration)
         language.classLoader = ((BodyTypeDeclaration) pageType).getLayeredSystem().getSysClassLoader();
      else if (pageType != null)
         language.classLoader = ((Class) pageType).getClassLoader();
      return res;
   }

   public static Object initPatternParselet(Language language, Object pageType, String pattern) {
      Object pt = initPattern(language, pageType, pattern);
      if (pt instanceof ParseError) {
         System.err.println("*** Failed to init pattern: " + pattern + " parse error: " + pt);
         return pt;
      }
      return ((Pattern) pt).getParselet(language, pageType);
   }

   public static Parselet getPattern(Language language, Object pageType, String pattern) {
      Object res = initPatternParselet(language, pageType, pattern);
      if (res instanceof ParseError) {
         throw new IllegalArgumentException("Error parsing pattern string: " + res.toString());
      }
      return (Parselet) res;
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
               String elemStr = elem.toString();
               int elemStrLen = elemStr.length();
               for (int i = 0; i < elemStrLen; i++) {
                  // Replace \{, etc. with { in the symbol string
                  if (elemStr.charAt(i) == '\\') {
                     if (i < elemStrLen - 1 && elemStr.charAt(i+1) != '\\') {
                        elemStr = elemStr.substring(0, i) + elemStr.substring(i+1);
                        elemStrLen = elemStr.length();
                     }
                  }
               }
               parselets.add(new Symbol(elemStr));
            }
            else if (elem instanceof VariableDef) {
               VariableDef varDef = (VariableDef) elem;
               if (varDef.propertyName != null)
                  descriptor.append(varDef.propertyName);
               Parselet patternParselet = language.getParselet("<" + varDef.parseletName + ">");
               if (patternParselet == null)
                  throw new IllegalArgumentException("Pattern: " + this + " referenced parselet: " + varDef.parseletName + " which does not exist in language: " + language);
               else
                  parselets.add((Parselet) patternParselet.clone());
            }
            else if (elem instanceof OptionalPattern) {
               Parselet optSubPattern = ((OptionalPattern) elem).getParselet(language, pageType);
               optSubPattern.optional = true;
               parselets.add(optSubPattern);
               // If there are any variables in the optSubPattern, this will cause them to be applied on the same instance.
               descriptor.append("*");
            }
            else {
               System.err.println("*** Unexpected element type in Pattern elements: " + elem);
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
