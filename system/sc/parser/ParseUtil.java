/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.ILanguageModel;
import sc.lang.SemanticNode;
import sc.lang.ISemanticNode;
import sc.lang.java.JavaModel;
import sc.lang.SemanticNodeList;
import sc.lang.java.JavaSemanticNode;
import sc.layer.SrcEntry;
import sc.lifecycle.ILifecycle;
import sc.layer.LayeredSystem;
import sc.util.FileUtil;
import sc.util.PerfMon;
import sc.util.StringUtil;
import sc.layer.Layer;

import java.io.*;
import java.util.List;

/** Static utility methods used in the parsing code */
public class ParseUtil  {
   private ParseUtil() {
   }

   public static String stripComments(String str) {
      str = str.replace("//", "");
      str = str.replace("/**", "");
      str = str.replace("/*", "");
      str = str.replace("*/", "");
      str = str.replace(" *", "");
      str = str.trim();
      return str;
   }

   public static Object[] wrapArg(Object arg) {
      Object[] args = { arg };
      return args;
   }

   public static Object[] wrapArgs(Object arg1, Object arg2) {
      Object[] args = { arg1, arg2 };
      return args;
   }

   public static String escapeString(IString s) {
      if (s == null)
          return null;
      return escapeString(s.toString());
   }

   public static String escapeString(String s) {
      StringBuffer sb = null;
      char c;

      if (s == null)
         return null;

      for (int i = 0; i < s.length(); i++) {
         c = s.charAt(i);
         switch (c) {
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
            /*
            case '(':
               sb = initsb(sb, s, i);
               sb.append("'('");
               break;
            case ')':
               sb = initsb(sb, s, i);
               sb.append("')'");
               break;
            */
            default:
               if (sb != null)
                  sb.append(c);
               break;
         }
      }
      if (sb != null)
         return sb.toString();
      return s;
   }

   private static StringBuffer initsb(StringBuffer sb, String s, int i) {
      if (sb == null) {
         sb = new StringBuffer();
         sb.append(s.substring(0, i));
      }
      return sb;
   }

   public static String getResultAsString(Object result) {
      if (result instanceof String)
         return (String) result;
      if (result instanceof ParentParseNode)
         return ((ParentParseNode) result).toString();
      if (result instanceof ParseNode)
      {
         ParseNode node = (ParseNode) result;
         if (node.value == null)
             return null;
         return node.value.toString();
      }
      if (result == null)
          return null;
      return result.toString();
   }

   public static String readFileString(File file) {
     BufferedReader fin = null;
     /*
      * The file may have fewer characters than bytes, but should not have
      * any more.
      */
     char [] buf = new char [(int) (file.length ())];
     try {
       fin = new BufferedReader (new InputStreamReader(new FileInputStream(file)));
       int numRead = 0;
       int num;
       do {
         num = fin.read (buf, numRead, buf.length - numRead);
         if (num > 0) numRead += num;
       } while (num > 0);

       return new String(buf, 0, numRead);
     }
     catch (IOException e)
     {
        return null;
     }
     finally {
       try { if (fin != null) fin.close (); }
       catch (IOException exc) {}
     }
   }

   public static int getParseNodeLineNumber(File file, IParseNode node) {
      if (node.getStartIndex() == -1)
         return -1;
      return charOffsetToLineNumber(file, node.getStartIndex());
   }

   // TODO: this is not efficient.  We could track number in the parse node or at least cache these values?
   public static int charOffsetToLineNumber(File file, int charOffset) {
      String s = readFileString(file);
      if (s == null)
         return -1;

      return charOffsetToLineNumber(s, charOffset);
   }

   public static int charOffsetToLineNumber(String s, int charOffset) {
      int lineCt = 1;

      if (charOffset >= s.length())
         return -1;

      for (int i = 0; i < charOffset; i++) {
         char c = s.charAt(i);
         if (c == '\n') {
            lineCt++;
         }
      }
      return lineCt;
   }

   public static String charOffsetToLine(File file, int charOffset) {
      String s = readFileString(file);
      if (s == null)
         return null;

      return charOffsetToLine(s, charOffset);
   }
   
