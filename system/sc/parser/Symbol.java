/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import java.util.Map;

public class Symbol extends Parselet {
   public static final String EOF = null;
   public static final String ANYCHAR = "";

   public ArrString expectedValue;
   private int expectedValueLength;

   public Symbol(String id, int options, String ev) {
      super(id, options);
      expectedValue = ArrString.toArrString(ev);
      if (expectedValue != null)
         expectedValueLength = expectedValue.length();
   }

   public Symbol(int options, String ev) {
      super(options);
      expectedValue = ArrString.toArrString(ev);
      if (expectedValue != null)
         expectedValueLength = expectedValue.length();
   }

   public Symbol(String ev) {
      super();
      expectedValue = ArrString.toArrString(ev);
      if (expectedValue != null)
         expectedValueLength = expectedValue.buf.length;
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
               return parseEOFError(parser, matchedValue, "ANY symbol does not match EOF");
            }

            if ((customError = accept(parser.semanticContext, input, input.startIndex, input.startIndex + 1)) != null)
               break;

            if (matchedValue == null) {
               matchedValue = input;
               input = new StringToken(parser);
            }
            else {
               matchedValue.len += input.len;
               matchedValue.hc = -333;
            }
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

               if ((customError = accept(parser.semanticContext, input, startIx, startIx + len)) != null)
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
}
