/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StringUtil {
   public static ArrayList<String> EMPTY_STRING_LIST = new ArrayList<String>(0);

   public static boolean isEmpty(String s) {
      return s == null || s.length() == 0;
   }

   public static boolean isBlank(String s) {
      return isEmpty(s) || s.trim().length() == 0;
   }

   public static String argsToString(List<String> args) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < args.size(); i++) {
         if (i != 0) sb.append(" ");
         sb.append(args.get(i));
      }
      return sb.toString();
   }

   public static String argsToString(String[] args) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < args.length; i++) {
         if (i != 0) sb.append(" ");
         sb.append(args[i]);
      }
      return sb.toString();
   }

   public static String[] split(String source, char delim) {
      int ct = 1;
      for (int i = 0; i < source.length(); i++)
         if (source.charAt(i) == delim)
            ct++;

      String[] arr = new String[ct];
      ct = 0;
      int last = 0;
      for (int i = 0; i < source.length(); i++) {
         if (source.charAt(i) == delim) {
            arr[ct++] = source.substring(last, i);
            last = i+1;
         }
      }
      arr[ct] = source.substring(last,source.length());
      return arr;
   }

   public static String[] split(String source, String delim) {
      int ix;
      ArrayList<String> result = new ArrayList<String>();
      while ((ix = source.indexOf(delim)) != -1) {
         result.add(source.substring(0, ix));
         source = source.substring(ix+delim.length());
      }
      result.add(source);
      return result.toArray(new String[result.size()]);
   }

   public static String[] splitQuoted(String source) {
      List<String> matchList = new ArrayList<String>();
      Pattern regex = Pattern.compile("[^\\s\"']+|\"([^\"]*)\"|'([^']*)'");
      Matcher regexMatcher = regex.matcher(source);
      while (regexMatcher.find()) {
         String s;
         if ((s = regexMatcher.group(1)) != null) {
            // Add double-quoted string without the quotes
            matchList.add(s);
         }
         else if ((s = regexMatcher.group(2)) != null) {
            // Add single-quoted string without the quotes
            matchList.add(s);
         }
         else {
            // Add unquoted word
            matchList.add(regexMatcher.group());
         }
      }
      return matchList.toArray(new String[matchList.size()]);
   }

   public static String indent(int l) {
      StringBuffer sb = new StringBuffer();
      for (int i = 0; i < l; i++)
         sb.append("   ");
      return sb.toString();
   }

   public static String repeat(String s, int ct) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < ct; i++)
         sb.append(s);
      return sb.toString();
   }

   public static String arrayToString(Object[] list) {
      if (list == null)
         return "";
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < list.length; i++) {
         if (i != 0)
            sb.append(", ");
         sb.append(list[i]);
      }
      return sb.toString();
   }

   /** Returns a normalized path from the list of toString'd objects */
   public static String arrayToPath(Object[] list) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < list.length; i++) {
         if (i != 0)
            sb.append(":");
         sb.append(list[i]);
      }
      return sb.toString();
   }

   public static String arrayToType(Object[] list) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < list.length; i++) {
         if (i != 0)
            sb.append(".");
         sb.append(list[i]);
      }
      return sb.toString();
   }

   public static String arrayToCommand(Object[] list) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < list.length; i++) {
         if (i != 0)
            sb.append(" ");
         sb.append(list[i]);
      }
      return sb.toString();
   }

   public static CharSequence escapeHTML(CharSequence input, boolean escapeBlankLines) {
      int len = input.length();
      StringBuilder sb = null;
      boolean blankLine = escapeBlankLines;
      for (int i = 0; i < len; i++) {
         char c = input.charAt(i);
         switch (c) {
            case ' ':
            case '\t':
               if (sb != null)
                  sb.append(c);
               break;
            case '<':
               sb = appendBuffer(sb,"&lt;", input, i);
               blankLine = false;
               break;
            case '>':
               sb = appendBuffer(sb,"&gt;", input, i);
               blankLine = false;
               break;
            case '&':
               sb = appendBuffer(sb,"&amp;", input, i);
               blankLine = false;
               break;
            case '"':
               sb = appendBuffer(sb,"&quot;", input, i);
               blankLine = false;
               break;
            case '\r':
            case '\n':
               // Because markdown treats blank lines as separators, when we dump out a chunk of HTML content
               // we need to make sure that any blank lines get turned into &nbsp; - white space so md doesn't
               // go munging the middle of the HTML block.
               if (blankLine)
                  sb = appendBuffer(sb,"&nbsp;", input, i);
               if (sb != null)
                  sb.append(c);
               blankLine = escapeBlankLines;
               break;
            
            default:
               blankLine = false;
               if (sb != null)
                  sb.append(c);
               break;
         }
      }
      if (sb == null)
         return input;
      return sb;
   }

   public static String escapeQuotes(CharSequence in) {
      return in.toString().replace("'","&apos;").replace("\"", "&quot;");
   }

   private static StringBuilder appendBuffer(StringBuilder sb, String str, CharSequence input, int i) {
      if (sb == null) {
         sb = new StringBuilder();
         sb.append(input.subSequence(0,i));
      }
      sb.append(str);
      return sb;
   }

   public static boolean equalStrings(String s1, String s2) {
      int len1 = s1 == null ? 0 : s1.length();
      int len2 = s2 == null ? 0 : s2.length();
      if (len1 != len2)
         return false;
      if (s1 == null || s2 == null)
         return true;
      return s1.equals(s2);
   }

   public static boolean arraysEqual(String[] arr1, String[] arr2) {
      if (arr1 == arr2)
         return true;

      if (arr1 == null || arr2 == null)
         return false;

      if (arr1.length != arr2.length)
         return false;

      for (int i = 0; i < arr1.length; i++) {
         if (!arr1[i].equals(arr2[i]))
            return false;
      }
      return true;
   }

   /**
    * Inserts linebreaks in the given text String.  The lineWidth specifies 
    * the maximum number of characters to allow in a single line.
    */
   public static String insertLinebreaks(String text, int lineWidth) {
      if (text == null)
         return null;
      StringBuffer buf = new StringBuffer(text);
      int strLength = buf.length();
      int pos = 0;
      int nextNewline = buf.indexOf("\n");

      while ((pos + lineWidth) < strLength) {
        // if a newline already exists in this segment, advance our
        // position to the character immediately following it
        if (nextNewline != -1 && nextNewline <= (pos + lineWidth)) {
           pos = nextNewline + 1;
           nextNewline = buf.indexOf("\n", pos);
        } else {
            int nextBreak = buf.lastIndexOf(" ", pos + lineWidth);
            // if there are no spaces in this segment, just find the 
      	    // next space and put a newline there
      	    if (nextBreak <= pos) {
               nextBreak = buf.indexOf(" ", pos + lineWidth);
               if (nextBreak == -1)
                  break;
            }
      	    buf.setCharAt(nextBreak, '\n');
      	    pos = nextBreak + 1;
         }
      }

      return buf.toString();
   }

   /**
    * Truncates the text String, adding ellipsis ("...") at the end.
    * If wholeWords is true, the text before the ellipsis is further
    * truncated so that it ends at the end of a word.
    */
   public static String ellipsis(String text, int maxLength, boolean wholeWords) {
      if ((text == null) || (text.length() <= maxLength))
	 return text;

      // Handle the pathological cases (maxLength < 3)
      switch (maxLength) {
      case 0:
	 return "";
      case 1: 
	 return ".";
      case 2:
	 return "..";
      }

      int endIndex = maxLength - 3;

      if (wholeWords && (text.charAt(endIndex) != ' ')) {
	 int lastSpace = text.lastIndexOf(' ', endIndex);
	 // If there are no spaces in our truncated string at all,
	 // just end it in the middle of the word.
	 if (lastSpace != -1) {
	    endIndex = lastSpace;
	 }	    
      }
      return text.substring(0, endIndex) + "...";
   }

   public static byte[] serializeToBytes(String[] stringArray) 
      throws IOException 
   {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      ObjectOutputStream out = null;
      try {
         out = new ObjectOutputStream(baos);
         out.writeObject(stringArray);
         return baos.toByteArray();
      }
      finally {
         if (out != null)
            out.close();
      }
   }

   public static String[] deserializeToStringArray(byte[] byteArray) 
      throws IOException, ClassNotFoundException 
   {
      ByteArrayInputStream bais = new ByteArrayInputStream(byteArray);
      ObjectInputStream in = null;
      try {
         in = new ObjectInputStream(bais);
	     return (String[]) in.readObject();
      }
      finally {
         if (in != null)
            in.close();
      }
   }

   public static byte[] computeHash(byte[] inputBytes) {
      try {
         MessageDigest hash = MessageDigest.getInstance("SHA1");
         return hash.digest(inputBytes);
      }
      catch (NoSuchAlgorithmException exc) {
         System.out.println("*** No SHA1 hash: " + exc);
         return null;
      }
   }

   public static byte[] computeHash(String input) {
      return computeHash(input.getBytes());
   }


   public static String formatFloat(double number) {
      DecimalFormat f = new DecimalFormat("#.##");
      return f.format(number);
   }
}