   public static String charOffsetToLine(String s, int charOffset) {
      int lineCt = 1;
      int column = 0;
      boolean newLine = false;

      if (charOffset >= s.length())
          return "end of file";

      for (int i = 0; i < charOffset; i++)
      {
         char c = s.charAt(i);
         if (c == '\n')
         {
            lineCt++;
            newLine = true;
         }
         if (newLine)
         {
            column = 0;
            newLine = false;
         }
         else
            column++;
      }
      return "line: " + lineCt + " column: " + column;
   }

   /** Given a parse node, returns either that parse node or a child of that parse node. */
   public static IParseNode findClosestParseNode(IParseNode parent, int offset) {
      if (parent.getStartIndex() > offset)
         return null; // We start after the requested offset so just return null.

      // We're the leaf node and we started just before the requested offset so return this node.
      if (!(parent instanceof ParentParseNode)) {

         if (parent instanceof ParseNode) {
            ParseNode parentParseNode = (ParseNode) parent;
            Object child = parentParseNode.value;
            if (!(child instanceof IParseNode))
               return parent;
            IParseNode childNode = (IParseNode) child;
            if (childNode.getStartIndex() > offset)
               return parent;
            return findClosestParseNodeOnChild(parentParseNode, childNode, offset);
         }
         return parent;
      }

      ParentParseNode parentNode = (ParentParseNode) parent;

      if (parentNode.children == null)
         return parentNode;

      IParseNode lastChild = null;
      for (Object child:parentNode.children) {
         if (child instanceof IParseNode) {
            if (child instanceof ErrorParseNode)
               continue;

            IParseNode childNode = (IParseNode) child;
            // This child starts after the requested offset
            if (childNode.getStartIndex() > offset) {
               if (lastChild != null)
                  return lastChild;
               else
                  return parentNode;
            }
            // This child still starts before the requested offset
            lastChild = childNode;
         }
      }
      Object semValue;
      return findClosestParseNodeOnChild(parentNode, lastChild, offset);
   }

   private static IParseNode findClosestParseNodeOnChild(IParseNode parentNode, IParseNode lastChild, int offset) {
      if (lastChild != null) {
         Object semValue = lastChild.getSemanticValue();
         if (semValue instanceof ISemanticNode) {
            ISemanticNode semNode = (ISemanticNode) semValue;
            if (semNode.getParentNode() != null)
               return findClosestParseNode(lastChild, offset);
         }
         // The value is null on this node but may not be null on one of the children.
         else {
            IParseNode res = findClosestParseNode(lastChild, offset);
            Object resVal = res.getSemanticValue();
            if (resVal instanceof ISemanticNode) {
               ISemanticNode childNode = (ISemanticNode) resVal;
               if (childNode.getParentNode() != null)
                  return res;
            }
         }
      }
      return parentNode;
   }

   public static String getInputString(File file, int startIndex, int i)
   {
      String str = readFileString(file);
      if (str == null)
         return "can't open file: " + file;
      else if (str.length() <= startIndex)
         return "EOF";
      else if (startIndex + i > str.length())
         return str.substring(startIndex) + "...";
      return str.substring(startIndex, startIndex + i);
   }

   public static String escapeObject(Object value)
   {
      if (value == null)
         return "null";
      return escapeString(value.toString());
   }

   public final static Object CONVERT_MISMATCH = new Object();

   public static Object convertSemanticValue(Class svClass, Object value) {
      // We really want to use an exact match here.
      boolean isInstance = svClass.isInstance(value); 
      if (isInstance)
          return value;
      if (svClass == IString.class && value instanceof String)
         return value;

      // We use the boolean to match against string properties where they are null or not null
      if (value instanceof Boolean && (svClass == IString.class || svClass == String.class))
         return value;
      if (value instanceof List) {
         List l = (List) value;
         if (l.size() > 0)
            return convertSemanticValue(svClass, l.get(0));
         return CONVERT_MISMATCH;
      }
      return CONVERT_MISMATCH;
   }

   public static void clearSemanticValue(Object value, IParseNode source) {
      if (value == null)
          return;
      
      if (value instanceof IParseNode)
         ((IParseNode) value).setSemanticValue(null);
      else if (value instanceof SemanticNode) {
         SemanticNode node = (SemanticNode) value;
         // many parse nodes may point to a value but we only need to clear if we
         // are removing the value from the parentNode.
         node.setParseNode(null);
      }
      else if (value instanceof IParseNode)
           ((IParseNode) value).setSemanticValue(null);
      else if (value instanceof SemanticNodeList) {
         SemanticNodeList nodeList = (SemanticNodeList) value;
         nodeList.setParseNode(null);
         for (Object o:nodeList)
            clearSemanticValue(o, source);
      }
      else if (value instanceof List) {
         for (Object o:(List) value)
            clearSemanticValue(o, source);
      }
   }

