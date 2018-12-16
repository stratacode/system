/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.SemanticNode;

import java.util.*;

public class GenerateContext extends BaseRebuildCtx {
   int levels;
   public boolean finalGeneration = false;

   public SemanticContext semanticContext;

   // Turn this on when dealing with generate bugs.
   public static boolean debugError = false;
   public static boolean generateStats = false;
   boolean debug = false;

   public static int generateCount = 0;
   public static int generateError = 0;

   public List<GenerateError> errors = null;

   public GenerateContext(boolean dbg) {
      debug |= dbg;
   }

   /** Avoid computing the length unless we are debugging generation errors */
   public int progress(Object obj) {
      if (debugError || generateStats) {
         if (obj == null)
            return 0;
         if (obj instanceof CharSequence)
            return ((CharSequence) obj).length();
         else if (obj instanceof GenerateError)
            return ((GenerateError) obj).progress;
         else if (obj instanceof PartialArrayResult) {
            PartialArrayResult par = (PartialArrayResult) obj;
            return progress(par.resultNode);
         }
            throw new UnsupportedOperationException();
      }
      return 0;
   }

   public Object generateChild(Parselet parselet, Object value) {
      Object result = null;

      generateCount++;

      // Lookaheads do not contribute to the semantic value or generate anything.
      if (parselet.getLookahead())
          return null;
      
      try {
         levels++;
         result = parselet.generate(this, value);
      }
      finally {
         levels--;

         /*
         if (debug || parselet.getTrace())
            System.out.println(StringUtil.indent(levels) + resultString(result) + " <= " + valueToString(value) + " generator: " + parselet);
         */
      }
      return result;
   }

   private String resultString(Object in) {
      if (in == null)
         return "null";
      // Bad idea!  If toString does any generation it will recursively infinite loop
      String input = "instance of class: " + in.getClass().toString();
      return input;
      //String input = in.toString();
      /*
      if (input.length() < 20)
         return input;
      else
         return input.substring(0,10) + "..." + input.substring(input.length()-10);
         */
   }

   String valueToString(Object value) {
      if (value == null)
         return "null";
      if (value instanceof List)
      {
         List l = (List) value;
         if (l.size() == 0)
             return "empty list";
         else
            return "[" + l.size() + "][0] = " + valueToString(l.get(0));
      }
      if (value instanceof SemanticNode)
         return ((SemanticNode) value).toHeaderString();
      return value.toString();
   }

   public Object error(Parselet parselet, GenerateError err, Object value, int progress) {
      generateError++;

      if (generateStats) {
         parselet.failedProgressBytes += progress;
      }

      /*
      if (debug || parselet.getTrace()) {
         System.out.println(StringUtil.indent(levels) + ":" + err + " parselet: " + parselet + " value: " + valueToString(value));
      }
      */
      if (debugError) {
         if (errors == null)
            errors = new ArrayList<GenerateError>();
         err = new DebugGenerateError(err, parselet, value);
         err.progress = progress;
         errors.add(err);
      }
      return err;
   }

   public String toString() {
      return "generate: " + super.toString();
   }
}
