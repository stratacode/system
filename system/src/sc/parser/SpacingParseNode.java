/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.HTMLLanguage;
import sc.lang.ISemanticNode;
import sc.lang.SemanticNode;
import sc.lang.SemanticNodeList;
import sc.lang.html.Element;
import sc.util.FileUtil;

import java.util.IdentityHashMap;
import java.util.List;

public class SpacingParseNode extends FormattingParseNode {
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

   public String toDebugString() {
      return "<spacing>";
   }

   public String toString() {
      return " ";
   }

   public void format(FormatContext ctx) {
      if (ctx.replaceFormatting) {
         ctx.setReplaceNode(this);
      }
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

         int nextInt = ctx.nextChar();
         if (nextInt != -1) {  // This can happen if
            char nextChar = (char) nextInt;
            switch (nextChar) {
               case '/':
                  // Close tag for an HTML tag should not indent
                  if (ctx.tagMode && HTMLLanguage.getHTMLLanguage().validTagChar(prevChar))
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
                           HTMLLanguage htmlLang = HTMLLanguage.getHTMLLanguage();
                           boolean indent = htmlLang.INDENTED_SET.contains(sv.toString());
                           boolean newLine = htmlLang.NEWLINE_SET.contains(sv.toString());
                           if (indent || newLine) {
                              SemanticNode node = ctx.getNextSemanticNode();
                              if (node == null) {
                                 node = ctx.getPrevSemanticNode();
                                 if (node != null) // last tag
                                    ctx.appendWithStyle("\n");
                              }
                              else {
                                 ctx.appendWithStyle("\n");
                                 ctx.indentWithStyle(node.getNestingDepth());
                              }
                           }
                        }
                     }
                     return;
                  }
                  else {
                     // For comparison ops, where we need a space, the sv will be a string so make sure the string case does not return
                     // Currently we do want the space for BinaryExpression and BinaryOperand, handed in the SemanticNodeMethod
                     if (!(sv instanceof SemanticNode) || !((SemanticNode) sv).formatSpaceBeforeAngleBracket())
                        return;
                  }

               case '+':
               case '-':
                  // For "x++ + 3"
                  if (prevChar == '+' || prevChar == '-')
                     ctx.appendWithStyle(" ");
                  break;

               // If we have (bar), as in SQL Language, no space after the )
               case ',':
                  if (prevChar == ')')
                     return;
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
                     if (prevValue instanceof SemanticNode && ((SemanticNode) prevValue).formatLeftParenDelegateToParent())
                        prevValue = ((SemanticNode) prevValue).parentNode;
                     if (prevValue instanceof SemanticNode) {
                        if (!((SemanticNode) prevValue).spaceAfterParen())
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
                  ctx.appendWithStyle(FileUtil.LINE_SEPARATOR);
                  Object sv = ctx.nextSemanticValue();
                  int indent = ctx.getCurrentIndent() + 2;
                  if (sv instanceof ISemanticNode)
                     indent = ((ISemanticNode) sv).getChildNestingDepth();
                  ctx.indentWithStyle(indent);
                  return;
               }
               /* Ignore suppress spacing if we have two identifier chars - e.g. New Expression */
               if (Character.isJavaIdentifierStart(nextChar)) {
                  ctx.appendWithStyle(" ");
                  return;
               }
            }
         }
         else {
            // This correlates to end of file, or end of the token's format context at least.  No need for a space here...
            return;
         }
      }
      // beginning of file, no space needed
      else
         return;
      if (!ctx.suppressSpacing)
         ctx.appendWithStyle(" ");
   }
}
