/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.HTMLLanguage;
import sc.lang.ISemanticNode;
import sc.lang.SemanticNode;
import sc.lang.SemanticNodeList;
import sc.lang.html.Element;
import sc.lang.java.BinaryExpression;
import sc.lang.java.BinaryOperand;
import sc.lang.java.BlockStatement;
import sc.lang.java.Statement;
import sc.util.FileUtil;

import java.util.IdentityHashMap;

public class SpacingParseNode extends AbstractParseNode {
   Parselet parselet;
   boolean newline = false;

   public SpacingParseNode(Parselet p, boolean newline) {
      parselet = p;
      this.newline = newline;
   }

   public Parselet getParselet() {
      return parselet;
   }
   
   public int firstChar() {
      return (int) ' ';
   }

   public boolean refersToSemanticValue(Object obj) {
      return false;
   }

   public Object getSemanticValue() {
      return null;
   }

   public void setSemanticValue(Object obj) {
      if (obj == null)
         return;
      throw new UnsupportedOperationException();
   }

   public String toDebugString() {
      return "<spacing>";
   }

   public String toString() {
      return " ";
   }

   public void format(FormatContext ctx) {
      if (ctx.semanticValueOnly)
         return;
      int prevInt = ctx.prevChar();

      boolean suppressNewline = false;

      if (prevInt != -1) {
         char prevChar = (char) prevInt;
         switch (prevChar) {
            case '=':
               if (ctx.tagMode)
                  return;
               else
                  break;
            case '\n':
            case '\r':
            case '\t':
            case ' ':
            case '(':
            case '!':
            case '[':
            case '.':
            case '@':
               return; // no space needed
            case '<':
               Object sv = ctx.prevSemanticValue();
               // A bit of a hack but for comparison ops, we store the sv as a string.
               if (!(PString.isString(sv)))
                  return;
//            case '-':
//               sv = ctx.prevSemanticValue();
//               if ((PString.isString(sv)))
//                  return;

         }

         int nextInt = (char) ctx.nextChar();
         if (nextInt != -1) { // Is this even possible?
            char nextChar = (char) nextInt;
            switch (nextChar) {
               case '/':
                  // Close tag for an HTML tag should not indent
                  if (ctx.tagMode && HTMLLanguage.validTagChar(prevChar))
                     return;
                  break;
               case '=':
                  if (ctx.tagMode)
                     return;
                  else
                     break;
               case ')':
               case '.':
               case ']':
               case '[':
               case ';':
               case ' ':
                  return;
               case '(':
                  if (prevChar == '>')
                      return;
                  break;
               case '<':
               case '>':
                  Object sv = ctx.nextSemanticValue();
                  if (sv instanceof SemanticNodeList)
                     sv = ((SemanticNodeList) sv).parentNode;

                  if (ctx.tagMode) {
                     if (nextChar == '<') {
                        if (sv instanceof Element)
                           sv = ((Element) sv).tagName;
                        if (PString.isString(sv)) {
                           boolean indent = HTMLLanguage.INDENTED_SET.contains(sv.toString());
                           boolean newLine = HTMLLanguage.NEWLINE_SET.contains(sv.toString());
                           if (indent || newLine) {
                              SemanticNode node = ctx.getNextSemanticNode();
                              if (node == null) {
                                 node = ctx.getPrevSemanticNode();
                                 if (node != null && (newLine || indent)) // last tag
                                    ctx.append("\n");
                              }
                              else if (node != null) {
                                 if (newLine || indent) {
                                    ctx.append("\n");
                                    ctx.indent(node.getNestingDepth());
                                 }
                              }
                           }
                        }
                     }
                     return;
                  }
                  else {
                     // For comparison ops, we store the operator as a string
                     // For type angle brackets, we're in the middle of a ClassType
                     // TODO: Need to refactor - parser should not depend on this class
                     if (!(sv instanceof BinaryExpression) && !(sv instanceof BinaryOperand))
                        return;
                  }

               case '+':
               case '-':
                  // For "x++ + 3"
                  if (prevChar == '+' || prevChar == '-')
                     ctx.append(" ");
                  break;
            }
            if (ctx.tagMode) {
               // TODO: what additional rules do we need for tag mode, the context set for parsing HTML tags
            }
            else  if (Character.isJavaIdentifierPart(prevChar)) {
               switch (nextChar) {
                  case '(':
                     Object prevValue = ctx.prevSemanticValue();

                     // For "do while" statements, the previous is a block - get the parent in that case.
                     if (prevValue instanceof BlockStatement)
                        prevValue = ((BlockStatement) prevValue).parentNode;
                     if (prevValue instanceof Statement) {
                        if (!((Statement) prevValue).spaceAfterParen())
                           return;
                     }
                     else  if (!(prevValue instanceof String) || !prevValue.equals("while"))
                        return;
                     break;
                  case '{':
                     if (newline)
                        suppressNewline = true;
                     break;
                  case ',':
                     return;
               }
               if (newline && !suppressNewline) {
                  ctx.append(FileUtil.LINE_SEPARATOR);
                  Object sv = ctx.nextSemanticValue();
                  int indent = ctx.getCurrentIndent() + 2;
                  if (sv instanceof ISemanticNode)
                     indent = ((ISemanticNode) sv).getChildNestingDepth();
                  ctx.indent(indent);
                  return;
               }
               /* Ignore suppress spacing if we have two identifier chars - e.g. New Expression */
               if (Character.isJavaIdentifierStart(nextChar)) {
                  ctx.append(" ");
                  return;
               }
            }
         }
         else {
            //System.err.println("*** Unreached case in spacing parse node");
         }
      }
      // beginning of file, no space needed
      else
         return;
      if (!ctx.suppressSpacing)
         ctx.append(" ");
   }

   public CharSequence toSemanticString() {
      return "";
   }

   // No state in this parse nodes so do not copy them
   public SpacingParseNode deepCopy() {
      return this;
   }

   public void updateSemanticValue(IdentityHashMap<Object, Object> oldNewMap) {
   }
}
