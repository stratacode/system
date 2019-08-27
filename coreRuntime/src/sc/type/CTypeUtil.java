/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.type;

import sc.js.JSSettings;

/**
 * Some utilities for managing types.  These are the TypeUtil methods that can get converted automatically to Javascript.
 * The PTypeUtil are those that must be ported by writing them in JS natively.  The RTypeUtil use reflection and are tied to the
 * dynamic runtime. TypeUtil was originally designed to run on GWT.
 */
@JSSettings(jsModuleFile="js/scgen.js", prefixAlias="sc_")
public class CTypeUtil {
   // TODO: replace with the above which are much more efficient
   public static String getClassName(String dottedName) {
      int bix = dottedName.indexOf("<");
      if (bix != -1) {
         dottedName = dottedName.substring(0, bix);
      }
      int ix = dottedName.lastIndexOf(".");
      if (ix == -1)
         return dottedName;
      return dottedName.substring(ix+1);
   }

   public static String getPackageName(String dottedName) {
      int ix = dottedName.lastIndexOf(".");
      if (ix == -1)
         return null;
      return dottedName.substring(0,ix);
   }

   /**
    * Capitalizes a bean property name.
    */
   public static String capitalizePropertyName(String name) {
       if (name == null || name.length() == 0) {
           return name;
       }
       // Odd rule for Java - zMin turns into getzMin() because getZMin falls into the getURL rule.
       if (name.length() > 1 && Character.isUpperCase(name.charAt(1)))
          return name;
       return name.substring(0, 1).toUpperCase() + name.substring(1);
   }

   public static String decapitalizePropertyName(String name) {
      if (name == null || name.length() == 0) {
         return name;
      }
      if (name.length() > 1 && Character.isUpperCase(name.charAt(1)) &&
                     Character.isUpperCase(name.charAt(0))){
         return name;
      }
      char chars[] = name.toCharArray();
      chars[0] = Character.toLowerCase(chars[0]);
      return new String(chars);
   }

   public static String prefixPath(String packagePrefix, String typeName) {
      if (packagePrefix == null || packagePrefix.length() == 0)
         return typeName;
      if (typeName == null || typeName.length() == 0)
         return packagePrefix;
      return packagePrefix + "." + typeName;
   }

   public static String getHeadType(String dottedName) {
      int ix = dottedName.indexOf(".");
      if (ix == -1)
         return null;
      return dottedName.substring(0,ix);
   }

   public static String getTailType(String dottedName) {
      int ix = dottedName.indexOf(".");
      if (ix == -1)
         return dottedName;
      return dottedName.substring(ix+1);
   }

   private static StringBuilder initsb(StringBuilder sb, String s, int i) {
      if (sb == null) {
         sb = new StringBuilder();
         sb.append(s.substring(0, i));
      }
      return sb;
   }


   private static String hex(int v) {
      return Integer.toHexString(v).toUpperCase();
   }

   private static String noEscapeChars = "/~!_-'.*();:@&#[]\"";

