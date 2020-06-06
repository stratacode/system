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
import sc.util.URLUtil;

import java.util.ArrayList;
import java.util.Map;

/**
 * A basic pattern matching algorithm that also supports populating named variables in an instance and
 * replacing a matched variable with a basic name for making it possible to filter log files, removing variables for
 * 'diff' comparison of test logs.
 * Use {parseletName} to parse/match a token of a specific type. Use {propName=parseletName} to define a variable that
 * can either be parsed and used to set propName on the provided instance. Or use the 'replace' mode to replace the parsed
 * value with {id=parseletName} (to say replace an id that changes from run to run with the word 'id')
 * See the PatternLanguage for details on the syntax used to generate a Pattern.
 * Use the method 'match', 'matchString', 'evalPatternWithInst' and 'replaceString' to use the pattern in different ways.
 */
public class Pattern extends SemanticNode {
   // String, PatternVariable, or OptionalPattern
   public SemanticNodeList<Object> elements;
   public String optionSymbols;

   public transient boolean negated;
   public transient boolean repeat;

   private transient Parselet parselet = null;
   private transient Language language = null;

   /** Flag to enable comparison of the two ways of matching a pattern - with and without parselets */
   public static boolean testMode = false;

   public void init() {
      super.init();
      if (optionSymbols != null) {
         repeat = optionSymbols.contains("*");
         negated = optionSymbols.contains("!");
      }
      if (elements != null) {
         for (int i = 0; i < elements.size(); i++) {
            Object elem = elements.get(i);
            if (PString.isString(elem))
               elements.set(i, PatternLanguage.unescapePatternToken(elem.toString()));
         }
      }
   }

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
      Pattern patternRes = (Pattern) res;
      // ignoring the result - just because this initializes the parslet and language
      patternRes.getParselet(language, pageType);
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
               /*
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
               */
               Symbol sym = new Symbol(negated ? IParserConstants.NOT : 0, PatternLanguage.unescapePatternToken(elem.toString()));
               parselets.add(sym);
            }
            else if (elem instanceof PatternVariable) {
               PatternVariable varDef = (PatternVariable) elem;
               if (varDef.propertyName != null)
                  descriptor.append(varDef.propertyName);
               Parselet patternParselet = language.getParselet("<" + varDef.parseletName + ">");
               if (patternParselet == null)
                  throw new IllegalArgumentException("Pattern: " + this + " referenced parselet: " + varDef.parseletName + " which does not exist in language: " + language);
               else {
                  Parselet newParselet = (Parselet) patternParselet.clone();
                  if (negated)
                     newParselet.negated = true;
                  parselets.add(newParselet);
               }
            }
            else if (elem instanceof Pattern) {
               Parselet subPattern = ((Pattern) elem).getParselet(language, pageType);
               if (elem instanceof OptionalPattern)
                  subPattern.optional = true;
               if (negated)
                  subPattern.negated = true;
               if (repeat)
                  subPattern.repeat = true;
               parselets.add(subPattern);
               // If we are given a page type and there are any variables in the subPattern, this will cause them to be
               // propagated from the sub-pattern to the instance provided with the top-level pattern.
               if (pageType != null)
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

   public boolean matchString(String fromStr) {
      ReplaceResult res = doMatch(fromStr, false, null);
      if (res == null)
         return false;
      // Should be a match with nothing left over
      return res.matchedLen == fromStr.length();
   }

   // TODO: this does the match via the parselet which can use any parselet defined in the language. For the URLs we moved to
   // specific parselets so we could implement a simple parsing version in Javascript, rather than using the Pattern object - not sure if it's different or not.
   public boolean matchSimpleString(String fromStr) {
      if (language == null)
         language = parselet.getLanguage();
      boolean res = language.matchString(fromStr, parselet);
      if (testMode) {
         boolean matchRes = matchString(fromStr);
         if (res != matchRes)
            System.err.println("*** Pattern.testMode error - matchString method and parselet pattern match are not the same for: " + this);
      }
      return res;
   }

   public boolean updateInstance(String fromStr, Object inst) {
      if (matchString(fromStr)) {
         ReplaceResult matchRes = doMatch(fromStr, false, inst);
         // This should return true because we did the simple match first - unless we fail to set the instance for some reason
         boolean res = matchRes != null && matchRes.matchedLen == fromStr.length();
         if (!res)
            System.err.println("*** Pattern.updateInstance returns false for a matching string");
         return res;
      }
      return false;
   }

   public boolean isPatternValidWithInst(Map<String,Object> otherProps, Object inst) {
      return evalPatternWithInst(otherProps, inst) != null;
   }

   /**
    * Returns a string for this pattern.  If inst is not null, it's used as a source for
    * any PatternVariables which map onto properties of the instance. If otherProps is not
    * null, that map is consulted before the instance is checked (so you can create a new URL
    * from the existing instance but replace one or more properties in the new URL)
    *
    * If a required PatternVariable does not have a value, null is returned - which means
    * this URL is not valid in this context.
    *
    * TODO: needs to be updated to handle repeat and negated patterns
    */
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

   public String replaceString(String fromStr) {
      StringBuilder cres = new StringBuilder();
      int matchedLen = 0;
      int fromLen = fromStr.length();
      String curStr = fromStr;
      while (matchedLen < fromLen) {
         ReplaceResult res = doMatch(curStr, true, null);
         if (res == null || res.matchedLen == 0) {
            matchedLen++;
            cres.append(curStr.charAt(0));
            curStr = curStr.substring(1);
         }
         else {
            cres.append(res.result);
            matchedLen += res.matchedLen;
            if (matchedLen < fromLen) {
               curStr = fromStr.substring(matchedLen);
            }
         }
      }
      return cres.toString();
   }

   /**
    * Similar to match above but if there's a match, we'll return a string which replaces any named
    * pattern variables with the variable name. This is used by the TestLogFilter, which can take a line in the
    * log file and replace matched values with the data type name matched - when those values are not consistent
    * from one run to the other.
    */
   public ReplaceResult doMatch(String fromStr, boolean doReplace, Object inst) {
      int len = 0;
      String matchStr = fromStr;
      StringBuilder res = new StringBuilder();
      boolean repeatMatch = true;
      int numElems = elements.size();
      StringBuilder pendingNeg = new StringBuilder();
      int pendingNegLen = 0;
      while (repeatMatch) {
         repeatMatch = repeat;
         StringBuilder nextRes = new StringBuilder();
         int nextLen = 0;
         int ix;
         for (ix = 0; ix < numElems; ix++) {
            Object elem = elements.get(ix);
            if (PString.isString(elem)) {
               String elemStr = elem.toString();
               if (matchStr.startsWith(elemStr)) {
                  if (negated) {
                     int strLen = elemStr.length();
                     pendingNeg.append(elemStr);
                     pendingNegLen += strLen;
                     matchStr = matchStr.substring(strLen);
                  }
                  else {
                     nextRes.append(elemStr);
                     int strLen = elemStr.length();
                     nextLen += strLen;
                     matchStr = matchStr.substring(strLen);
                  }
               }
               else {
                  if (negated) {
                     if (pendingNeg.length() > 0) {
                        nextRes.append(pendingNeg);
                        nextLen += pendingNegLen;
                        pendingNeg = new StringBuilder();
                        pendingNegLen = 0;
                     }
                     if (matchStr.length() > 0) {
                        nextRes.append(matchStr.charAt(0));
                        nextLen++;
                        matchStr = matchStr.substring(1);
                        repeatMatch = true;
                        ix = numElems;
                     }
                  }
                  else {
                     if (len == 0) {
                        return null;
                     }
                  }
                  break;
               }
            }
            else if (elem instanceof Pattern) {
               Pattern pattern = (Pattern) elem;
               ReplaceResult subMatch = pattern.doMatch(matchStr, doReplace, inst);
               if (subMatch == null) {
                  if (negated) {
                     nextRes.append(pendingNeg);
                     nextLen += pendingNegLen;
                     pendingNeg = new StringBuilder();
                     pendingNegLen = 0;
                     if (matchStr.length() > 0) {
                        nextRes.append(matchStr.charAt(0));
                        nextLen++;
                        matchStr = matchStr.substring(1);
                        repeatMatch = true;
                        ix = numElems;
                        break;
                     }
                  }
                  else {
                     if (len == 0)
                        return null;
                     if (!repeatMatch)
                        return null;
                     break;
                  }
               }
               else {
                  int subLen = subMatch.result.length();
                  if (negated) {
                     pendingNeg.append(subMatch);
                     pendingNegLen += subMatch.matchedLen;
                     matchStr = matchStr.substring(subMatch.matchedLen);
                  }
                  else {
                     if (subLen != 0) {
                        nextRes.append(subMatch);
                        nextLen += subMatch.matchedLen;
                        matchStr = matchStr.substring(subMatch.matchedLen);
                     }
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
                  if (typeName.equals("integer") || typeName.equals("integerLiteral") || typeName.equals("digits")) {
                     int intLen;
                     for (intLen = 0; intLen < matchLen && Character.isDigit(matchStr.charAt(intLen)); intLen++) {
                     }

                     boolean intMatched = true;
                     if (intLen == 0) {
                        if (negated) {
                           nextRes.append(matchStr.charAt(0));
                           nextLen++;
                           matchStr = matchStr.substring(1);
                           intMatched = false;
                           ix = numElems;
                        }
                        else
                           return null;
                     }
                     if (intMatched) {
                        String intStr = matchStr.substring(0, intLen);
                        try {
                           int intVal = Integer.parseInt(intStr); // validate that this string is an integer
                           // TODO: should we add an option to return the values?
                           if (propName != null && inst != null) {
                              DynUtil.setProperty(inst, propName, intVal);
                           }
                        }
                        catch (NumberFormatException exc) {
                           if (negated) {
                              nextRes.append(matchStr.charAt(0));
                              nextLen++;
                              matchStr = matchStr.substring(1);
                              intMatched = false;
                           }
                           else
                              return null;
                        }
                        // TODO: catch IllegalArgumentException here and log an error?
                        if (intMatched) {
                           if (negated) {
                              if (propName == null) {
                                 pendingNeg.append(intStr);
                              }
                              else {
                                 if (doReplace)
                                    appendSubstitute(pendingNeg, propName);
                              }
                              pendingNegLen += intLen;
                           }
                           else {
                              if (propName == null) {
                                 nextRes.append(intStr);
                              }
                              else {
                                 if (doReplace)
                                    appendSubstitute(nextRes, propName);
                              }
                              nextLen += intLen;
                           }
                           matchStr = matchStr.substring(intLen);
                        }
                     }
                  }
                  else if (typeName.equals("urlString") || typeName.equals("identifier") || typeName.equals("alphaNumString") || typeName.equals("escapedString")) {
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
                        else if (typeName.equals("alphaNumString")) {
                           if (!Character.isAlphabetic(c) && !Character.isDigit(c))
                              break;
                        }
                        else if (typeName.equals("escapedString")) {
                           if (c == '"' || c == 0 || c == '\n')
                              break;
                        }
                        strLen++;
                     }
                     if (strLen == 0) {
                        if (negated) {
                           if (matchStr.length() > 0) {
                              int addLen = 1;
                              nextRes.append(matchStr.substring(0, addLen));
                              nextLen += addLen;
                              matchStr = matchStr.substring(addLen);
                           }
                           ix = numElems;
                           repeatMatch = true;
                           break;
                        }
                        else {
                           return null;
                        }
                     }
                     else {
                        String strVal = matchStr.substring(0, strLen);
                        propVal = strVal;

                        if (negated) {
                           if (propName == null)
                              pendingNeg.append(strVal);
                           else {
                              appendSubstitute(pendingNeg, propName);
                           }
                           pendingNegLen += strLen;
                        }
                        else {
                           if (propName == null)
                              nextRes.append(strVal);
                           else {
                              if (doReplace)
                                 appendSubstitute(nextRes, propName);
                              else {
                                 if (inst != null) {
                                    DynUtil.setProperty(inst, propName, strVal);
                                 }
                              }
                           }
                           nextLen += strLen;
                        }
                        matchStr = matchStr.substring(strLen);
                     }
                  }
                  else if (typeName.equals("whiteSpace")) {
                     int strLen = 0;
                     while (strLen < matchLen) {
                        char c = matchStr.charAt(strLen);
                        if (!Character.isWhitespace(c))
                           break;
                        strLen++;
                     }
                     if (strLen > 0) {
                        if (negated) {
                           pendingNeg.append(matchStr.substring(0, strLen));
                           pendingNegLen += strLen;
                        }
                        else {
                           nextRes.append(matchStr.substring(0, strLen));
                           nextLen += strLen;
                        }
                        matchStr = matchStr.substring(strLen);
                     }
                  }
                  else if (typeName.equals("quoteChar")) {
                     char c = matchStr.charAt(0);
                     if (c == '"' || c == '\'') {
                        if (negated) {
                           pendingNeg.append(c);
                           pendingNegLen++;
                           matchStr = matchStr.substring(1);
                           repeatMatch = false;
                        }
                        else {
                           matchStr = matchStr.substring(1);
                           nextRes.append(c);
                           nextLen++;
                        }
                     }
                     else {
                        if (negated) {
                           nextRes.append(pendingNeg);
                           nextLen += pendingNegLen;
                           pendingNeg = new StringBuilder();
                           pendingNegLen = 0;
                           nextRes.append(matchStr.charAt(0));
                           nextLen++;
                           matchStr = matchStr.substring(1);
                           ix = numElems;
                           repeatMatch = true;
                           break;
                        }
                        else {
                           if (len == 0)
                              return null;
                           return new ReplaceResult(res.toString(), len);
                        }
                     }
                  }
                  else {
                     System.err.println("*** Unrecognized pattern name: " + typeName);
                  }
               }
               catch (IllegalArgumentException exc) {
                  System.err.println("*** Failed to replace pattern property: " + propName + " = " + propVal);
                  return null;
               }
            }
         }
         // Only append this this iteration if we matched all elements in the pattern
         if (ix == numElems) {
            if (nextLen == 0) {
               repeatMatch = false;
               break;
            }
            res.append(nextRes);
            len += nextLen;
         }

         if (matchStr.length() == 0) {
            break;
         }
      }
      return new ReplaceResult(res.toString(), len);
   }

   private void appendSubstitute(StringBuilder res, String propName) {
      res.append("{");
      res.append(propName);
      res.append("}");
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      if (repeat)
         sb.append("*");
      if (negated)
         sb.append("!");
      if (elements == null)
         sb.append("--- no elements in pattern ---");
      else {
         if (this instanceof OptionalPattern)
            sb.append("[");
         else
            sb.append("(");
         for (Object elem:elements) {
            sb.append(elem.toString());
         }
         if (this instanceof OptionalPattern)
            sb.append("]");
         else
            sb.append(")");
      }
      return sb.toString();
   }
}
