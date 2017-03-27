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

   private static String noEscapeChars = "~!_-'.*()\"";

   public static String escapeURLString(String s) {
      StringBuilder res = new StringBuilder();
      for (int i = 0; i < s.length(); i++) {
         char c = s.charAt(i);
         if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
             (c >= '0' && c <= '9') || noEscapeChars.indexOf(c) == -1)
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

   public static String escapeJavaString(String s, boolean charMode) {
      StringBuilder sb = null;
      char c;

      if (s == null)
         return null;

      for (int i = 0; i < s.length(); i++) {
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
               sb = initsb(sb, s, i);
               sb.append("\\n");
               break;
            case '\r':
               sb = initsb(sb, s, i);
               sb.append("\\r");
               break;
            case '\'':
               if (charMode) {
                  sb = initsb(sb, s, i);
                  sb.append("\\'");
               }
               else {
                  if (sb != null)
                     sb.append(c);
               }
               break;
            case '"':
               if (!charMode) {
                  sb = initsb(sb, s, i);
                  sb.append("\\\"");
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
}