   public static int firstCharFromValue(Object value) {
      if (PString.isString(value)) {
         IString str = PString.toIString(value);
         if (str == null || str.length() == 0)
            return -1;
         return str.charAt(0);
      }
      if (value instanceof IParseNode)
         return ((IParseNode)value).firstChar();

      if (value == null)
         return -1;
      throw new IllegalArgumentException("Unrecognized value in firstChar for parse node");
   }

   public static CharSequence toSemanticString(Object value) {
      if (value instanceof IParseNode)
         return ((IParseNode) value).toSemanticString();
      return value.toString();
   }

   public static int toSemanticStringLength(Object value) {
      if (value instanceof IParseNode)
         return ((IParseNode) value).toSemanticStringLength();
      if (value instanceof CharSequence)
         return ((CharSequence) value).length();
      return value.toString().length();
   }

   public static boolean isArrayParselet(Parselet parselet) {
      Class svClass;
      return parselet.getSemanticValueIsArray() ||
             ((svClass = parselet.getSemanticValueClass()) != null && List.class.isAssignableFrom(svClass));
   }

   /**
    * Use this method to convert the return value of the parse method into the semantic value produced.
    * Usually the parse method returns an IParseNode.
    */
   public static Object nodeToSemanticValue(Object node) {
      return node instanceof IParseNode ? ((IParseNode)node).getSemanticValue() : node;
   }

   public static Object initComponent(Object obj) {
      if (obj instanceof ILifecycle) {
         ILifecycle sc = (ILifecycle) obj;
         if (!sc.isInitialized())
            sc.initialize();
      }
      return obj;
   }

   public static Object startComponent(Object obj) {
      if (obj instanceof ILifecycle) {
         ILifecycle sc = (ILifecycle) obj;
         if (!sc.isStarted())
            sc.start();
         // this should not validate because we use this method to start components during the start phase
      }
      return obj;
   }

   public static Object validateComponent(Object obj) {
      if (obj instanceof ILifecycle) {
         ILifecycle sc = (ILifecycle) obj;
         if (!sc.isStarted())
            sc.start();
         if (!sc.isValidated())
            sc.validate();
      }
      return obj;
   }

   /** TODO: this just inits and starts the type for those cases where you can't do validate */
   public static Object realInitAndStartComponent(Object obj) {
      if (obj instanceof ILifecycle) {
         ILifecycle sc = (ILifecycle) obj;

         if (!sc.isInitialized())
            sc.initialize();
         if (!sc.isStarted())
            sc.start();
      }
      return obj;
   }

   /** This really does init, start, and validate.  TODO: Should fix this at some point. */
   public static Object initAndStartComponent(Object obj) {
      if (obj instanceof ILifecycle) {
         ILifecycle sc = (ILifecycle) obj;

         if (!sc.isInitialized())
            sc.initialize();
         if (!sc.isStarted())
            sc.start();
         if (!sc.isValidated())
           sc.validate();
      }
      return obj;
   }

   public static void stopComponent(Object obj) {
      if (obj instanceof ILifecycle) {
         ILifecycle sc = (ILifecycle) obj;
         sc.stop();
      }
   }

   public static Object getParseResult(Object node) {
      return initAndStartComponent(nodeToSemanticValue(node));
   }

   public static Object getParseNode(Parselet parselet, ISemanticNode node) {
      if (node.getParseNode() == null)
         return parselet.generate(parselet.getLanguage().newGenerateContext(parselet, node), node);
      else
         return node.getParseNode();
   }

   public static String toLanguageString(Parselet parselet, ISemanticNode node) {
      // Need to force this to update here as it's going to the outside world, like in TransformUtil.  If the parse nodes
      // are invalid, we still have to regenerate here.
      node.validateParseNode(false);
      PerfMon.start("ParseUtil.toLanguageString");
      try {
         return getParseNode(parselet, node).toString();
      }
      finally {
         PerfMon.end("ParseUtil.toLanguageString");
      }
   }

   public static void restartComponent(Object obj) {
      ParseUtil.stopComponent(obj);
      ParseUtil.initAndStartComponent(obj);
   }