   public static String escapeURLString(String s) {
      StringBuilder res = new StringBuilder();
      for (int i = 0; i < s.length(); i++) {
         char c = s.charAt(i);
         if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
             (c >= '0' && c <= '9') || noEscapeChars.indexOf(c) != -1)
            res.append(c);
         else if (c == ' ')
            res.append('+');
         else
            res.append(hex(c));
      }
      return res.toString();
   }

   public static String escapeIdentifierString(String s) {
      StringBuilder res = new StringBuilder();
      for (int i = 0; i < s.length(); i++) {
         char c = s.charAt(i);
         if (!(i == 0 ? Character.isJavaIdentifierStart(c) : Character.isJavaIdentifierPart(c))) {
            // Technically treating all separators as _'s could lead to name conflicts but I think functionally this is the best
            // solution
            if (c == ' ' || c == '\t' || c == '\n' || c == '.' || c == '-')
               res.append("_");
            else {
               res.append("_x");
               res.append(hex(c));
               res.append("_");
            }
         }
         else
            res.append(c);
      }
      return res.toString();
   }

   /*
   public static String unescapeURLString(String s) {
      StringBuilder res = new StringBuilder();
      int len = s.length();
      for (int i = 0; i < len; i++) {
         char c = s.charAt(i);
         switch (c) {
            case '+':
               res.append(' ');
               break;
            case '%':
               String dig = s.substring(i+1, 2);
               try {
                  int charVal = Integer.parseInt(dig, 16);
                  if (charVal < 0)
                     throw new IllegalArgumentException("Invalid escape")
               }
               ... finish me or just use a native hook so this works on JS and Java land

            default:
               res.append(c);
            break;
         }
      }
      return res.toString();
   }
   */

   // Returns an escaped string using normal Java/JS rules for escaping for the given delimiter - either ', ", or ` (for js template literals)
   public static String escapeJavaString(String s, char delim, boolean escSlash) {
      StringBuilder sb = null;
      char c;

      if (s == null)
         return null;

      boolean escNewLines = delim != '`';

      int len = s.length();
      for (int i = 0; i < len; i++) {
         c = s.charAt(i);
         switch (c) {
            case '\b':
               sb = initsb(sb, s, i);
               sb.append("\\b");
               break;
            case '\f':
               sb = initsb(sb, s, i);
               sb.append("\\f");
               break;
            case '\t':
               sb = initsb(sb, s, i);
               sb.append("\\t");
               break;
            case '\n':
               if (escNewLines) {
                  sb = initsb(sb, s, i);
                  sb.append("\\n");
               }
               else if (sb != null)
                  sb.append(c);
               break;
            case '\r':
               if (escNewLines) {
                  sb = initsb(sb, s, i);
                  sb.append("\\r");
               }
               else if (sb != null)
                  sb.append(c);
               break;
            case '\'':
               if (delim == '\'') {
                  sb = initsb(sb, s, i);
                  sb.append("\\'");
               }
               else {
                  if (sb != null)
                     sb.append(c);
               }
               break;
            case '"':
               if (delim == '"') {
                  sb = initsb(sb, s, i);
                  sb.append("\\\"");
               }
               else {
                  if (sb != null)
                     sb.append(c);
               }
               break;
            case '`':
               if (delim == '`') {
                  sb = initsb(sb, s, i);
                  sb.append("\\`");
               }
               else {
                  if (sb != null)
                     sb.append(c);
               }
               break;
            case '\\':
               sb = initsb(sb, s, i);
               sb.append("\\\\");
               break;
            // TODO: we really only need to escape / when it follows <
            case '/':
               // When including a string literal inside of a script tag, need to escape the / so that </script> is not parsed out of the string literal
               if (escSlash) {
                  sb = initsb(sb, s, i);
                  sb.append("\\/");
                  break;
               }
               // else - fall through!
            default:
               if (c < 32 || c > 0x7f) {
                  sb = initsb(sb, s, i);
                  sb.append("\\u");
                  String hexRes = hex(c);
                  while (hexRes.length() < 4)
                     hexRes = "0" + hexRes;
                  sb.append(hexRes);
               }
               else {
                  if (sb != null)
                     sb.append(c);
               }
               break;
         }
      }
      if (sb != null)
         return sb.toString();
      return s;
   }

   public static String unescapeJavaString(CharSequence str) {
      StringBuilder sb = new StringBuilder(str.length());
      int len = str.length();
      for (int i = 0; i < len; i++) {
         char charVal = str.charAt(i);
         if (charVal == '\\' && i < len - 1) {
            i++;
            switch (str.charAt(i)) {
               case 'b':
                  charVal = '\b';
                  break;
               case 't':
                  charVal = '\t';
                  break;
               case 'n':
                  charVal = '\n';
                  break;
               case 'f':
                  charVal = '\f';
                  break;
               case 'r':
                  charVal = '\r';
                  break;
               case '"':
                  charVal = '\"';
                  break;
               case '\\':
                  charVal = '\\';
                  break;
               case '\'':
                  charVal = '\'';
                  break;
               case 'u':
                  CharSequence hexStr = str.subSequence(i+1,i+5);
                  i += 4;
                  try {
                     charVal = (char) Integer.parseInt(hexStr.toString(), 16);
                  }
                  catch (NumberFormatException exc) {
                     System.err.println("**** Invalid character unicode escape: " + hexStr + " should be a hexadecimal number");
                  }
                  break;
               case '0':
               case '1':
               case '2':
               case '3':
               case '4':
               case '5':
               case '6':
               case '7':
                  int olen = 1;
                  // May be \3\u1234  or \123 or \1\2\3
                  for (int oix = i + 1; oix < len; oix++) {
                     char nextChar = str.charAt(i + olen);
                     if (Character.isDigit(nextChar) && nextChar < '8')
                        olen++;
                     else
                        break;
                  }

                  CharSequence octStr = str.subSequence(i, i + olen);
                  i += olen - 1;
                  try {
                     charVal = (char) Integer.parseInt(octStr.toString(), 8);
                  }
                  catch (NumberFormatException exc) {
                     System.err.println("**** Invalid character octal escape: " + octStr + " should be an octal number");
                  }
                  break;
            }
         }
         sb.append(charVal);
      }
      return sb.toString();
   }
}
