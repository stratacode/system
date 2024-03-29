/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.lang.java.NonKeywordString;
import sc.layer.Layer;
import sc.parser.*;
import sc.util.FileUtil;

import java.util.Collections;
import java.util.Set;

/**
 * The BaseLanguage contains some core constructs useful in all languages digits, whitespace, and some higher
 * level parselets like Keyword, SemanticToken, etc.
 * It's possible to modify parselets, or override language features by overriding methods (e.g. to modify what
 * characters are valid in an identifier). This is helpful to reuse higher level features that use those parselets.
 * Like changing EOLComment for SQL to -- or the array bracket character between JS and Java.
 * It's also possible to clone and change a parselet rather than copying the original definition.
 */
public abstract class BaseLanguage extends Language implements IParserConstants {
   public static Set DEPS_KEYWORDS = Collections.emptySet();

   public boolean ignoreCaseInKeywords = false;

   public Set getKeywords() {
      return DEPS_KEYWORDS;
   }
   
   public SymbolChoice digits = new SymbolChoice(REPEAT | NOERROR);
   public SymbolChoice optDigits = new SymbolChoice(REPEAT | OPTIONAL | NOERROR);
   public SymbolChoice nonZeroDigit = new SymbolChoice(NOERROR);
   public SymbolChoice hexDigits = new SymbolChoice(REPEAT | NOERROR);
   public SymbolChoice hexDigit = new SymbolChoice(NOERROR);
   public SymbolChoice binaryDigits = new SymbolChoice(REPEAT | NOERROR);
   public SymbolChoice binaryDigit = new SymbolChoice(NOERROR);
   public SymbolChoice octalDigits = new SymbolChoice(REPEAT | NOERROR);
   public SymbolChoice octalDigit = new SymbolChoice();
   public Sequence optOctalDigit = new Sequence(OPTIONAL | NOERROR, octalDigit);
   public SymbolChoice fpChar = new SymbolChoice(LOOKAHEAD | NOERROR); // start chars for floating point
   public SymbolChoice intChar = new SymbolChoice(LOOKAHEAD | NOERROR); // start chars for integer

   public String [] hexLetters = {"a", "b", "c", "d", "e", "f"};

   protected void addDigitChar(String s, int i) {
      digits.addExpectedValue(s);
      optDigits.addExpectedValue(s);
      hexDigits.addExpectedValue(s);
      hexDigit.addExpectedValue(s);
      fpChar.addExpectedValue(s);
      intChar.addExpectedValue(s);
      if (i < 8)
      {
         octalDigits.addExpectedValue(s);
         octalDigit.addExpectedValue(s);
      }
      if (i != 0)
         nonZeroDigit.addExpectedValue(s);
      if (i < 2) {
         binaryDigits.addExpectedValue(s);
         binaryDigit.addExpectedValue(s);
      }

   }

   {
      for (int i = 0; i < 10; i++) {
         String s = String.valueOf(i);
         addDigitChar(s, i);
      }
      for (String hexLetter : hexLetters) {
         hexDigits.addExpectedValue(hexLetter);
         String u = hexLetter.toUpperCase();
         hexDigits.addExpectedValue(u);
         hexDigit.addExpectedValue(hexLetter);
         hexDigit.addExpectedValue(u);
      }
      fpChar.addExpectedValue(".");
   }

   public SymbolChoice lineTerminator = new SymbolChoice(NOERROR, "\r\n", "\r", "\n");
   {
      lineTerminator.defaultGenerateValue = "\n"; // The symbol to use when generating this node from scratch - i.e. the default terminator
   }
   public SymbolChoice notLineTerminators = new SymbolChoice(NOT | REPEAT | OPTIONAL | NOERROR, "\r\n", "\r", "\n", Symbol.EOF);