   public static CharSequence styleString(Object semanticValue, IParseNode node, String strVal, boolean escape) {
      if (node == null || node.getParselet() == null)
         return strVal;
      String styleName = node.getParselet().styleName;
      return styleString(semanticValue, styleName, strVal, escape);
   }

   public static CharSequence styleString(Object semanticValue, String styleName, CharSequence strVal, boolean escape) {
      if (semanticValue instanceof ISemanticNode)
          return ((ISemanticNode) semanticValue).toStyledString();
      return styleString(styleName, strVal, escape);
   }

   public static CharSequence getStyleStart(String styleName) {
      StringBuilder sb = new StringBuilder();
      sb.append("<span class=\"");
      sb.append(styleName);
      sb.append("\">");
      return sb;
   }

   public static CharSequence getStyleEnd() {
      return "</span>";
   }

   public static CharSequence styleString(String styleName, CharSequence strVal, boolean escape) {
      if (styleName != null) {
         StringBuilder sb = new StringBuilder();
         sb.append(getStyleStart(styleName));
         if (escape)
            sb.append(StringUtil.escapeHTML(strVal, false));
         else
            sb.append(strVal);
         sb.append(getStyleEnd());
         return sb;
      }
      if (escape)
         return StringUtil.escapeHTML(strVal, false);
      else
         return strVal;
   }

   public static Object toStyledString(Object parseNode) {
      if (parseNode instanceof IParseNode)
         return ((IParseNode) parseNode).toStyledString();
      else if (parseNode == null)
         return "";
      else
         return StringUtil.escapeHTML(parseNode.toString(), false);
   }

   public static CharSequence styleSemanticValue(Object semanticValue, Object result) {
      if (semanticValue instanceof ISemanticNode)
         return ((ISemanticNode) semanticValue).toStyledString();
      else if (result instanceof IParseNode)
         return ((IParseNode) result).toStyledString();
      else
         return result.toString();
   }

   public static Object styleParseResult(String layerPath, String dispLayerPath, String fileName, boolean displayErrors, boolean isLayer, Object result) {
      Object semanticValue = ParseUtil.nodeToSemanticValue(result);

      if (semanticValue instanceof ILanguageModel) {
         ILanguageModel model = ((ILanguageModel) semanticValue);
         LayeredSystem sys = LayeredSystem.getCurrent().getMainLayeredSystem();
         model.setLayeredSystem(sys);
         if (model instanceof JavaModel) {
            ((JavaModel) model).isLayerModel = isLayer;
            ((JavaModel) model).temporary = true;
         }
         if (fileName != null) {
            Layer layer = null;
            if (layerPath != null) {
               layer = sys.getLayerByPath(layerPath);
               if (layer == null) {
                  // Layer does not have to be active here - this lets us parse the code in the layer but not really start, transform or run the modules because the layer itself is not started
                  layer = sys.getInactiveLayer(layerPath);
                  if (layer == null)
                     System.err.println("*** No layer: " + layerPath + " for style operation on: " + fileName);
               }
               model.setLayer(layer);
            }
            model.addSrcFile(new SrcEntry(layer, FileUtil.concat(layerPath, fileName), fileName));
         }
         if (!displayErrors && model instanceof JavaModel)
            ((JavaModel) model).disableTypeErrors = true;
         ParseUtil.initAndStartComponent(semanticValue);
      }

      StringBuilder sb = new StringBuilder();
      if (dispLayerPath != null && fileName != null) {
         sb.append("<div class='filename'>");
         sb.append("<span class='filePrefix'>file: </span>");
         sb.append("<span class='layerName'>");
         sb.append(dispLayerPath);
         sb.append("</span>/");
         sb.append(fileName);
         sb.append("\n</div>"); // divs must close at the start of a new line for markdown
      }
      sb.append("<pre class='code'>");
      String styledResult = ParseUtil.styleSemanticValue(semanticValue, result).toString();
      // This is a hack so that we can cut off the end of a styled result without getting syntax errors.  Technically
      // the enablePartialValues should support that but doesn't right now.
      int ix = styledResult.indexOf("// REMOVE THE REST");
      if (ix != -1)
         styledResult = styledResult.substring(0, ix);
      sb.append(styledResult);
      sb.append("</pre>");
      return sb;
   }

   public static void processComponent(ILifecycle comp) {
      if (!comp.isValidated())
         initAndStartComponent(comp);
      if (!comp.isProcessed())
         comp.process();
   }

