/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.sync;

import sc.dyn.INameContext;
import sc.type.CTypeUtil;
import sc.util.JSONResolver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static sc.sync.JSONFormat.ExprPrefixes.isRefPrefix;

public class JSONParser {
   CharSequence input;
   int len;
   int curPos;
   /** Optional component to support resolving references with 'ref:' as used in the sync system */
   JSONResolver resolver;

   public JSONParser(CharSequence input, JSONResolver resolver) {
      this.input = input;
      this.len = input.length();
      this.resolver = resolver;
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

   // Parses: "string" or null
   public CharSequence parseString(boolean allowNull) {
      char c;
      do {
         if (curPos >= len)
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
         else if (allowNull && c == 'n' && (len - curPos) >= 3 && input.charAt(curPos) == 'u' && input.charAt(curPos + 1) == 'l' && input.charAt(curPos + 2) == 'l') {
            curPos += 3;
            return null;
         }
      } while (Character.isWhitespace(c));
      curPos--;
      return null;
   }

   /** The JSON format does not natively support references so when JSONDeserializer is present,
    *  use a string with a special prefix 'ref:'.  This also allows null */
   public Object parseRefOrString() {
      CharSequence val = parseString(true);
      int len;
      if (resolver != null && val != null && (len = val.length()) > 4) {
         if (isRefPrefix(val, 0)) {
            return resolveRefString(val, len);
         }
         // When the string value starts with 'ref:' we insert a \ as the first char so just need to strip this off.
         else if (val.charAt(0) == '\\' && len > 5 && isRefPrefix(val, 1))
            val = val.subSequence(1, len);
      }
      if (val != null)
         val = CTypeUtil.unescapeJavaString(val);
      return val;
   }

   Object resolveRefString(CharSequence val, int valLen) {
      String objName = val.subSequence(4, valLen).toString();
      // For sync, this will be JSONDeserializer that resolves named object references in the sync stream
      // For DB types it will resolve a reference to either DB object or a rooted object in the tree
      Object obj = resolver.resolveRef(objName, null);
      if (obj == null) {
         System.err.println("No object: " + objName + " for reference in JSON: " + this);
      }
      return obj;
   }

   // Parses: "string":
   public CharSequence parseName() {
      CharSequence res = parseString(false);
      if (res == null || !parseCharToken(':'))
         return null;
      return res;
   }

   public CharSequence parseNextName() {
      if (!parseCharToken(','))
         throw new IllegalArgumentException("Missing comma in JSON: " + this);
      return parseName();
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
         boolean first = true;
         while (!parseCharToken('}')) {
            CharSequence name = first ? parseName() : parseNextName();
            if (name == null) {
               throw new IllegalArgumentException("Expected string name value at: " + this);
            }
            Object val = parseJSONValue();

            map.put(name, val);
            first = false;
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
      skipWhitespace();
      expect(",");
      skipWhitespace();
      expect("\"");
      expect(val);
      expect("\"");
      expect(":");
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
