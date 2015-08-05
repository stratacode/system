/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.ISemanticNode;
import sc.lang.SemanticNodeList;
import sc.util.FileUtil;

import java.util.IdentityHashMap;

public class NewlineParseNode extends FormattingParseNode {
   private String terminator;
   public boolean needsIndent = false;

   public NewlineParseNode(String term) {
      terminator = term;
   }

   public int firstChar() {
      return (int) terminator.charAt(0);
   }

   public String toDebugString() {
      return "<newline>";
   }

   public String toString() {
      if (terminator == null) return "\n";
      return terminator + "\n";
   }

   /** This node is inserted into the tree in places where we might need to insert a newline.  It chooses the indent
    * level to put in after the newline based on the indent level of the next semantic value.
    *
    * @param ctx
    */
   public void format(FormatContext ctx) {
      if (ctx.semanticValueOnly)
         return;

      ctx.append(terminator);

      boolean suppressNewline = false;

      boolean doPop = false;
      boolean doPush = false;
      int indentIncr = 0;
      int nextInt = ctx.nextChar();
      Object val = null;
      int indent = -1;
      char nextChar = '\0';

      // For if (...) when there's no { we indent the current indent plus one and do not push (since it's only one line)
      if (needsIndent) {
         indentIncr = 1;
      }

      if (nextInt != -1) {
         nextChar = (char) nextInt;
         switch (nextChar) {
            case '}':
               doPop = true;
               break;
            case '{':
               // if (foo) { - all on one line
               if (terminator.equals(")")) {
                  suppressNewline = true;
               }
               break;
         }
      }

      int prevInt = ctx.prevChar();
      if (prevInt != -1) {
         char prevChar = (char) prevInt;
         switch (prevChar) {
            case '}':
               if (ctx.suppressNewlines) {
                  ctx.append(" ");
                  return;
               }
               break;
            case '{':
               // If this is {} just get out without popping since we did not push this one
               if (nextChar == '}')
                   return;
               break;
         }
      }

      if (doPop)
         indent = ctx.popIndent();

      if (!suppressNewline)
         ctx.append(FileUtil.LINE_SEPARATOR);

      if (indent == -1) {
         val = ctx.nextSemanticNode();
         if (val != null && val instanceof ISemanticNode) {
            ISemanticNode ival = (ISemanticNode) val;
            int incr = 0;

            // The first newline after the open brace will find the list of statements, not
            // an individual statement.  In this case, we unwrap and get the indent
            // for the first statement in the list.
            if (ival instanceof SemanticNodeList) {
               SemanticNodeList listVal = (SemanticNodeList) ival;
               if (listVal.size() > 0) {
                  val = listVal.get(0);
                  if (val instanceof ISemanticNode)
                     ival = (ISemanticNode) val;
                  // Since the string is inside of the list, we'll add 1 to the list's indent
                  else if (PString.isString(val))
                     incr = 1;
               }
            }
            indent = ival.getNestingDepth() + incr;
         }
      }
      // The close brace will automatically pop back to the indent from the open
      if (terminator.equals("{") || doPush)
         ctx.pushIndent();

      if (!suppressNewline)
         ctx.indent(indent + indentIncr);
      else
         ctx.append(" ");
   }
}
