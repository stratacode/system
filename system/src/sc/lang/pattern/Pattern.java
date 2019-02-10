/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.pattern;

import sc.dyn.DynUtil;
import sc.lang.PatternLanguage;
import sc.lang.SemanticNode;
import sc.lang.SemanticNodeList;
import sc.lang.html.Option;
import sc.lang.java.BodyTypeDeclaration;
import sc.lang.java.ModelUtil;
import sc.parser.*;
import sc.util.URLUtil;

import java.util.ArrayList;
import java.util.Map;

public class Pattern extends SemanticNode {
   // String, PatternVariable, or OptionalPattern
   public SemanticNodeList<Object> elements;

   private transient Parselet parselet = null;
   private transient Language language = null;

   /**
    * Initializes a pattern string written in the PatternLanguage.  Returns either a Pattern object or a ParseError if the pattern string is not valid
    * This variant uses the URLPatternLanguage to provide a known set of data types.
    */
   public static Pattern initURLPattern(Object pageType, String pattern) {
      Object res = initPattern(URLPatternLanguage.getURLPatternLanguage(), pageType, pattern);
      if (res instanceof ParseError) {
         throw new IllegalArgumentException("*** Failed to parse URL pattern: " + pattern + " in: " + pageType);
      }
      Pattern pat = (Pattern) res;
      pat.init();
      return pat;
   }

   /**
    * Initializes a pattern string written in the PatternLanguage.  Returns either a Pattern object or a ParseError if the pattern string is not valid
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
                  // Replace \, brace, etc. with open-brace in the symbol string
                  if (elemStr.charAt(i) == '\\') {
                     if (i < elemStrLen - 1 && elemStr.charAt(i+1) != '\\') {
                        elemStr = elemStr.substring(0, i) + elemStr.substring(i+1);
                        elemStrLen = elemStr.length();
                     }
                  }
               }
               parselets.add(new Symbol(elemStr));
            }
            else if (elem instanceof PatternVariable) {
               PatternVariable varDef = (PatternVariable) elem;
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

   /**
    * The internal routine that implements the match for a given pattern.
    * Returns null for no match - empty string for an optional match that did not match.  We could implement this using the getParselet for the
    * server but want to have one set of logic we share between client and server and don't want to require Parselets just for URL pattern matching.
    */
   String match(String fromStr, Object inst) {
      int len = 0;
      String matchStr = fromStr;
      for (Object elem:elements) {
         if (elem instanceof String) {
            String elemStr = (String) elem;
            if (matchStr.startsWith(elemStr)) {
               int strLen = elemStr.length();
               matchStr = matchStr.substring(strLen);
               len += strLen;
            }
            else {
               return null;
            }
         }
         else if (elem instanceof Pattern) {
            Pattern pattern = (Pattern) elem;
            String subMatch = pattern.match(matchStr, inst);
            if (subMatch == null)
               return null;
            else {
               int subLen = subMatch.length();
               if (subLen != 0) {
                  matchStr = matchStr.substring(subLen);
                  len += subLen;
               }
            }
         }
         else if (elem instanceof PatternVariable) {
            PatternVariable patVar = (PatternVariable) elem;
            String typeName = patVar.parseletName;
            String propName = patVar.propertyName;
            Object propVal = null;
            int matchLen = matchStr.length();
            try {
               if (typeName.equals("integer") || typeName.equals("integerLiteral")) {
                  int intLen;
                  for (intLen = 0; intLen < matchLen && Character.isDigit(matchStr.charAt(intLen)); intLen++) {
                  }
                  if (intLen == 0)
                     return null;
                  String intStr = matchStr.substring(0, intLen);
                  try {
                     int intVal = Integer.parseInt(intStr);
                     propVal = intVal;
                     if (inst != null) {
                        if (propName != null) {
                           DynUtil.setProperty(inst, propName, intVal);
                        }
                     }
                  }
                  catch (NumberFormatException exc) {
                     return null;
                  }
               }
               else if (typeName.equals("urlString") || typeName.equals("identifier")) {
                  int strLen = 0;
                  while (strLen < matchLen) {
                     char c = matchStr.charAt(strLen);
                     boolean isFirst = strLen == 0;

                     if (typeName.equals("urlString")) {
                        if (!URLUtil.isURLCharacter(c))
                           break;
                     }
                     else if (typeName.equals("identifier")) {
                        if (isFirst) {
                           if (!Character.isJavaIdentifierStart(c))
                              break;
                        }
                        else if (!Character.isJavaIdentifierPart(c))
                           break;
                     }
                     strLen++;
                  }
                  if (strLen == 0)
                     return null;
                  String strVal = matchStr.substring(0, strLen);
                  propVal = strVal;
                  if (inst != null) {
                     DynUtil.setProperty(inst, propName, strVal);
                  }
                  matchStr = matchStr.substring(strLen);
               }
               else {
                  System.err.println("*** Unrecognized pattern name: " + typeName);
               }
            }
            catch (IllegalArgumentException exc) {
               System.err.println("*** Failed to set pattern property: " + inst + "." + propName + " = " + propVal);
               return null;
            }
         }
      }
      if (len == fromStr.length())
         return fromStr;
      else
         return fromStr.substring(0, len);
   }

   public boolean matchString(String fromStr) {
      String matchStr = match(fromStr, null);
      // Should be a match with nothing left over
      return matchStr != null && matchStr.length() == fromStr.length();
   }

   public boolean updateInstance(String fromStr, Object inst) {
      if (matchString(fromStr)) {
         Object res = match(fromStr, inst);
         return res != null;
      }
      return false;
   }

   public boolean isPatternValidWithInst(Map<String,Object> otherProps, Object inst) {
      return evalPatternWithInst(otherProps, inst) != null;
   }

   public String evalPatternWithInst(Map<String,Object> otherProps, Object inst) {
      StringBuilder sb = new StringBuilder();
      for (Object elem:elements) {
         if (PString.isString(elem))
            sb.append(elem);
         else if (elem instanceof OptionalPattern) {
            OptionalPattern pat = (OptionalPattern) elem;
            String optStr = pat.evalPatternWithInst(otherProps, inst);
            if (optStr != null)
               sb.append(optStr);
         }
         else if (elem instanceof PatternVariable) {
            PatternVariable patVar = (PatternVariable) elem;
            String propName = patVar.propertyName;
            try {
               // The pattern is not defined because some property is not defined or we do not have an instance
               Object propVal = otherProps == null ? null : otherProps.get(propName);
               propVal = propVal == null && inst != null ? DynUtil.getProperty(inst, propName) : propVal;
               if (propVal == null)
                  return null;
               // TODO: are there any cases where we need to do something other than toString here?
               //sb.append(propName);
               //sb.append('=');
               sb.append(propVal.toString());
            }
            catch (IllegalArgumentException exc) {
               System.err.println("*** Failed to get property: " + inst + "." + propName + " for pattern: " + this);
               return null;
            }
         }
      }
      return sb.toString();
   }

   public boolean isSimplePattern() {
      return elements.size() == 1 && elements.get(0) instanceof String;
   }
}
