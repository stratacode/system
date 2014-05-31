/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.SemanticNode;
import sc.type.TypeUtil;
import sc.util.StringUtil;

import java.util.*;

public class GenerateContext {
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

      if (parselet.getTrace())
         System.out.println("*** Tracing generate of child: " + parselet);


      // Lookaheads do not contribute to the semantic value or generate anything.
      if (parselet.getLookahead())
          return null;
      
      try {
         levels++;
         result = parselet.generate(this, value);
      }
      finally {
         levels--;

         if (debug || parselet.getTrace())
            System.out.println(StringUtil.indent(levels) + resultString(result) + " <= " + valueToString(value) + " generator: " + parselet);
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

   IdentityHashMap<Object,Map<Object,Object>> maskTable;

   public void maskProperty(Object obj, Object mapping, Object value) {
      if (maskTable == null)
         maskTable = new IdentityHashMap<Object,Map<Object,Object>>();

      Map<Object,Object> objMasks = maskTable.get(obj);

      if (objMasks == null)
      {
         // TODO: get a more efficient small map here - usually only a couple of values
         objMasks = new HashMap<Object,Object>(7);
         maskTable.put(obj,objMasks);
      }
      objMasks.put(mapping,value);
   }

   public void unmaskProperty(Object obj, Object mapping) {
      if (maskTable == null)
          throw new IllegalArgumentException("No mask to remove");

      Map<Object,Object> objMasks = maskTable.get(obj);

      if (objMasks == null)
         throw new IllegalArgumentException("No mask to remove");

      if (!objMasks.containsKey(mapping))
         throw new IllegalArgumentException("No mask to remove");

     objMasks.remove(mapping);

      if (objMasks.size() == 0)
      {
         maskTable.remove(obj);
         if (maskTable.size() == 0)
            maskTable = null;
      }
   }

   public Object getPropertyValue(Object parent, Object mapping) {
      if (maskTable != null)
      {
         Map<Object,Object> objMasks = maskTable.get(parent);

         if (objMasks != null)
            if (objMasks.containsKey(mapping))
                return objMasks.get(mapping);
      }

      return TypeUtil.getPropertyValue(parent, mapping);
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

      if (debug || parselet.getTrace()) {
         System.out.println(StringUtil.indent(levels) + ":" + err + " parselet: " + parselet + " value: " + valueToString(value));
      }
      if (debugError) {
         if (errors == null)
            errors = new ArrayList<GenerateError>();
         err = new DebugGenerateError(err, parselet, value);
         err.progress = progress;
         errors.add(err);
      }
      return err;
   }
}