   IndexedChoice whiteSpaceChunk = new IndexedChoice("<whitespaceChunk>", NOERROR | OPTIONAL);
   {
      whiteSpaceChunk.put(" ", new Symbol(REPEAT, " "));
      whiteSpaceChunk.put("\t", new Symbol("\t"));
      whiteSpaceChunk.put("\f", new Symbol("\f"));
      whiteSpaceChunk.put("\r", lineTerminator);
      whiteSpaceChunk.put("\n", lineTerminator);
   }

   public IndexedChoice whiteSpace = whiteSpaceChunk.clone();
   {
      whiteSpace.setName("<whiteSpace>");
      whiteSpace.repeat = true;
   }

   public Sequence EOLComment = new Sequence("<eolComment>", NOERROR,
           new Symbol("//"),
           notLineTerminators,
           new OrderedChoice(lineTerminator, new Symbol(Symbol.EOF)));
   {
      EOLComment.styleName = "comment";
   }

   public IndexedChoice commentBody = new IndexedChoice("<commentBody>", OPTIONAL | REPEAT | NOERROR);
   {
      commentBody.put("*", new Sequence(NOERROR,new Symbol(NOERROR,"*"), new Symbol(NOERROR | NOT | LOOKAHEAD, "/")));
      commentBody.addDefault(new Sequence(NOERROR,new Symbol(NOERROR | NOT, "*"), new Symbol(NOERROR | LOOKAHEAD, Symbol.ANYCHAR)));
   }

   public Sequence blockComment = new Sequence("<blockComment>", NOERROR);
   { blockComment.add(new Symbol("/*"), commentBody, new Symbol("*/")); }
   {
      blockComment.styleName = "comment";
   }

   public IndexedChoice spacing = new IndexedChoice("<spacing>", REPEAT | OPTIONAL | NOERROR);
   {
      spacing.put(" ", whiteSpaceChunk);
      spacing.put("\t", whiteSpaceChunk);
      spacing.put("\f", whiteSpaceChunk);
      spacing.put("\r", whiteSpaceChunk);
      spacing.put("\n", whiteSpaceChunk);
      spacing.put("//", EOLComment);
      spacing.put("/*", blockComment);

      spacing.generateParseNode = new SpacingParseNode(spacing, false);
      spacing.alwaysReparse = true;
   }

   public IndexedChoice spacingEOL = (IndexedChoice) spacing.clone();
   {
      spacingEOL.setName("<spacingEOL>");
      spacingEOL.generateParseNode = new SpacingParseNode(spacingEOL, true);
   }

   public SymbolChoiceSpace periodSpace = new SymbolChoiceSpace(".");
   {
      // We want to match '.' but not '...' in the partial values case so excluding '..'
      periodSpace.addExcludedValues("..");
   }
   public Symbol period = new Symbol(".");
   public SymbolSpace semicolonEOL = new SymbolSpace(";", SKIP_ON_ERROR);
   public SymbolSpace semicolonEOL2 = new SymbolSpace(";", SKIP_ON_ERROR);
   {
      semicolonEOL.generateParseNode = new NewlineParseNode(semicolonEOL, ";");
      semicolonEOL2.generateParseNode = new NewlineParseNode(semicolonEOL2, ";\n");
   }
   // A semicolon followed by 2 newlines for package, imports
   public SymbolSpace semicolonNewline = new SymbolSpace(";", SKIP_ON_ERROR);
   {
      semicolonNewline.generateParseNode = new NewlineParseNode(semicolonNewline, ";") {
         public String getNewlineSeparator() {
            return FileUtil.LINE_SEPARATOR + FileUtil.LINE_SEPARATOR;
         }
      };
   }
   public SymbolSpace semicolon = new SymbolSpace(";");
   public SymbolSpace colon = new SymbolSpace(":");
   public SymbolSpace colonEOL = new SymbolSpace(":");
   {
      colonEOL.generateParseNode = new NewlineParseNode(colonEOL, ":");
   }
   public Sequence optSemicolon = new Sequence(OPTIONAL, new Symbol(";"), spacing);
   public SymbolSpace comma = new SymbolSpace(",");
   public SymbolSpace commaEOL = new SymbolSpace(",");
   {
      commaEOL.generateParseNode = new NewlineParseNode(commaEOL, ",");
   }
   public SymbolSpace openBrace = new SymbolSpace("{");
   public SymbolSpace openBraceEOL = new SymbolSpace("{");
   {
      openBraceEOL.generateParseNode = new NewlineParseNode(openBraceEOL, "{");
      openBraceEOL.pushIndent = true;
   }
   public SymbolSpace closeBrace = new SymbolSpace("}");
   public SymbolSpace closeBraceEOL = new SymbolSpace("}", SKIP_ON_ERROR);
   {
      closeBraceEOL.generateParseNode = new NewlineParseNode(closeBraceEOL, "}");
      closeBraceEOL.popIndent = true;
   }
   public SymbolSpace openParen = new SymbolSpace("(");

