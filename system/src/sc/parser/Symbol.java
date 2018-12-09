/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.ISemanticNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class Symbol extends Parselet {
   public static final String EOF = null;
   public static final String ANYCHAR = "";

   public ArrString expectedValue;
   private int expectedValueLength;

   // index for excludedValues - foreach excluded symbol - e.g. %> this table stores remaining string to exclude - here >
   private ArrayList<ArrString> excludedPeekStrings = null;

   // Optional set of symbols that include the expectedValues but should not cause a match - e.g. %> for TemplateLanguage to not match the % for the modulo operator
   private HashSet<IString> excludedValues = null;

   public Symbol(String id, int options, String symVal) {
      super(id, options);
      initSymbol(symVal);
   }

   public Symbol(int options, String symVal) {
      super(options);
      initSymbol(symVal);
   }

   public Symbol(String symVal) {
      super();
      initSymbol(symVal);
   }

   private void initSymbol(String symVal) {
      expectedValue = ArrString.toArrString(symVal);
      if (expectedValue != null)
         expectedValueLength = expectedValue.length();
      // For negated repeat we can only advance 1 char at a time or we'll only reject the symbol if we hit it on a boundary
      if (negated && repeat && expectedValueLength > 1)
         expectedValueLength = 1;

      if (excludedValues != null) {
         excludedPeekStrings = new ArrayList<ArrString>();
         for (IString excludeValue:excludedValues) {
            if (excludeValue.startsWith(expectedValue)) {
               IString peekStr = excludeValue.substring(expectedValue.length());
               excludedPeekStrings.add(ArrString.toArrString(peekStr.toString()));
            }
            else
               System.err.println("*** Warning: ignoring excluded value: " + excludeValue + " does not match the expected value: " + expectedValue);
         }
      }
   }

   /** Number of chars this symbol should consume if not repeating */
   private final int expectedLen() {
      if (expectedValue == null)
         return 1;
      int len = expectedValueLength;
      if (len == 0)
         return 1;
      return len;
   }

   public Object parse(Parser parser) {
      if (expectedValue == null) { // Special case for EOF
         if (parser.peekInputChar(parser.currentIndex) != '\0')
            return parseError(parser, "Expected EOF");
         return null;
      }

      StringToken matchedValue = null;
      StringToken input = new StringToken(parser);
      String customError = null;
      boolean hitEOF = false;

      int len = expectedValueLength;

      do {
         int i;

         // Special case for ANYCHAR
         if (len == 0) {
            input.startIndex = parser.currentIndex;
            input.len = 1;
            input.hc = -333;

            if (input.charAt(0) == '\0') {
               break;
            }

            if ((customError = acceptMatch(parser, input, input.startIndex, input.startIndex + 1)) != null)
               break;

            if (matchedValue == null) {
               matchedValue = input;
               input = new StringToken(parser);
            }
            else {
               matchedValue.len += input.len;
               matchedValue.hc = -333;
            }
            parser.changeCurrentIndex(parser.currentIndex + 1);
         }
         else {
            char lastChar = '\1';
            // Returns 0 for match, 1 for no match, 2 for no-match that hit eof
            int stat = parser.peekInputStr(expectedValue, negated);
            if (stat == 0) {
               int startIx = parser.currentIndex;
               input.startIndex = startIx;
               input.len = len;
               input.hc = -333;

               if ((customError = acceptMatch(parser, input, startIx, startIx + len)) != null)
                  break;

               if (matchedValue == null) {
                  matchedValue = input;
                  input = new StringToken(parser);
               }
               else {
                  matchedValue.len += input.len;
                  matchedValue.hc = -333;
               }
               parser.changeCurrentIndex(parser.currentIndex + len);
            }
            else {
               hitEOF = stat == 2;
               break;
            }
         }
      } while (repeat);

      if (matchedValue != null || optional)
         return parseResult(parser, matchedValue, false);
      else {
         ParseError err = parseError(parser, customError == null ? "Expected {0}" : customError, this);
         err.eof = hitEOF;
         return err;
      }
   }

   // Only should be called for parselets that produce a node which needs to be re-registered.  Symbol always produces
   // a string that will be a property in the model which can be skipped during the restore operation.
   public Object restore(Parser parser, ISemanticNode oldModel, RestoreCtx rctx, boolean inherited) {
      if (oldModel != null)
         System.err.println("*** Invalid symbol restore!");

      return parse(parser); // We really just need to advance the pointer here but for now just do a normal parse
   }

   String acceptMatch(Parser parser, StringToken matchedValue, int lastStart, int current) {
      String customError = accept(parser.semanticContext, matchedValue, lastStart, current);
      if (customError != null)
         return customError;
      if (excludedPeekStrings != null) {
         for (ArrString excludedValue:excludedPeekStrings) {
            // If we match the excluded peek string for this symbol, it's not a match
            if (parser.peekInputStr(excludedValue, false) == 0)
               return "excluded token";
         }
      }
      return null;
   }

   private String getSymbolValue()
   {
      return getPrefixSymbol() + (expectedValue == null ? "EOF" : expectedValue.equals(ANYCHAR) ? "ANY" : "'" + ParseUtil.escapeString(expectedValue) + "'");
   }

   public String toHeaderString(Map<Parselet,Integer> visited)
   {
      if (name == null)
         return getSymbolValue();
      return name;
   }

   public String toString() {
      if (name != null)
         return name + " = " + getSymbolValue();
      return getSymbolValue();
   }

   public Class getSemanticValueClass() {
      return IString.class;
   }

   public boolean match(SemanticContext ctx, Object value) {
      if (accept(ctx, value, -1, -1) != null)
         return false;

      if (expectedValue == null)
         return !negated == (value == null);

      if (expectedValue.length() == 0)
         return !negated;

      return !negated == (expectedValue.equals(value));
   }

   private final static GenerateError ACCEPT_ERROR = new GenerateError("Accept rule failed");
   private final static GenerateError NO_VALUE_FOR_SYMBOL = new GenerateError("No value for symbol");
   private final static GenerateError NOT_EOF = new GenerateError("Not EOF");
   private final static GenerateError NOT_EXPECTED_VALUE = new GenerateError("Not expected value");

   public Object generate(GenerateContext ctx, Object value) {
      if (PString.isString(value)) {
         IString strValue = PString.toIString(value);

         if (!repeat) {
            int exLen = expectedLen();
            strValue = strValue.length() <= exLen ? strValue : strValue.substring(0, exLen);
            if (!match(ctx.semanticContext, strValue))
               return optional ? null : ACCEPT_ERROR;
         }
         else {
            // In the string case, we need to figure out what sub string belongs to
            // this parselet.  We can't return any more than we can accept.
            while (strValue.length() > 0 && !match(ctx.semanticContext, strValue))
               strValue = strValue.substring(0,strValue.length()-1);

            if (strValue.length() == 0 && !match(ctx.semanticContext, strValue))
                return optional ? null : ACCEPT_ERROR;
         }

         value = strValue;
      }
      // When we turn a symbol into a semantic value, we may convert a
      // non-null value to a boolean in the model.  So as we generate, if
      // we see a boolean and it is true, we match the original value.
      // if it is false, we return null or an error.
      else if (value instanceof Boolean) {
         Boolean bval = (Boolean) value;
         if (!bval) {
            if (optional)
               return null;
            return ACCEPT_ERROR;
         }
         else
            return generateResult(ctx, expectedValue);
      }
      else if (!match(ctx.semanticContext, value)) {
         if (expectedValue != null && !expectedValue.equals(ANYCHAR) && value == null) {
            int x = 3;
         }
         else {
            if (optional)
                return null;
            return ACCEPT_ERROR;
         }
      }

      if (value == null) {
         if (optional)
             return null;

         if (!negated && !repeat)
            return generateResult(ctx, expectedValue);

         if (expectedValue == null)
            return null;
         return NO_VALUE_FOR_SYMBOL;
      }
      else if (expectedValue == null)
         return optional ? null : NOT_EOF;

      if (expectedValue.equals(ANYCHAR))
         return generateResult(ctx, value);

      // When we are negated, the generated value is the matched value, not the expected value.
      return generateResult(ctx, negated ? value : expectedValue);
   }

   public boolean isNullValid() {
      return expectedValue == null;
   }

   public void addExcludedValues(String... values) {
      if (excludedValues == null)
         excludedValues = new HashSet<IString>();
      for (String val:values)
         excludedValues.add(PString.toIString(val));
   }

}
