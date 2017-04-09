/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.*;
import sc.lang.java.JavaModel;
import sc.lang.java.JavaSemanticNode;
import sc.layer.IExternalModelIndex;
import sc.layer.SrcEntry;
import sc.lifecycle.ILifecycle;
import sc.layer.LayeredSystem;
import sc.util.FileUtil;
import sc.util.PerfMon;
import sc.util.StringUtil;
import sc.layer.Layer;

import java.io.*;
import java.util.List;
import java.util.TreeSet;

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

   public static FilePosition charOffsetToLinePos(File file, int charOffset) {
      String s = readFileString(file);
      if (s == null)
         return null;

      return charOffsetToLinePos(s, charOffset);
   }

   public static String charOffsetToLine(String s, int charOffset) {
      FilePosition pos = charOffsetToLinePos(s, charOffset);
      if (pos == null)
         return "end of file";
      return "line: " + pos.lineNum + " column: " + pos.colNum;
   }

   public static FilePosition charOffsetToLinePos(String s, int charOffset) {
      int lineCt = 1;
      int column = 0;
      boolean newLine = false;

      if (charOffset >= s.length())
          return null;

      for (int i = 0; i < charOffset; i++) {
         char c = s.charAt(i);
         if (c == '\n') {
            lineCt++;
            newLine = true;
         }
         if (newLine) {
            column = 0;
            newLine = false;
         }
         else
            column++;
      }
      return new FilePosition(lineCt, column);
   }

   /**
    * Returns the number of spaces for the given line for the given file name.  Uses line numbers starting at 1.
    * Warning - no caching or indexing - reads the file, counts the chars so don't put this into a loop.
    * Returns 0 if the line number is found
    */
   public static int getIndentColumnForLine(String fileName, int lineNum) {
      String fileStr = FileUtil.getFileAsString(fileName);
      int len = fileStr.length();
      int lineCt = 1;
      for (int i = 0; i < len; i++) {
         if (lineCt == lineNum) {
            int spaceCt = 0;
            while (i < len && Character.isWhitespace(fileStr.charAt(i++)))
               spaceCt++;
            return spaceCt;
         }
         if (fileStr.charAt(i) == '\n')
            lineCt++;
      }
      return 0; // If we can't find the indent - just return 0 since this is used for UI navigation
   }

   /**
    * Utility method to find a single contiguous error inside of a specified region of a parse node.
    * If there are multiple errors, the 'error region' inside of the specified startIx and endIx params
    * is returned.  This is used for identifying unparsed regions in building a formatting code model
    * for this file to capture errors that exist between recognized parsed types.  It's not OK to just treat
    * them as whitespace since IntelliJ complains.
    */
   public static ParseRange findErrorsInRange(IParseNode pn, int startIx, int endIx) {
      int pnStart = pn.getStartIndex();
      int pnLen = pn.length();
      int pnEnd = pnStart + pnLen;

      if (pnStart > endIx || pnEnd < startIx)
         return null;

      // NOTE: pn.isErrorNode() does not work here - we set the error node flag on the parent if any child has an error but the entire node is not an error node
      if (pn instanceof ErrorParseNode) {
         return new ParseRange(Math.max(pnStart,startIx), Math.min(pnEnd, endIx));
      }
      else if (pn instanceof ParseNode) {
         ParseNode p = (ParseNode) pn;
         if (p.value instanceof IParseNode) {
            return findErrorsInRange((IParseNode) p.value, startIx, endIx);
         }
         return null;
      }
      else if (pn instanceof ParentParseNode) {
         ParentParseNode p = (ParentParseNode) pn;
         ParseRange errors = null;
         if (p.children != null) {
            for (int i = 0; i < p.children.size(); i++) {
               Object child = p.children.get(i);
               if (child instanceof IParseNode) {
                  IParseNode childPN = (IParseNode) child;
                  if (childPN.getStartIndex() > endIx)
                     return errors;
                  ParseRange range = findErrorsInRange(childPN, startIx, endIx);
                  if (range != null) {
                     if (errors == null)
                        errors = range;
                     else {
                        errors.mergeInto(range.startIx, range.endIx);
                     }
                  }
               }
            }
         }
         return errors;
      }
      else // String based parse-node cannot be an error
         return null;
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

   public static String getInputString(File file, int startIndex, int i) {
      String str = readFileString(file);
      if (str == null)
         return "can't open file: " + file;
      else if (str.length() <= startIndex)
         return "EOF";
      else if (startIndex + i > str.length())
         return str.substring(startIndex) + "...";
      return str.substring(startIndex, startIndex + i);
   }

   public static String escapeObject(Object value) {
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
         ((IParseNode) value).setSemanticValue(null, true);
      else if (value instanceof SemanticNode) {
         SemanticNode node = (SemanticNode) value;
         // many parse nodes may point to a value but we only need to clear if we
         // are removing the value from the parentNode.
         node.setParseNode(null);
      }
      else if (value instanceof IParseNode)
           ((IParseNode) value).setSemanticValue(null, true);
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

   public static int toLength(Object val) {
      if (val == null)
         return 0;
      if (val instanceof CharSequence)
         return ((CharSequence) val).length();
      throw new UnsupportedOperationException();
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
            sc.init();
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
            sc.init();
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
            sc.init();
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

   public static void reinitComponent(Object obj) {
      ParseUtil.stopComponent(obj);
      ParseUtil.initComponent(obj);
   }

   public static void styleString(IStyleAdapter adapter, Object semanticValue, IParseNode node, String strVal, boolean escape) {
      if (node == null || node.getParselet() == null)
         adapter.styleString(strVal, false, null, null);
      else {
         String styleName = node.getParselet().styleName;
         styleString(adapter, semanticValue, styleName, strVal, escape);
      }
   }

   public static void styleString(IStyleAdapter adapter, Object semanticValue, String styleName, CharSequence strVal, boolean escape) {
      if (semanticValue instanceof ISemanticNode)
          ((ISemanticNode) semanticValue).styleNode(adapter);
      else
         adapter.styleString(strVal, escape, styleName, null);
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

   public static boolean isSpacingNode(IParseNode childParseNode) {
      if (childParseNode instanceof NewlineParseNode || childParseNode instanceof SpacingParseNode || childParseNode.getParselet().generateParseNode != null)
         return true;
      return false;
   }

   public static class HTMLStyleAdapter implements IStyleAdapter {
      StringBuilder currentResult = new StringBuilder();
      FormatContext ctx;

      public HTMLStyleAdapter() {
      }

      public boolean getNeedsFormattedString() {
         return true;
      }

      public void setFormatContext(FormatContext ctx) {
         this.ctx = ctx;
         if (ctx != null)
            ctx.setStyleBuffer(currentResult);
      }

      public void styleStart(String styleName) {
         currentResult.append(getStyleStart(styleName));
      }

      public void styleEnd(String styleName) {
         currentResult.append(getStyleEnd());
      }

      public void styleString(CharSequence codeOut, boolean escape, String styleName, String styleDesc) {
         StringBuilder sb = currentResult;

         if (ctx != null)
            ctx.appendNoStyle(codeOut);
         if (styleName != null) {
            sb.append(getStyleStart(styleName));
            if (escape)
               sb.append(StringUtil.escapeHTML(codeOut, false));
            else
               sb.append(codeOut);
            sb.append(getStyleEnd());
         }
         else {
            if (escape)
               sb.append(StringUtil.escapeHTML(codeOut, false));
            else
               sb.append(codeOut);
         }
      }

      public CharSequence getResult() {
         return currentResult;
      }
   }

   public static void styleString(IStyleAdapter adapter, String styleName, CharSequence strVal, boolean escape) {
      adapter.styleString(strVal, escape, styleName, null);
   }


   public static void toStyledChild(IStyleAdapter adapter, ParentParseNode parent, Object child, int childIx) {
      if (!(child instanceof IParseNode) && child != null) {
         Parselet childParselet = ((NestedParselet) parent.getParselet()).getChildParselet(child, childIx);
         if (childParselet != null) {
            String styleName = null;
            styleName = childParselet.styleName;
            if (styleName != null) {
               adapter.styleString((CharSequence) child, false, styleName, null);
               return;
            }
         }
      }
      ParseUtil.toStyledString(adapter, child);
   }

   public static void toStyledString(IStyleAdapter adapter, Object parseNode) {
      if (parseNode instanceof IParseNode)
         ((IParseNode) parseNode).styleNode(adapter, null, null, -1);
      else if (parseNode != null)
         adapter.styleString(parseNode.toString(), true, null, null);
   }

   public static CharSequence styleSemanticValue(Object semanticValue, Parselet p) {
      HTMLStyleAdapter adapter = new HTMLStyleAdapter();
      if (semanticValue instanceof SemanticNode)
         ((SemanticNode) semanticValue).styleNode(adapter, p);
      return adapter.getResult();
   }

   public static CharSequence styleSemanticValue(Object semanticValue, Object result) {
      HTMLStyleAdapter adapter = new HTMLStyleAdapter();
      if (semanticValue instanceof ISemanticNode)
         ((ISemanticNode) semanticValue).styleNode(adapter);
      else if (result instanceof IParseNode)
         ((IParseNode) result).styleNode(adapter, null, null, -1);
      else
         adapter.styleString(result.toString(), false, null, null);
      return adapter.getResult();
   }

   public static Object styleParseResult(String layerPath, String dispLayerPath, String fileName, boolean displayErrors, boolean isLayer, Object result, boolean enabled) {
      Object semanticValue = ParseUtil.nodeToSemanticValue(result);

      if (semanticValue instanceof ILanguageModel) {
         ILanguageModel model = ((ILanguageModel) semanticValue);

         LayeredSystem sys = LayeredSystem.getCurrent().getMainLayeredSystem();
         Layer layer = layerPath == null ? null : sys.getActiveOrInactiveLayerByPathSync(layerPath, null, true, true, enabled);
         if (layer != null)
            model.setLayeredSystem(layer.layeredSystem);
         else
            model.setLayeredSystem(sys);
         if (model instanceof JavaModel) {
            ((JavaModel) model).isLayerModel = isLayer;
            ((JavaModel) model).temporary = true;
         }
         if (fileName != null) {
            model.setLayer(layer);
            model.addSrcFile(new SrcEntry(layer, FileUtil.concat(layer.getLayerPathName(), fileName), fileName));
         }
         // This is needed even though we may never use this model again.  Otherwise, we load the file twice if something
         // initializing the model refers back to itself again.  Now that we have the inactiveModelIndex, this will go there
         // unless it happens to be loaded as a current type.
         if (model.getSrcFile() != null && model.getSrcFile().layer != null)
            model.getLayeredSystem().addCachedModel(model);
         if (!displayErrors && model instanceof JavaModel)
            ((JavaModel) model).disableTypeErrors = true;
         ParseUtil.initAndStartComponent(semanticValue);
      }

      return styleSemanticValue(semanticValue, result, dispLayerPath, fileName);
   }

   public static Object styleSemanticValue(Object semanticValue, Object result, String dispLayerPath, String fileName) {
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

   /**
    * Removes the formatting parse-nodes, like SpacingParseNode and NewlineParseNode and replaces them with actual text based on the reformat algorithm.
    * Some background on this: when we re-generate a model, i.e. update the parse-node representation for changes in the semantic node tree, we are unable to
    * determine the spacing and other text which is generated based on the complete context.  Instead, we insert these formatting parse-nodes as placeholders.  The format process starts then
    * from the top of the file and can accurately generate the indentation, newlines and spaces as per the formatting rules.   This method performs that global operation but also replaces
    * the formatting parse-nodes with the actual formatting characters - e.g. the whitespace, newlines, etc.  so they can be more easily manipulated.   Operations like reparsing and generating
    * the IDE representation of the parse-nodes requires that the spacing is all evaluated.
    *
    * See also ParseUtil.resetSpacing which does the opposite - replacing the explicit spacing nodes with generated nodes so you can reformat a file.
    */
   public static void reformatParseNode(IParseNode node) {
      node.formatString(null, null, -1, true);
   }


   public static void resetStartIndexes(ISemanticNode node) {
      IParseNode rootParseNode = node.getParseNode();
      int endIx = rootParseNode.resetStartIndex(0, false, false);
      //if (endIx != rootParseNode.length())
     //    System.out.println("*** End index does not match after resetStartIndex");
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

      int skipIx = 0;
      for (int i = 0; i < space.length(); i++) {
         char ch = space.charAt(i);
         if (ch == '\n' || ch == ' ' || ch == '\t' || ch == '\r')  {
            skipIx++;
         }
         else
            break;
      }
      if (skipIx == 0)
         return space;
      return space.substring(skipIx);
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

   public static class SpaceBeforeResult {
      public boolean found;
      public String spaceBefore;

      SpaceBeforeResult(boolean found, String space) {
         this.found = found;
         this.spaceBefore = space;
      }
   }

   /**
    * Takes a parent child node and does a search through the parse-node tree looking for the semantic-value specified.  Returns the comments (aka whitespace) right
    * before that node, i.e. javadoc style comments */
   public static SpaceBeforeResult getSpaceBefore(IParseNode parseNode, ISemanticNode value, Parselet spacing) {
      StringBuilder sb = new StringBuilder();
      if (parseNode instanceof ParentParseNode) {
         ParentParseNode pp = (ParentParseNode) parseNode;
         if (pp.children != null) {
            for (Object childNode:pp.children) {
               if (childNode instanceof IParseNode) {
                  IParseNode childPN = (IParseNode) childNode;
                  Object childSemVal = childPN.getSemanticValue();
                  // Ok, found the top-level parse-node which produced the value
                  if (childSemVal == value) {
                     if (childPN instanceof ParentParseNode) {
                        // Check if there's a nested parse-node that also produced the same value.  if so, we'll include any comments here as well
                        SpaceBeforeResult nestedResult = getSpaceBefore(childPN, value, spacing);
                        if (nestedResult.found)
                           sb.append(nestedResult.spaceBefore);
                     }
                     return new SpaceBeforeResult(true, sb.toString());
                  }
                  // Don't look at comments which precede another semantic value - only those directly before the node we are documenting
                  else if (!(childSemVal instanceof CharSequence) && !(childSemVal instanceof SemanticNodeList))
                     sb = new StringBuilder();
                  if (childNode instanceof ParentParseNode) {
                     ParentParseNode childParent = (ParentParseNode) childNode;
                     SpaceBeforeResult childResult = getSpaceBefore(childParent, value, spacing);
                     if (childResult.found) {
                        childResult.spaceBefore = sb.toString() + childResult.spaceBefore;
                        return childResult;
                     }
                     else
                        sb.append(childResult.spaceBefore);
                  }
               }

               if (pp.parselet == spacing)
                  sb.append(childNode);
            }
         }
      }
      // Did not find the child so don't return anything
      return new SpaceBeforeResult(false, sb.toString());
   }

   public static String getCommentsBefore(IParseNode outerParseNode, ISemanticNode semNode, Parselet commentParselet) {
      ParseUtil.SpaceBeforeResult spaceBefore = ParseUtil.getSpaceBefore(outerParseNode, semNode, commentParselet);
      if (!spaceBefore.found) {
         return "";
      }
      return ParseUtil.stripComments(spaceBefore.spaceBefore);
   }

   public static boolean isCollapsibleNode(Object currentParent) {
      return currentParent instanceof JavaSemanticNode && ((JavaSemanticNode) currentParent).isCollapsibleNode();
   }

   public static int getLineNumberForNode(IParseNode rootNode, IParseNode toFindPN) {
      LineFormatContext ctx = new LineFormatContext();
      rootNode.computeLineNumberForNode(ctx, toFindPN);
      if (ctx.found)
         return ctx.curLines;
      return -1;
   }

   public static int countLinesInNode(CharSequence nodeStr) {
      int numLines = 0;
      // TODO: we should probably just have a method which counts newlines in the parse-node strings since length is almost as expensive as just doing that and length is inside of charAt - or cache len in the parse node?
      if (nodeStr instanceof IParseNode)
         nodeStr = nodeStr.toString();
      for (int i = 0; i < nodeStr.length(); i++) {
         char c = nodeStr.charAt(i);
         if (c == '\n')
            numLines++;
      }
      return numLines;
   }

   public static ISemanticNode getNodeAtLine(IParseNode parseNode, int requiredLineNum) {
      NodeAtLineCtx ctx = new NodeAtLineCtx();

      if (parseNode == null)
         return null;

      return parseNode.getNodeAtLine(ctx, requiredLineNum);
   }

   public static ISemanticNode findSameNodeInNewModel(ILanguageModel newModel, ISemanticNode oldNode) {
      // Our file model is not the current one managed by the system so we need to find the corresponding node.
      IParseNode origParseNode = oldNode.getParseNode();
      boolean computed = false;
      if (origParseNode == null && oldNode instanceof ISrcStatement) {
         ISrcStatement srcSt = (ISrcStatement) oldNode;
         ISemanticNode fromSt = srcSt.getFromStatement();
         if (fromSt != null) {
            origParseNode = fromSt.getParseNode();
            computed = true;
         }
      }
      if (origParseNode != null) {
         int startIx = origParseNode.getStartIndex();
         if (startIx != -1 && newModel != null && newModel.getParseNode() != null) {
            IParseNode newNode = newModel.getParseNode().findParseNode(startIx, origParseNode.getParselet());
            if (newNode != null) {
               Object semValue = newNode.getSemanticValue();
               if (semValue instanceof JavaSemanticNode)
                  return (JavaSemanticNode) semValue;
               else
                  System.err.println("*** Unrecognized return type");
            }
            else
               System.err.println("*** Failed to find new parse node in model");
         }
      }
      return null;
   }

   /**
    * Creates a LayeredSystem from a single classPath, externalClassPath, and srcPath.   You use this method
    * when you do not have any layers but still want to leverage the low-level language processing capabilities of
    * StrataCode.
    * You can optionally provide an implementation of IExternalModelIndex to provide control over how models
    * are cached and managed on the file system.  This is useful to help synchronize the LayeredSystem's copy of a
    * particular model with the version of the model managed by an external tool like an IDE.
    */
   public static LayeredSystem createSimpleParser(String classPath, String externalClassPath, String srcPath, IExternalModelIndex modelIndex) {
      LayeredSystem.Options options = new LayeredSystem.Options();
      options.installLayers = false;

      LayeredSystem sys = new LayeredSystem(null, null, null, null, classPath, options, null, null, false, modelIndex, null, null);

      /** Create a single layer to manage externalClasses and source files given to us to parse */
      Layer sysLayer = sys.createLayer("sysLayer", null, null, false, false, false, false, false);
      // Set the externalClassPath - i.e. those classes not loaded via the ClassLoader
      sysLayer.externalClassPath = externalClassPath;

      // Set the path for finding src files.   By default this is relative to the layer's dir so need to make it absolute
      sysLayer.srcPath = FileUtil.makePathAbsolute(srcPath);

      sys.startLayers(sysLayer);

      return sys;
   }

   public static Object reparse(IParseNode pnode, String newText) {
      int oldLen = pnode.length();
      int newLen = newText.length();

      // Stop all of the nodes before we reparse to ensure we can cleanly start them up afterwards.
      Object oldModel = pnode.getSemanticValue();
      if (oldModel != null)
         ParseUtil.stopComponent(oldModel);

      // First we make a pass over the parse node tree to find two mark points in the file - where the changes
      // start and where the text becomes the same again.  We are optimizing for the "single edit" case - global
      // edits currently will require a complete reparse (not that we could not handle this case - it will just be
      // easier for the 90+% case).
      DiffContext ctx = new DiffContext();
      ctx.text = newText;
      ctx.newLen = newLen;
      ctx.startChangeOffset = 0;

      // Find the parse node which is the first one that does not match in the text.
      pnode.findStartDiff(ctx, true, null, null, -1);
      Parselet plet = pnode.getParselet();
      Language lang = plet.getLanguage();

      // Clear this out so it's not set for findEndDiff
      ctx.lastVisitedNode = null;
      // Exact same contents - just return the originl
      if (ctx.firstDiffNode == null && oldLen == newLen && newLen == ctx.startChangeOffset)
         return pnode;
      else {
         int unparsedLen = pnode instanceof PartialValueParseNode ? ((PartialValueParseNode) pnode).unparsedLen : 0;
         ctx.unparsedLen = unparsedLen;

         ctx.diffLen = newLen - (oldLen + unparsedLen);

         int origNewLen = newLen - unparsedLen - 1;
         int origOldLen = oldLen - unparsedLen - 1;

         // The offset at which changes start - the same in both old and new texts
         ctx.endChangeNewOffset = origNewLen;
         ctx.endChangeOldOffset = origOldLen;
         pnode.findEndDiff(ctx, null, null, -1);

         // If we are still on the last character we checked - there's no overlap in these files so advance the count beyond the last char
         if (ctx.endChangeNewOffset == origNewLen)
            ctx.endChangeNewOffset = newLen;
         if (ctx.endChangeOldOffset == origOldLen)
            ctx.endChangeOldOffset = oldLen;

         if (ctx.afterLastNode == null)
            ctx.afterLastNode = ctx.lastDiffNode;

         // Start out with these two the same.  endParseChangeNewOffset can be adjusted during the reparse to force us to
         // parse more characters, even when we should be "sameAgain"
         ctx.endParseChangeNewOffset = ctx.endChangeNewOffset;

         if (ctx.lastDiffNode == ctx.firstDiffNode && ctx.afterLastNode == ctx.lastDiffNode && ctx.firstDiffNode != null) {
            int endNodeIx = ctx.firstDiffNode.getStartIndex() + ctx.firstDiffNode.length();
            if (endNodeIx > ctx.endParseChangeNewOffset)
               ctx.endParseChangeNewOffset = endNodeIx;
         }

         // Now we walk the parselet tree in a way similar to how we parsed it in the first place, but accepting
         // the pnode.  We'll update this existing pnode with changes so it looks the same as if we'd reparsed
         // the whole thing.
         Object newRes = lang.reparse(pnode, ctx, newText, true);

         // Find common parent node and reparse that node
         /*
         Object startObj = startNode.getSemanticValue();
         Object endObj = endNode.getSemanticValue();
         if (startObj instanceof ISemanticNode && endObj instanceof ISemanticNode) {
            ISemanticNode startVal = (ISemanticNode) startObj;
            ISemanticNode endVal = (ISemanticNode) endObj;
            ISemanticNode reparseNode = findCommonParent(startVal, endVal);
         }
         else {
            Parselet plet = pnode.getParselet();
            return plet.getLanguage().parse(newText, plet, false);
         }
         */
         return newRes;
      }
   }

   public static ISemanticNode findCommonParent(ISemanticNode node1, ISemanticNode node2) {
      if (node1 == node2)
         return node1;
      TreeSet<ISemanticNode> node1Parents = new TreeSet<ISemanticNode>();
      for (ISemanticNode parent1 = node1.getParentNode(); parent1 != null; parent1 = parent1.getParentNode())
         node1Parents.add(parent1);
      for (ISemanticNode parent2 = node2.getParentNode(); parent2 != null; parent2 = parent2.getParentNode()) {
         if (node1Parents.contains(parent2))
            return parent2;
      }
      // Is this reached?  Shouldn't there always be a common parent?
      return null;
   }
}