   public SymbolSpace closeParenSkipOnError = new SymbolSpace(")", SKIP_ON_ERROR);
   // TODO: should all closeParen's have skip on error?  Right now for cast expressions, we'll terminate those with the ; there's a chance the "skip on error" will lead to ambiguities when the body of the construct we are skipping has not enough info to differentiate it
   public SymbolSpace closeParen = new SymbolSpace(")");
   // Use this one for annotations
   public SymbolSpace openParenEOL = new SymbolSpace("(");
   public SymbolSpace closeParenEOL = new SymbolSpace(")", SKIP_ON_ERROR);
   {
      openParenEOL.generateParseNode = new NewlineParseNode(openParenEOL, "(");
      closeParenEOL.generateParseNode = new NewlineParseNode(closeParenEOL, ")");
   }
   // and this one for if statements where we need to indent
   public SymbolSpace closeParenEOLIndent = new SymbolSpace(")", SKIP_ON_ERROR);
   {
      NewlineParseNode pn = new NewlineParseNode(closeParenEOLIndent, ")");
      pn.needsIndent = true;
      closeParenEOLIndent.generateParseNode = pn;
   }
   public SymbolSpace openSqBracket = new SymbolSpace("[");
   public SymbolSpace closeSqBracket = new SymbolSpace("]");
   public SymbolSpace lessThan = new SymbolSpace("<");
   public SymbolSpace greaterThan = new SymbolSpace(">");
   public SymbolSpace greaterThanSkipOnError = new SymbolSpace(">", SKIP_ON_ERROR);
   public SymbolSpace equalSign = new SymbolSpace("=");
   public SymbolSpace asterix = new SymbolSpace("*");
   public SymbolSpace questionMark = new SymbolSpace("?");

   public Symbol startIdentifierChar = new Symbol("<startIdChar>", 0, Symbol.ANYCHAR) {
      protected String accept(SemanticContext ctx, Object value, int startIx, int endIx) {
         IString str = PString.toIString(value);
         if (str == null)
            return "Identifiers must be non null";
         if (str.length() == 1 && isIdentifierStartChar(str.charAt(0)))
            return null;
         return "Not a valid start identifier character";
      }
   };

   public class IdentSymbol extends Symbol {
      public IdentSymbol(String id, int options, String ev) {
         super(id, options, ev);
      }

      protected String accept(SemanticContext ctx, Object value, int startIx, int endIx) {
         IString str = PString.toIString(value);
         if (str == null)
            return "Identifiers must be non null";
         if (!repeat) {
            if (str.length() == 1 && isIdentifierPartChar(str.charAt(0)))
               return null;
         }
         else {
            int len = str.length();
            int i;
            for (i = 0; i < len; i++) {
               if (!isIdentifierPartChar(str.charAt(i)))
                  break;
            }
            if (i == len) {
               return null;
            }
         }
         return "Not a valid character for the inside of an identifier";
      }
   }

