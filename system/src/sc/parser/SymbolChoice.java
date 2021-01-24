/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.ISemanticNode;

import java.util.*;

public class SymbolChoice extends Parselet {
   private HashSet<IString> expectedValues = new HashSet<IString>();
   // Optional set of symbols that include the expectedValues but should not cause a match - e.g. %> for TemplateLanguage to not match the % for the modulo operator
   private HashSet<IString> excludedValues = null;
   HashMap<IString,List<IString>> valueIndex = new HashMap<IString,List<IString>>();

   // index for excludedValues - foreach excluded symbol - e.g. %> this table stores remaining string to exclude - here >
   private HashMap<IString,ArrayList<ArrString>> excludedPeekString = null;

   // Specifies the number of characters to use as an index for choosing the choice
   public int keySize = -1;

   /**
    * When you have a SymbolChoice that is able to choose from it's values during the generate phase (e.g. the
    * newline at the end of a CSV row), set this to the value to use
    */
   public String defaultGenerateValue = null;

   // Match the EOF input
   private boolean matchEOF = false;

   // Match any character in the stream (or do not match if negated is true).
   private boolean matchANY = false;

   // Optimize the case where each pattern is only a single character
   private boolean allSingleCharPatterns = true;

   private char[] expectedChars;

   public SymbolChoice() {
      super(0);
   }

   public SymbolChoice(int options, String... expectedValues) {
      super(options);
      // Since we return strings, it should appear to be a scalar value, not an array even if repeat is on
      if ((options & REPEAT) != 0)
         treatValueAsScalar = true;
      for (String p: expectedValues)
         addExpectedValue(p);
   }

   public SymbolChoice(String... expectedValues) {
      for (String p: expectedValues)
         addExpectedValue(p);
   }

   public SymbolChoice(Character... expectedValues) {
      for (Character p: expectedValues)
         addExpectedValue(String.valueOf(p));
   }

   public void addExpectedValue(String v) {
      if (initialized) {
         System.out.println("warning: adding values to an initialized SymbolChoice");
         initialized = false;
      }
      if (allSingleCharPatterns && (v == null || v.length() != 1))
         allSingleCharPatterns = false;
      expectedValues.add(PString.toIString(v));
   }

   public void setExpectedValues(String [] values) {
      expectedValues.clear();
      addExpectedValues(values);
   }

   public void removeExpectedValue(CharSequence v) {
      if (!expectedValues.remove(v)) {
         // The hashcode on PString is different than String to we need to convert to be sure to really remove it
         if (!expectedValues.remove(PString.toIString(v)))
            System.err.println("*** Failed to remove expected value!");
      }
   }

   public void addExpectedValues(String [] values) {
      List<IString> l =  Arrays.asList(PString.toPString(values));
      if (allSingleCharPatterns) {
         for (int i = 0; i < l.size(); i++) {
            IString s = l.get(i);
            if (s != null && s.length() != 1)
               allSingleCharPatterns = false;
         }
      }
      expectedValues.addAll(l);
   }

   public void addExcludedValues(String... values) {
      if (excludedValues == null)
         excludedValues = new HashSet<IString>();
      for (String val:values)
         excludedValues.add(PString.toIString(val));
   }

   public Class getSemanticValueClass() {
      return IString.class;
   }

   public void set(String...values) {
      clear();
      add(values);
   }

   public void add(String...values) {
      for (String s:values) {
         if (allSingleCharPatterns) {
            if (s != null && s.length() != 1)
               allSingleCharPatterns = false;
         }
         expectedValues.add(PString.toIString(s));
      }
   }

   int maxLen = -1;