   public static int getSemanticValueNestingDepth(Object sv) {
      if (sv == null)
         return -1;
      if (sv instanceof ISemanticNode) {
         return ((ISemanticNode) sv).getNestingDepth();
      }
      return -1;
   }

   /** Re-applies default spacing rules to the parse node given */
   public static Object resetSpacing(Object parseNodeObj) {
      if (parseNodeObj instanceof IParseNode)
         return ((IParseNode) parseNodeObj).reformat();
      return parseNodeObj;
   }

   /** Re-applies default spacing/new line rules to the parse node given.  The spacing and newline objects have their parse nodes replaced by the generateParseNode */
   public static void resetSpacing(ISemanticNode node) {
      IParseNode opn = node.getParseNode();
      IParseNode npn = opn.reformat();
      if (npn != opn)
         node.setParseNode(npn);
   }


   /** Handles the case where there is a primitive boolean property.  Each getX call on that property will return a new Boolean instance.  During updateParseNode, we have a test to stop updating when we find a getX call that did not match from one to the next. */
   public static boolean equalSemanticValues(Object o1, Object o2) {
      return o1 == o2 || (o1 instanceof Boolean && o1.equals(o2));
   }

   /** For retrieving the comments that precede a variable definition */
   public static String getPreSpacingForNode(Object parseNode) {
      String space = getSpacingForNode(parseNode);
      if (space == null)
         return null;

      for (int i = 0; i < space.length(); i++) {
         char ch = space.charAt(i);
         if (ch == '\n') {
            return space.substring(i+1);
         }
      }
      return "";
   }

   /** For retrieving the comments that follow a variable definition on the same line */
   public static String getTrailingSpaceForNode(Object parseNode) {
      String space = getSpacingForNode(parseNode);
      if (space == null)
         return null;

      for (int i = 0; i < space.length(); i++) {
         char ch = space.charAt(i);
         if (ch == '\n') {
            return space.substring(0, i);
         }
      }
      return space;
   }

   public static String getSpacingForNode(Object parseNode) {
      StringBuilder sb = new StringBuilder();
      if (parseNode instanceof ParentParseNode) {
         ParentParseNode par = (ParentParseNode) parseNode;
         // TODO: should there be a better way to detect a spacing parselet?
         if (par.parselet.generateParseNode instanceof SpacingParseNode)
            sb.append(par.toString());
         else {
            if (par.children != null) {
               for (Object child:par.children) {
                  sb.append(getSpacingForNode(child));
               }
            }
         }
      }
      return sb.toString();
   }

   public static String getSpaceForBodyElement(ParentParseNode pp, ISemanticNode value) {
      StringBuilder sb = new StringBuilder();
      if (pp != null && pp.children != null) {
         int ix = 0;
         if (pp.children.size() == 3) {
            Object classBodyChild = pp.children.get(1);
            if (classBodyChild instanceof ParentParseNode) {
               ParentParseNode classBodyParseNode = (ParentParseNode) classBodyChild;

               for (Object childNode:classBodyParseNode.children) {
                  if (childNode instanceof ParentParseNode) {
                     ParentParseNode childParent = (ParentParseNode) childNode;
                     if (childParent.refersToSemanticValue(value))
                        break;
                  }
                  ix++;
               }
               if (ix != classBodyParseNode.children.size() && ix > 0) {
                  Object prev = classBodyParseNode.children.get(ix - 1);
                  sb.append(ParseUtil.getPreSpacingForNode(prev));
               }
               // The space for the first element is up one level right after the { in the parse node for <classBody>
               else if (ix == 0) {
                  sb.append(ParseUtil.getSpacingForNode(pp.children.get(0)));
               }
            }
         }
      }

      return sb.toString();
   }

   public static String getSpaceBefore(IParseNode parseNode, ISemanticNode value) {
      StringBuilder sb = new StringBuilder();
      if (parseNode instanceof ParentParseNode) {
         ParentParseNode pp = (ParentParseNode) parseNode;
         if (pp.children != null) {
            for (Object childNode:pp.children) {
               if (childNode instanceof ParentParseNode) {
                  ParentParseNode childParent = (ParentParseNode) childNode;
                  if (childParent.value == value)
                     return sb.toString();

                  Object childSB = getSpacingForNode(childParent);
                  if (childSB != null) {
                     sb.append(childSB);
                  }
               }
            }
         }
      }
      return sb.toString();
   }

