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


   public static void resetStartIndexes(ISemanticNode node) {
      IParseNode rootParseNode = node.getParseNode();
      int endIx = rootParseNode.resetStartIndex(0);
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

   public static int getLineNumberForNode(IParseNode rootNode, IParseNode toFindPN) {
      LineFormatContext ctx = new LineFormatContext();
      rootNode.computeLineNumberForNode(ctx, toFindPN);
      if (ctx.found)
         return ctx.curLines;
      return -1;
   }

   public static int countLinesInNode(CharSequence nodeStr) {
      int numLines = 0;
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
         if (startIx != -1) {
            IParseNode newNode = newModel.getParseNode().findParseNode(startIx, origParseNode.getParselet());
            if (newNode != null) {
               Object semValue = newNode.getSemanticValue();
               if (semValue instanceof JavaSemanticNode)
                  return (JavaSemanticNode) semValue;
               else
                  System.err.println("*** Unrecognized return type");
            }
         }
      }
      return null;
   }

   public static LayeredSystem createSimpleParser(String classPath, String externalClassPath, String srcPath, IExternalModelIndex modelIndex) {
      LayeredSystem.Options options = new LayeredSystem.Options();
      options.installLayers = false;

      LayeredSystem sys = new LayeredSystem(null, null, null, null, classPath, options, null, null, false, modelIndex);

      /** Create a single layer to manage externalClasses and source files given to us to parse */
      Layer sysLayer = sys.createLayer("sysLayer", null, null, false, false, false, false, false);
      // Set the externalClassPath - i.e. those classes not loaded via the ClassLoader
      sysLayer.externalClassPath = externalClassPath;

      // Set the path for finding src files.   By default this is relative to the layer's dir so need to make it absolute
      sysLayer.srcPath = FileUtil.makePathAbsolute(srcPath);

      sys.startLayers(sysLayer);

      return sys;
   }
}