   // Hooks so that new languages can change the rules for identifier and reuse more parselets
   public boolean isIdentifierPartChar(char c) {
      return Character.isJavaIdentifierPart(c);
   }

   public boolean isIdentifierStartChar(char c) {
      return Character.isJavaIdentifierStart(c);
   }

   public Symbol identifierChar = new IdentSymbol("<idChar>", 0, Symbol.ANYCHAR);
   public Symbol nextIdentChars = new IdentSymbol("<nextIdChars>", OPTIONAL | REPEAT, Symbol.ANYCHAR);

   public Symbol alphaNumChar = new Symbol("<alphaNumChar>", 0, Symbol.ANYCHAR) {
      protected String accept(SemanticContext ctx, Object value, int startIx, int endIx) {
         IString str = PString.toIString(value);
         if (str == null)
            return "AlphaNum char must not non null";
         if (str.length() == 1) {
            char c = str.charAt(0);
            if (Character.isLetterOrDigit(c))
               return null;
         }
         return "Not alpha numeric";
      }

   };

   public Sequence identifier = new Sequence("<identifier>('','',)", startIdentifierChar, nextIdentChars, spacing) {
      /** Assumes we have validated the start and other chars already */
      protected String accept(SemanticContext ctx, Object value, int startIx, int endIx) {
         if (value instanceof IParseNode)
            value = ((IParseNode) value).getSemanticValue();

         // This is a sentinel type you can use to push even 'this' through as non-keyword.  Used to avoid needing to convert
         // to a selector expression during code-generation
         if (value instanceof NonKeywordString)
            return null;

         if (value != null && !(value instanceof StringToken))
            value = PString.toIString(value);
         if (getLanguage() == null)
            throw new IllegalArgumentException("*** No language defined for parselet: " + this);
         if (ignoreCaseInKeywords && value != null) {
            value = new ToLowerWrapper((IString) value);
         }
         if (!((BaseLanguage) getLanguage()).getKeywords().contains(value))
            return null;
         return "Identifiers cannot be keywords";
      }
   };
   {
      identifier.cacheResults = true;
   }

   /**
    * Used for skipOnError parselet and for TestLogFilter to match {alphaNumChar}_{id} - where normal identifier
    * would consume the _id part
    */
   public Sequence alphaNumString = new Sequence("<alphaNumString>('','',)", alphaNumChar,
           new Sequence("('')", REPEAT | OPTIONAL, alphaNumChar), spacing);

   /** Used for log filter patterns - to match either quote character */
   public SymbolChoice quoteChar = new SymbolChoice("\"", "'");

   /**
    * Use this to create a parselet for your repeating parselets skipOnError parselet.  It's used to consume the next error token while trying to skip out
    * of the body of something which is incomplete.  It consumes text which is safe to skip when we encounter an error parsing
    * the main parselet.  It must not match text which would ordinarily complete the parent.
    */
   public Parselet createSkipOnErrorParselet(String name, String... exitSymbols) {
      return new OrderedChoice(name + "(.,.)", alphaNumString, new Sequence(new SymbolChoice(NOT, exitSymbols), spacing));
   }

   public Sequence identifierSp = (Sequence) identifier.copy();
   {
      identifierSp.setName("('','',)");
   }

   public Sequence optIdentifier = new Sequence("(.)", OPTIONAL, identifier);

   public Sequence qualifiedIdentifier = new Sequence("('','')", identifier,
           new Sequence("('','')", OPTIONAL | REPEAT, new SymbolSpace("."), identifier));

   public Sequence optQualifiedIdentifier = new Sequence("('')", OPTIONAL, qualifiedIdentifier);

   OrderedChoice escapeSequence = new OrderedChoice("<escape>",
           new SymbolChoice("\\b","\\t","\\n","\\f","\\r","\\\"","\\\\","\\'"),
           new Sequence("('','','','','')", new Symbol("\\u"), hexDigit, hexDigit, hexDigit, hexDigit),
           new Sequence("('','','','')", new Symbol("\\"), octalDigit, optOctalDigit, optOctalDigit));