   public void init() {
      if (initialized)
         return;
      initialized = true;
      if (expectedValues == null || expectedValues.size() == 0)
         throw new IllegalArgumentException("SymbolChoice defined without expected values");

      int esz = expectedValues.size();
      if (allSingleCharPatterns) {
         expectedChars = new char[esz];
         Iterator<IString> svs = expectedValues.iterator();
         for (int i = 0; i < esz; i++)
            expectedChars[i] = svs.next().charAt(0);

         if (ignoreCase) {
            for (int i = 0; i < esz; i++)
               expectedChars[i] = Character.toLowerCase(expectedChars[i]);
         }
      }

      if (keySize == -1) {
         keySize = esz < 12 ? 1 : 2;
         ArrayList<IString> toAdd = ignoreCase ? new ArrayList<IString>() : null;
         for (IString val: expectedValues) {
            int valLen;
            if (val == null) {
               keySize = 1;
               matchEOF = true;
               if (!repeat)
                  maxLen = 1;
            }
            else if ((valLen = val.length()) == 0) {
               keySize = 1;
               matchANY = true;
               if (!repeat)
                  maxLen = 1;
            }
            else if (valLen < keySize) {
               keySize = val.length();
            }

            if (toAdd != null && val != null) {
               IString lower = PString.toIString(val.toString().toLowerCase());
               if (!lower.equals(val))
                  toAdd.add(lower);
            }
         }
         if (toAdd != null)
            expectedValues.addAll(toAdd);
      }

      if (keySize <= 0)
         throw new IllegalArgumentException("Invalid keySize for: " + this);

      for (IString val: expectedValues) {
         if (val != null) {
            int newLen = val.length();
            if (newLen > 0) {
               if (!repeat && newLen > maxLen)
                  maxLen = newLen;

               String valStr = val.toString();
               IString key = new PString(valStr.substring(0, keySize));
               List<IString> l;
               if ((l = valueIndex.get(key)) != null) {
                  int i;
                  // Longest strings first so we get the most specific match
                  for (i = l.size()-1; i >= 0; i--)
                      if (l.get(i).length() > val.length())
                          break;
                  l.add(i+1,val);
               }
               else {
                  l = new ArrayList<IString>(2);
                  l.add(val);
                  valueIndex.put(key, l);
               }
            }
         }
      }
      expectedValues.remove(null); // this one messes up comparisons with strings that have a null char apparently!

      if (excludedValues != null) {
         excludedPeekString = new HashMap<IString,ArrayList<ArrString>>();
         for (IString excludeValue:excludedValues) {
            boolean matched = false;
            if (ignoreCase)
               excludeValue = PString.toIString(excludeValue.toString().toLowerCase());
            for (IString expectedValue:expectedValues) {
               if (excludeValue.startsWith(expectedValue)) {
                  matched = true;
                  IString peekStr = excludeValue.substring(expectedValue.length());
                  ArrayList<ArrString> excludePeekList = excludedPeekString.get(expectedValue);
                  if (excludePeekList == null) {
                     excludePeekList = new ArrayList<ArrString>();
                     excludedPeekString.put(expectedValue, excludePeekList);
                  }
                  excludePeekList.add(ArrString.toArrString(peekStr.toString()));
               }
            }
            if (!matched)
               System.err.println("*** Warning: ignoring excluded value: " + excludeValue + " does not match any expected value: " + expectedValues);
         }
      }
   }

   private boolean stringMatches(Parser parser, StringToken inputStr) {
      boolean matched;
      int origLen = inputStr.len;

      inputStr.startIndex = parser.currentIndex;
      inputStr.len = keySize;
      inputStr.hc = -333; // recompute hash code next time

      // We hit EOF before the smallest string
      if (parser.eof) {
         if (inputStr.charAt(keySize-1) == '\0')
            return matchEOF && !negated || !matchEOF && negated;
      }

      matched = negated;

      List<IString> possibleMatches = valueIndex.get(inputStr);
      if (possibleMatches != null) {
         for (IString m:possibleMatches) {
            inputStr.len = m.length();
            inputStr.hc = -333;

            // Trim chars after EOF off the of the end - it is ok if we match unless we back up
            // to the original string.
            if (parser.eof) {
               while (inputStr.charAt(inputStr.len-1) == '\0') {
                  inputStr.len--;
               }
               inputStr.hc = -333;

               if (inputStr.len <= origLen) {
                  if (expectedValues.contains(inputStr))
                     matched = !negated;
                  else {
                     System.out.println("*** error - questionable case in symbol choice");
                     matched = negated;
                  }
                  break;
               }
            }

            if (expectedValues.contains(inputStr)) {
               matched = !negated;
               break;
            }
            else { // Don't consume more chars
               inputStr.len = keySize;
               inputStr.hc = -333;
            }
         }
      }
      else if (matchANY)
         matched = !negated;
      else if (inputStr.charAt(0) == '\0')
         matched = matchEOF && !negated || !matchEOF && negated;

      return matched;
   }

