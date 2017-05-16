/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.sync;

import sc.type.CTypeUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static sc.sync.JSONFormat.ExprPrefixes.isRefPrefix;

public class JSONParser {
   CharSequence input;
   int len;
   int curPos;
   JSONDeserializer dser;

   public JSONParser(CharSequence input, JSONDeserializer dser) {
      this.input = input;
      this.len = input.length();
      this.dser = dser;
   }

   public boolean parseCharToken(char token) {
      char c;
      do {
         if (curPos >= len)
            return false;
         c = input.charAt(curPos++);
         if (c == token)
            return true;
      } while (Character.isWhitespace(c));
      curPos--;
      return false;
   }

   public boolean peekCharToken(char token) {
      int start = curPos;
      boolean res = parseCharToken(token);
      curPos = start;
      return res;
   }

   // Parses: "string"
   public CharSequence parseString() {
      char c;
      do {
         if (curPos >= input.length())
            return null;

         c = input.charAt(curPos++);
         if (c == '}')
            return null;

         if (c == '"') {
            int nameStart = curPos;
            do {
               if (curPos >= len)
                  return null;
               c = input.charAt(curPos++);
               if (c == '\\')
                  curPos++;
            } while (c != '"');
            return input.subSequence(nameStart, curPos - 1);
         }
      } while (Character.isWhitespace(c));
      curPos--;
      return null;
   }

   /** The JSON format does not natively support references so we need to use a string with a special prefix 'ref:' */
   public Object parseRefOrString() {
      CharSequence val = parseString();
      int len;
      if (val != null && (len = val.length()) > 4) {
         if (isRefPrefix(val, 0)) {
            String objName = val.subSequence(4, len).toString();
            Object obj = dser.resolveObject(objName, false); // TODO - this dependency makes JSONParser unusable outside of the sync system but could be easily removed
            if (obj == null)
               System.err.println("No object: " + objName + " for reference in JSON: " + this);
            return obj;
         }
         // When the string value starts with 'ref:' we insert a \ as the first char so just need to strip this off.
         else if (val.charAt(0) == '\\' && len > 5 && isRefPrefix(val, 1))
            val = val.subSequence(1, len);

         val = CTypeUtil.unescapeJavaString(val);
      }
      return val;
   }

   // Parses: "string":
   public CharSequence parseName() {
      CharSequence res = parseString();
      if (res == null || !parseCharToken(':'))
         return null;
      return res;
   }

   private void skipWhitespace() {
      char nextChar = input.charAt(curPos);
      while (Character.isWhitespace(nextChar)) {
         nextChar = input.charAt(++curPos);
      }
   }

   public Map parseObject() {
      if (parseCharToken('{')) {
         HashMap map = new HashMap();
         while (!parseCharToken('}')) {
            CharSequence name = parseName();
            if (name == null) {
               throw new IllegalArgumentException("Expected string name value at: " + this);
            }
            Object val = parseJSONValue();

            map.put(name, val);
         }
         return map;
      }
      throw new IllegalArgumentException("Expected object definition at: " + this);
   }

   public List parseArray() {
      if (parseCharToken('[')) {
         ArrayList res = new ArrayList();

         boolean first = true;
         while (!parseCharToken(']')) {
            if (!first)
               expect(",");
            first = false;
            Object val = parseJSONValue();
            res.add(val);
         }
         return res;
      }
      throw new IllegalArgumentException("Expected JSON array at: " + this);
   }

   public Object parseJSONValue() {
      skipWhitespace();
      char nextChar = input.charAt(curPos);
      switch (nextChar) {
         case '{':
            return parseObject();
         case '[':
            return parseArray();
         case '"':
            return parseRefOrString();
         case 't':
            expect("true");
            return Boolean.TRUE;
         case 'f':
            expect("false");
            return Boolean.FALSE;
         case 'n':
            expect("null");
            return null;
         case '-':
            return parseNumber(true);
      }
      if ((nextChar >= '0' && nextChar <= '9') ) {
         return parseNumber(false);
      }
      throw new IllegalArgumentException("Parse error - expected JSON value at: " + this);
   }


   public Number parseNumber(boolean negative) {
      int startNum = curPos;
      if (negative)
         parseCharToken('-');
      boolean isFloat = false;
      do {
         if (curPos >= len)
            break;
         char nextChar = input.charAt(curPos++);
         if (nextChar >= '0' && nextChar <= '9') {
            continue;
         }
         else if (nextChar == 'e' || nextChar == 'E' || nextChar == '.' || nextChar == '+' || nextChar == '-') {
            isFloat = true;
            continue;
         }
         else {
            curPos--;
            break;
         }
      } while (true);

      if (curPos > startNum) {
         String numStr = input.subSequence(startNum, curPos).toString();

         if (numStr.length() == 0)
            throw new IllegalArgumentException("Invalid json number: " + this);
         if (isFloat) {
            return Double.parseDouble(numStr);
         }
         else {
            Long res = Long.parseLong(numStr);
            if (res <= Integer.MAX_VALUE && res >= Integer.MIN_VALUE)
               return res.intValue();
            return res;
         }
      }
      throw new IllegalArgumentException("Expected number: " + this);
   }

   public void expect(String val) {
      int len = val.length();
      for (int i = 0; i < len; i++) {
         if (input.charAt(curPos++) != val.charAt(i)) {
            curPos--;
            throw new IllegalArgumentException("Expected: " + val + " found: " + this);
         }
      }
   }

   public void expectNextName(String val) {
      expect(",");
      expect(val);
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("json - parsing: ");
      if (atEOF())
         sb.append("at eof");
      else
         sb.append(input.subSequence(curPos, Math.min(curPos + 25, len)));
      sb.append(" [");
      sb.append(curPos);
      sb.append(":");
      sb.append(len);
      return sb.toString();
   }

   public static boolean eqs(CharSequence s1, CharSequence s2) {
      int len1 = s1.length();
      int len2 = s2.length();
      if (len1 != len2)
         return false;
      for (int i = 0; i < len1; i++) {
         if (s1.charAt(i) != s2.charAt(i))
            return false;
      }
      return true;
   }

   public boolean atEOF() {
      return curPos >= len;
   }
}