   public SymbolChoice escapedStringBody = new SymbolChoice(NOT, "\\", "\"", "\n", EOF);
   public Parselet escapedString = new OrderedChoice("('','')", OPTIONAL | REPEAT, escapeSequence, escapedStringBody);
   public Parselet escapedSingleQuoteString = new OrderedChoice("('','')", OPTIONAL | REPEAT, escapeSequence, new SymbolChoice(NOT, "\\", "'", "\n", EOF));
   {
      escapedString.styleName = escapedSingleQuoteString.styleName = "string";
      escapedSingleQuoteString.setLanguage(this);
   }

   /**
    * The keyword ensures that it is not followed by an identifier character - i.e. "returnSpace" would
    * be rejected if "return" is a keyword rather than a symbol.  Just add a not/lookahead rule to
    * negate that case.
    */
   public class KeywordSpace extends Sequence {
      public KeywordSpace(String name, int options, String symbol) {
         super(name, options | NOERROR);
         int symbolOpts = (options & IGNORE_CASE) | NOERROR;
         add(new Symbol(symbolOpts, symbol), new Sequence(NOT | LOOKAHEAD | NOERROR, identifierChar), spacing);
         styleName = "keyword";
      }
      public KeywordSpace(String symbol, int options) {
         this("<" + ((options & OPTIONAL) != 0 ? "opt_" : "") + ((options & IGNORE_CASE) != 0 ? "ic_" : "") + "keyword_" +  symbol + ">" + "('',,)", options, symbol);
      }
      public KeywordSpace(String symbol) {
         this(symbol, 0);
      }
   }

   public class KeywordNewline extends KeywordSpace {
      public KeywordNewline(String symbol) {
         super(symbol);
         set(2, spacingEOL);
      }
   }

   public class KeywordChoice extends Sequence {
      public KeywordChoice() {
         this(0);
      }

      public KeywordChoice(String name, int options, boolean doSpacing, String... expectedValues) {
         super(name, options | NOERROR);
         int symbolOpts = (options & IGNORE_CASE) | NOERROR;
         add(new SymbolChoice(symbolOpts, expectedValues), new Sequence(NOT | LOOKAHEAD | NOERROR, identifierChar));
         if (doSpacing)
            add(spacing);
         styleName = "keyword";
      }

      public KeywordChoice(int options, String... expectedValues) {
         super("('',)", options | NOERROR);
         int symbolOpts = (options & IGNORE_CASE) | NOERROR;
         add(new SymbolChoice(symbolOpts, expectedValues), new Sequence(NOT | LOOKAHEAD | NOERROR, identifierChar));
         styleName = "keyword";
      }

      public KeywordChoice(boolean doSpacing, String... expectedValues) {
         this(doSpacing ? "('',,)" : "('',)", 0, doSpacing, expectedValues);
      }

      public KeywordChoice(String... expectedValues) {
         this(0,expectedValues);
      }

      /** Adds a new choice after this is constructed. */
      public void add(String newValue) {
         ((SymbolChoice) parselets.get(0)).add(newValue);
      }
   }

   public class SymbolSpace extends Sequence {
      Symbol symbolParselet;
      /**
       * Creates a symbol space token with a deliminator for output purposes.  This
       * deliminator is appended to the symbol on output.
       *
       * @param symbol The symbol to match
       * @param options Any options
       * @param delim The deliminator
       */
      public SymbolSpace(String symbol, String delim, int options) {
         // We used to include NOERROR here but need errors for ; and in general these seem to be important parselets
         super("('',):(.," + delim + ")", options);
         int symbolOpts = options & IGNORE_CASE;
         symbolParselet = new Symbol(NOERROR | symbolOpts, symbol);
         add(symbolParselet, spacing);
      }
      public SymbolSpace(String symbol, int options) {
         super("('',)", options);
         int symbolOpts = options & IGNORE_CASE;
         symbolParselet = new Symbol(NOERROR | symbolOpts, symbol);
         add(symbolParselet, spacing);
      }