   public static boolean isCollapsibleNode(Object currentParent) {
      return currentParent instanceof JavaSemanticNode && ((JavaSemanticNode) currentParent).isCollapsibleNode();
   }

   public static int getLineNumberForNode(IParseNode rootParseNode, IParseNode toFindPN) {
      LineForNodeCtx ctx = new LineForNodeCtx();
      ctx.computeLineNumberForNode(rootParseNode, toFindPN);
      if (ctx.found)
         return ctx.curLines;
      return -1;
   }

   static class LineForNodeCtx {
      boolean found = false;
      int curLines = 1; // The first line starts at 1

      private void computeLineNumberForNode(IParseNode rootParseNode, IParseNode toFindPN) {
         if (rootParseNode instanceof ParentParseNode) {
            ParentParseNode pn = (ParentParseNode) rootParseNode;
            if (pn.children == null)
               return;
            for (int i = 0; i < pn.children.size(); i++) {
               Object childNode = pn.children.get(i);
               if (childNode == toFindPN) {
                  found = true;
                  return;
               }
               else if (childNode instanceof IParseNode) {
                  computeLineNumberForNode((IParseNode) childNode, toFindPN);
                  if (found)
                     return;
               }
               else if (childNode instanceof CharSequence) {
                  curLines += countLinesInNode((CharSequence) childNode);
               }
            }
         }
         else if (rootParseNode instanceof ParseNode) {
            ParseNode pn = (ParseNode) rootParseNode;
            Object pnVal = pn.value;
            if (pnVal == toFindPN)
               found = true;
            else if (pnVal instanceof IParseNode)
               computeLineNumberForNode((IParseNode) pnVal, toFindPN);
            else if (pnVal instanceof CharSequence)
               curLines += countLinesInNode((CharSequence) pnVal);
         }
         else {
            // Newline or spacing node - just treat it as a string
            curLines += countLinesInNode(rootParseNode);
         }
      }
   }

   static class NodeAtLineCtx {
      int curNumLines = 0;
      ISemanticNode lastVal;
   }

   private static int countLinesInNode(CharSequence nodeStr) {
      int numLines = 0;
      for (int i = 0; i < nodeStr.length(); i++) {
         char c = nodeStr.charAt(i);
         if (c == '\n')
            numLines++;
      }
      return numLines;
   }

   public static ISemanticNode getNodeAtLine(IParseNode parseNode, int requiredLineNum, NodeAtLineCtx ctx) {
      if (ctx == null)
         ctx = new NodeAtLineCtx();

      if (parseNode == null)
         return null;

      ISemanticNode parentVal = ctx.lastVal;

      // Keep track of the last semantic node we past in the hierarchy
      Object val = parseNode.getSemanticValue();
      if (val instanceof ISemanticNode)
         ctx.lastVal = (ISemanticNode) val;

      if (parseNode instanceof ParentParseNode) {
         ParentParseNode par = (ParentParseNode) parseNode;

         if (par.children == null)
            return null;

         for (Object childNode:par.children) {
            if (PString.isString(childNode)) {
               CharSequence nodeStr = (CharSequence) childNode;
               ctx.curNumLines += countLinesInNode(nodeStr);
               if (ctx.curNumLines >= requiredLineNum)
                  return ctx.lastVal;
            }
            else if (childNode != null) {
               ISemanticNode res = getNodeAtLine((IParseNode) childNode, requiredLineNum, ctx);
               if (res != null || ctx.curNumLines > requiredLineNum)
                  return res;

               ctx.lastVal = parentVal;
            }
         }
      }
      else if (parseNode instanceof ParseNode) {
         ParseNode pn = (ParseNode) parseNode;
         Object childVal = pn.value;
         if (childVal == null)
            return null;
         if (PString.isString(childVal)) {
            CharSequence nodeStr = (CharSequence) childVal;
            ctx.curNumLines += countLinesInNode(nodeStr);
            if (ctx.curNumLines >= requiredLineNum)
               return ctx.lastVal;
         }
         else {
            ISemanticNode res = getNodeAtLine((IParseNode) childVal, requiredLineNum, ctx);
            if (res != null)
               return res;
            ctx.lastVal = parentVal;
         }
      }
      else {
         ctx.curNumLines += countLinesInNode(parseNode);
         if (ctx.curNumLines >= requiredLineNum)
            return ctx.lastVal;
      }
      return null;
   }
}