   private boolean stringMatches(IString inputStr) {
      boolean matched;

      if (ignoreCase)
         inputStr = new ToLowerWrapper(inputStr);

      if (repeat)
        return stringMatchesRepeating(inputStr);

      // TODO: optimize this!
      //if (allSingleCharPatterns) {
      //}

      // Not enough input - just fail as though we did not match
      if (inputStr == null)
         matched = matchEOF;
      else if (inputStr.length() == 0)
         matched = !negated;
      else {
         matched = expectedValues.contains(inputStr) ? !negated : negated;

         /*
         if (inputStr.length() >= keySize)
         {
            IString key = inputStr.substring(0, keySize);

            List<IString> possibleMatches = valueIndex.get(key);
            if (possibleMatches != null)
            {
               for (IString m:possibleMatches)
               {
                  if (expectedValues.contains(inputStr))
                  {
                     matched = !negated;
                     break;
                  }
               }
            }
            else if (matchANY)
               matched = !negated;
         }
         */
      }

      return matched;
   }

   private boolean stringMatchesRepeating(IString inputStr) {
      boolean matched;
      boolean matchedAny = false;
      boolean found;

      do {
         found = false;

         // Not enough input - just fail as though we did not match
         if (inputStr == null) {
            matched = matchEOF;
         }
         else {
            matched = negated;

            if (inputStr.length() >= keySize) {
               IString key = inputStr.substring(0, keySize);

               List<IString> possibleMatches = valueIndex.get(key);
               if (possibleMatches != null) {
                  for (IString m:possibleMatches) {
                     if (inputStr.startsWith(m)) {
                        inputStr = inputStr.substring(m.length());
                        found = true;
                        matchedAny = true;
                        break;
                     }
                  }
                  if (!found)
                     matched = negated;
               }
               else
                  matched = negated;
            }
         }
      } while (found && matched == negated && inputStr.length() > 0);

      if (inputStr.length() == 0 && matchedAny)
          matched = !negated;

      return matched;
   }

   public Object parseAllChars(Parser parser) {
      if (trace)
         System.out.println("*** tracing parse of all char symbol choice");

      StringToken matchedValue = null;

      do {
         char ic = parser.peekInputChar(0);

         if (parser.eof && ic == '\0')
            break;

         int esz = expectedChars.length;
         int i;
         for (i = 0; i < esz; i++) {
            if (ic == expectedChars[i]) {
               if (matchedValue == null) {
                  matchedValue = new StringToken(parser);
                  matchedValue.len = 1;
                  matchedValue.startIndex = parser.currentIndex;
               }
               else {
                  matchedValue.len++;
               }
               parser.currentIndex++;
               break;
            }
         }
         if (i == esz)
            break;
      } while (repeat);

      if (matchedValue == null) {
         if (optional)
            return parseResult(parser, null, false);
         else {
            if (parser.eof && parser.peekInputChar(0) == '\0')
               return parseEOFError(parser, null, negated ? "Did not expect one of {0}" : "Expected one of {0}", this);
            else
               return parseError(parser, negated ? "Did not expect one of {0}" : "Expected one of {0}", this);
         }
      }
      else {
         String customError = acceptMatch(parser, matchedValue, parser.lastStartIndex, parser.currentIndex);
         if (customError == null)
            return parseResult(parser, matchedValue, false);
         else
            return parseError(parser, customError, this);
      }
   }

   String acceptMatch(Parser parser, StringToken matchedValue, int lastStart, int current) {
      String customError = accept(parser.semanticContext, matchedValue, lastStart, current);
      if (customError != null)
         return customError;
      if (excludedValues != null) {
         // Some input symbols may be excluded e.g. %> will override %
         ArrayList<ArrString> excludedTokensToPeek = excludedPeekString.get(matchedValue);
         if (excludedTokensToPeek != null) {
            for (ArrString excludeToken:excludedTokensToPeek) {
               // If we match the excluded peek string for this symbol, it's not a match
               if (parser.peekInputStr(excludeToken, false, ignoreCase) == 0)
                  return "excluded token";
            }
         }
      }
      return null;
   }