      public SymbolSpace(String symbol, String delim) {
         this(symbol, delim, NOERROR);
      }

      public SymbolSpace(String symbol) {
         this(symbol, NOERROR);
      }

      public String toString() {
         return "Symbol: '" + ((Symbol) parselets.get(0)).expectedValue + "'";
      }

      public void addExcludedValues(String... excludedValues) {
         symbolParselet.addExcludedValues(excludedValues);
      }

      public void setSymbolStyleName(String styleName) {
         symbolParselet.styleName = styleName;
      }
   }

   /** Like SymbolSpace but styled as a keyword */
   public class KeywordSymbolSpace extends SymbolSpace {
      public KeywordSymbolSpace(String symbol) {
         super(symbol);
         symbolParselet.styleName = "keyword";
      }
   }

   public class KeywordSymbol extends Symbol {
      public KeywordSymbol(String ev) {
         super(ev);
         styleName = "keyword";
      }
   }

   private final static GenerateError MISSING_SEMANTIC_VALUE = new GenerateError("Null value for semantic parselet");

   /**
    * The SemanticToken is parsed like SymbolSpace (a symbol followed by spacing).  When the model is generated
    * back into a language representation, the symbol is used only when its corresponding value is not null, or
    * if a boolean if the value is true.
    */
   public class SemanticToken extends SymbolSpace {
      SemanticToken(String symbol, int options) {
         super(symbol, options);
      }
      SemanticToken(String symbol) {
         this(symbol, 0);
      }

      public Object generate(GenerateContext ctx, Object value) {
         if (value == null)
            return ctx.error(this, MISSING_SEMANTIC_VALUE, value, 0);

         return super.generate(ctx, value);
      }
   }

   /**
    * A SemanticSequence is parsed like a regular sequence.  During generation however, if the semantic value
    * is null, this rule is not matched at all and no output is presented.  
    */
   public class SemanticSequence extends Sequence {
      SemanticSequence(String symbol, Parselet ...values) {
         this(symbol, 0, values);
      }

      SemanticSequence(String symbol, int options, Parselet ...values) {
         super(symbol, options, values);
      }

      public Object generate(GenerateContext ctx, Object value) {
         if (value == null)
            return ctx.error(this, MISSING_SEMANTIC_VALUE, value, 0);

         return super.generate(ctx, value);
      }
   }

   public class SymbolChoiceSpace extends Sequence {
      SymbolChoice choice;

      public SymbolChoiceSpace(String...values) {
         this("('',)",0,values);
      }
      public SymbolChoiceSpace(int options, String...values) {
         this("('',)", options, values);
      }
      public SymbolChoiceSpace(String name, int options, String...values) {
         super(name, options | NOERROR);
         int symOptions = options & IGNORE_CASE;
         choice = new SymbolChoice(NOERROR | symOptions, values);
         add(choice, spacing);
      }

      public SymbolChoiceSpace(String symbol) {
         this(0, symbol);
      }

      public void add(String...choices) {
         choice.add(choices);
      }

      public void set(String...choices) {
         choice.set(choices);
      }

      public void addExcludedValues(String... excluded) {
         choice.addExcludedValues(excluded);
      }
   }

   public class SemanticTokenChoice extends SymbolChoiceSpace {
      SemanticTokenChoice(String...values) {
         super(values);
      }

      public Object generate(GenerateContext ctx, Object value) {
         if (value == null)
            return MISSING_SEMANTIC_VALUE;

         return super.generate(ctx, value);
      }
   }

   public BaseLanguage() {
      this(null);
   }

   public BaseLanguage(Layer layer) {
      super(layer);
   }
}
