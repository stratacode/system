/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

public class MarkdownLanguage extends BaseLanguage {

   /*
   SymbolChoice endOfLine = new SymbolChoice("/r/n", "/n", "/r", Symbol.EOF);
   SymbolChoice indent = new SymbolChoice("\t", "    ");

   Sequence blankLine = new Sequence(spacing, endOfLine);
   Sequence optBlankLines = new Sequence(OPTIONAL | REPEAT, blankLine);

   Sequence formattedLine = new Sequence(OPTIONAL | REPEAT, new Sequence(NOT, endOfLine), endOfLine);
   Sequence indentedLine = new Sequence(indent, formattedLine);
   Sequence indentedNonBlankLine = new Sequence(new Sequence(NOT | LOOKAHEAD, blankLine), indentedLine);
   Sequence verbatim = new Sequence(REPEAT, optBlankLines, new Sequence(REPEAT, indentedNonBlankLine));
   Sequence blockQuote = new Sequence(greaterThan, formattedLine, new Sequence(REPEAT | OPTIONAL, new Symbol(NOT | LOOKAHEAD, ">"), new Sequence(NOT | LOOKAHEAD, blankLine), formattedLine));
   SymbolChoice nonIndentSpace = new SymbolChoice(OPTIONAL, "   ", "  ", " ");

   SymbolChoice ruleChar = new SymbolChoice("*", "-", "=");
   Sequence horizontalRule = new Sequence(nonIndentSpace, new Sequence(ruleChar, spacing, ruleChar, new Sequence(REPEAT, spacing, ruleChar)));

   OrderedChoice blockElement = new OrderedChoice(blockQuote, verbatim, horizontalRule, heading, orderedList, bulletList, htmlBlock);
   Sequence blocks = new Sequence(REPEAT | OPTIONAL, optBlankLines, blockElement);

   Sequence document = new Sequence(blocks, new Symbol(Symbol.EOF));
   */
}