   public Object parse(Parser parser) {
      if (allSingleCharPatterns)
         return parseAllChars(parser);

      if (trace)
         System.out.println("*** tracing parse of symbol choice");

      StringToken matchedValue = null;
      boolean matchedAny = false;
      boolean matched;

      StringToken inputStr = new StringToken(parser);
      if (ignoreCase)
         inputStr.toLower = true;
      do {
         matched = stringMatches(parser, inputStr);

         // No match on any of the key sizes we need to test
         if (!matched) {
            break;
         }
         else {
            matchedAny = true;

            // Advance the ptr so we scan for the next symbol
            int len = inputStr.length();
            parser.changeCurrentIndex(parser.currentIndex + len);
            if (matchedValue == null) {
               if (ignoreCase) {
                  inputStr.toLower = false; // put the token back into it's normal mode after the match is complete
                  inputStr.hc = -333;
               }
               matchedValue = inputStr;
               inputStr = new StringToken(parser);
            }
            else {
               matchedValue.len += inputStr.len;
               matchedValue.hc = -333;
            }
         }
      } while (repeat);

      if (!matchedAny) {
         if (optional)
            return parseResult(parser, null, false);
         else {
            if (parser.eof && inputStr.charAt(keySize-1) == '\0')
               return parseEOFError(parser, null, negated ? "Did not expect one of {0}" : "Expected one of {0}", this);
            else
               return parseError(parser, negated ? "Did not expect one of {0}" : "Expected one of {0}", this);
         }
      }
      else {
         String customError = acceptMatch(parser, matchedValue, parser.lastStartIndex, parser.currentIndex);
         if (customError == null)
            return parseResult(parser, matchedValue, false);
         else
            return parseError(parser, customError, this);
      }
   }

   // Only should be called for parselets that produce a node which needs to be re-registered.  SymbolChoice produces
   // a string that will be a property in the model which can be skipped during the restore operation.
   public Object restore(Parser parser, ISemanticNode oldModel, RestoreCtx rctx, boolean inherited) {
      if (oldModel != null)
         System.err.println("*** Invalid symbol choice restore!");

      return parse(parser); // We really just need to advance the pointer here but for now just do a normal parse
   }

   public void saveParse(IParseNode pn, ISemanticNode sn, SaveParseCtx sctx) {
      Parselet pnParselet = pn.getParselet();
      if (pnParselet == this)
         sctx.pOut.saveChild(pnParselet, pn, sctx, false);
      else
         pnParselet.saveParse(pn, sn, sctx);
   }

   public String toHeaderString(Map<Parselet,Integer> visited) {
      if (name != null)
         return name;
      return toString();
   }

   public String toString() {
      if (name != null && !(name.startsWith("<")))
          return "<" + name + ">";
      return getPrefixSymbol() + ParseUtil.escapeString(expectedValues.toString()) + getSuffixSymbol();
   }

   public void clear() {
      expectedValues.clear();
      initialized = false;
   }

   private static GenerateError SYMBOL_CHOICE_ERROR = new GenerateError("Value did not match");

   public Object generate(GenerateContext ctx, Object value) {
      if (trace)
         System.out.println("*** tracing generated node");
      
      IString istr = PString.toIString(value);

      if (istr == null) {
         if (defaultGenerateValue != null)
            return generateResult(ctx, defaultGenerateValue);

         if (optional)
            return null;

         // If the symbol choice only has one value, we handle it like we do with 'symbol'
         // This is a weird case in the grammar but useful because Symbol does not implement
         // the excludedValues like SymbolChoice does.
         if (!negated && !repeat && expectedValues.size() == 1)
            return generateResult(ctx, expectedValues.iterator().next());

         // There's not enough information here to generate which of the symbols should be generated
         return SYMBOL_CHOICE_ERROR;
      }

      // An optimization but more importantly keeps us from matching
      // a larger string against a negated rule like !".  We should only
      // look at the first character in that case.
      if (maxLen != -1 && istr.length() > maxLen)
         istr = istr.substring(0, maxLen);

      // TODO: optimize: should have string matches find the longest match?
      while (istr.length() > 0 && !stringMatches(istr))
         istr = istr.substring(0,istr.length()-1);

      // TODO: use acceptTree here to validate the entire value?
      if (istr.length() > 0 && stringMatches(istr) && accept(ctx.semanticContext, istr, -1, -1) == null)
         return generateResult(ctx, istr); 

      return optional ? null : SYMBOL_CHOICE_ERROR;
   }

   public Object clone() {
      SymbolChoice newP = (SymbolChoice) super.clone();
      newP.expectedValues = (HashSet<IString>) newP.expectedValues.clone();
      newP.valueIndex = (HashMap<IString,List<IString>>) newP.valueIndex.clone();
      if (newP.excludedValues != null)
         newP.excludedValues = (HashSet<IString>) newP.excludedValues.clone();
      if (newP.excludedPeekString != null)
         newP.excludedPeekString = (HashMap<IString,ArrayList<ArrString>>) newP.excludedPeekString.clone();
      return newP;
   }
}
