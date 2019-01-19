/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.layer.Layer;
import sc.parser.*;

/** TODO: For now, the sccss format is just the template language generating a string.  This is the hook for
    extending the template grammar so it can also parse CSS.  It might be nice to add syntax to embed expressions into the CSS so we'd manage
    the structure of the CSS as part of the language implementation.
    It could also be used to incrementally update the css on the client.   For now, the template language will work
    by itself and where we refresh page-by-page.  Since CSS is layered by design - rules override previous rules
    we can still do a ton this way, just more awkward syntax.

    TODO: Check unicode escape sequences in ident parses.
    TODO: Deal with the <!-- --> comments, (=) and other vestigal things?
  */
public class CSSLanguage extends TemplateLanguage {
    //////////////////////////////////////////////////
    // INSTANCE CONSTRUCTION & SINGLETON PATTERN
    //////////////////////////////////////////////////

    // Based on http://www.w3.org/TR/2003/WD-css3-syntax-20030813
    public final static CSSLanguage INSTANCE = new CSSLanguage();

    private boolean enableCSSParser = false;

    public CSSLanguage() {
       this(null);
    }

    public CSSLanguage(Layer layer) {
       super(layer);
       if (enableCSSParser) {
          templateBodyDeclarations.setName("([],[],[],[],[],[])");
          templateBodyDeclarations.addDefault(cssStyleSheet);
          setStartParselet(template);
       }
       addToSemanticValueClassPath("sc.lang.css");
       languageName = "SCCss";
       defaultExtension = "sccss";
    }

    public static CSSLanguage getCSSLanguage() {
       INSTANCE.initialize();
       return INSTANCE;
    }


    //////////////////////////////////////////////////
    // CUSTOM CLASS DEFINITIONS FOR CSS PARSING
    //////////////////////////////////////////////////

    // There is at least 1 case where we need to be able to match a string without case sensitivity (@charset).
    class CaseInsensitiveSequence extends Sequence {
        public CaseInsensitiveSequence(String stringSource){
            // Go through each letter.  If it is an alphabet-character then add a choice.
            for (Character aCharacter : stringSource.toCharArray()) {
                if (aCharacter.isLetter(aCharacter)){
                    String stringValue = aCharacter.toString();
                    this.add(new SymbolChoice(stringValue.toLowerCase(), stringValue.toUpperCase()));
                } else {
                    this.add(new Symbol(aCharacter.toString()));
                }
            }
        }
    }


    //////////////////////////////////////////////////
    // LEXICAL SCANNER ITEMS FROM CSS SYNTAX
    // This is sort of a weak distinction here, but
    // it helps keep it like the spec.
    // This is an attempt to separate what are tokens
    // in the CSS Spec.
    //////////////////////////////////////////////////

    // TODO: Consider moving some this block of stuff to BaseLanguage.
    Symbol at = new Symbol("@");

    Sequence stringLiteralSingleQuote = new Sequence("StringLiteral(,value,)", singleQuote, escapedSingleQuoteString, singleQuote);

    /** These are additions to the TemplateLanguage to parse CSS syntax.
     *  The syntax should work for CSS 2.1 and CSS 3.x.
     */
    OrderedChoice cssString = new OrderedChoice ("('','')",
            stringLiteralSingleQuote,
            stringLiteral
    );

    SymbolChoice nmstart = new SymbolChoice(0);
    {
        for (Character letter = 'a'; letter <= 'z'; letter++)
            nmstart.addExpectedValue(letter.toString());
        for (Character letter = 'A'; letter <= 'Z'; letter++)
            nmstart.addExpectedValue(letter.toString());
        for (Character letter = '0'; letter <= '9'; letter++)
            nmstart.addExpectedValue(letter.toString());
        nmstart.addExpectedValue("_");
        for (Character letter = '\u0200'; letter <= '\u0377'; letter++)
            nmstart.addExpectedValue(letter.toString());
        nmstart.addExpectedValue("\\");
    }

    SymbolChoice nmchar = new SymbolChoice(OPTIONAL | REPEAT);
    {
        for (Character letter = 'a'; letter <= 'z'; letter++)
            nmchar.addExpectedValue(letter.toString());
        for (Character letter = 'A'; letter <= 'Z'; letter++)
            nmchar.addExpectedValue(letter.toString());
        for (Character letter = '0'; letter <= '9'; letter++)
            nmchar.addExpectedValue(letter.toString());
        nmchar.addExpectedValue("_");
        for (Character letter = '\u0200'; letter <= '\u0377'; letter++)
            nmchar.addExpectedValue(letter.toString());
        nmchar.addExpectedValue("\\");
        nmchar.addExpectedValue("-");
    }

    OrderedChoice uriString = new OrderedChoice ("('','','')",
            stringLiteralSingleQuote,
            stringLiteral,
            new OrderedChoice("('','')", OPTIONAL | REPEAT, escapeSequence, new SymbolChoice(NOT, ")", EOF))
    );

    Sequence cssURI = new Sequence("URI(,,,uriLocation,,)", new CaseInsensitiveSequence("url"),
            openParen, spacing, uriString, spacing, closeParen);

    // Later, http://stackoverflow.com/questions/2812072/allowed-characters-for-css-identifiers
    // This is IDENT in the spec.
    Sequence ident = new Sequence ("('','','')", new Symbol(OPTIONAL, "-"), nmstart, nmchar){
        protected String accept(SemanticContext ctx, Object value, int startIx, int endIx){
            String stringValue = null;

            if (value instanceof ParentParseNode) {
                ParentParseNode ppn = (ParentParseNode)value;

                StringToken tokenValue = (StringToken)ppn.getSemanticValue();
                stringValue = tokenValue.toString();
            } else {
                stringValue = ((StringToken)value).toString();
            }

            // Make sure nothing was accidentally picked-up that should not be.
            if (stringValue.equalsIgnoreCase("url"))
                return "Identifier cannot be url keyword.";

            //TODO: Make sure the escape sequences are in a valid-range.
            return null;
        }
    };

    // STATUS: DONE (I think)
    Sequence hashName = new Sequence("('','')", new Symbol("#"), nmchar);

    Symbol includes = new Symbol("~=");

    Symbol dashMatch = new Symbol("|=");

    OrderedChoice number = new OrderedChoice (
            new Sequence("('','','')", optDigits, period, digits),
            new Sequence("(.)", digits));

    Sequence percentage = new Sequence ("NumericMeasure(number, measure)", number, new Symbol("%"));

    SymbolChoice lengthSuffixes = new SymbolChoice();
    {
        lengthSuffixes.addExpectedValue("px");
        lengthSuffixes.addExpectedValue("cm");
        lengthSuffixes.addExpectedValue("mm");
        lengthSuffixes.addExpectedValue("in");
        lengthSuffixes.addExpectedValue("pt");
        lengthSuffixes.addExpectedValue("pc");
    }
    Sequence length = new Sequence ("NumericMeasure(number, measure)", number, lengthSuffixes);

    Sequence ems = new Sequence("NumericMeasure(number, measure)", number, new Symbol("em"));

    Sequence exs = new Sequence("NumericMeasure(number, measure)", number, new Symbol("ex"));

    SymbolChoice angleSuffixes = new SymbolChoice();
    {
        angleSuffixes.addExpectedValue("deg");
        angleSuffixes.addExpectedValue("rad");
        angleSuffixes.addExpectedValue("grad");
    }
    Sequence angle = new Sequence("NumericMeasure(number, measure)", number, angleSuffixes);

    SymbolChoice timeSuffixes = new SymbolChoice();
    {
        timeSuffixes.addExpectedValue("ms");
        timeSuffixes.addExpectedValue("s");
    }
    Sequence time = new Sequence ("NumericMeasure(number, measure)", number, timeSuffixes);

    SymbolChoice frequencySuffixes = new SymbolChoice();
    {
        frequencySuffixes.addExpectedValue("Hz");
        frequencySuffixes.addExpectedValue("kHz");
    }
    Sequence freq = new Sequence("NumericMeasure(number, measure)", number, frequencySuffixes);



    //////////////////////////////////////////////////
    // CSS GRAMMAR
    // Again weak distinction, but easier to follow...
    // This will be sort of like the spec, but in
    // reverse, as things get used after declarations.
    //////////////////////////////////////////////////

    Sequence hexColor = new Sequence ("(.,)", hashName, spacing){
        protected String accept(SemanticContext ctx, Object value, int startIx, int endIx){
            String stringValue = null;

            if (value instanceof ParentParseNode) {
                ParentParseNode ppn = (ParentParseNode)value;

                StringToken tokenValue = (StringToken)ppn.getSemanticValue();
                stringValue = tokenValue.toString();
            } else {
                stringValue = ((StringToken)value).toString();
            }

            if (stringValue.length() != 4 &&
                    stringValue.length() != 7)
                return "Hex sequence is not #<3-VAL> or #<6-VAL>.";

            for (char aCharacter : stringValue.substring(1).toLowerCase().toCharArray()) {
                if (!((aCharacter >= 'a' && aCharacter <= 'f') ||
                        (aCharacter >= '0' && aCharacter <= '9')))
                    return "Hex sequence has a non-hex character, not in 0-9, a-f or A-F range.";
            }
            return null;
        }
    };

    // STATUS: DONE
    OrderedChoice operator = new OrderedChoice (OPTIONAL,
            new Sequence ("(.,)", new Symbol("/"), spacing),
            new Sequence ("(.,)", comma, spacing));

    // STATUS: DONE
    SymbolChoice unaryOperator = new SymbolChoice(OPTIONAL, "-", "+");

    // Status: DONE
    Sequence expr = new Sequence("Expr(term, otherTerms)");

    Sequence functionStart = new Sequence ("(.,)", ident, openParen);
    // STATUS: DONE
    Sequence function = new Sequence ("Function(name,,,expression,,)", ident, new Symbol("("), spacing, expr, new Symbol(")"), spacing);

    Sequence term = new Sequence ("Term(unaryOperator,termValue)", unaryOperator,
            new OrderedChoice(
                    new Sequence ("(.,)", percentage, spacing),
                    new Sequence ("(.,)", length, spacing),
                    new Sequence ("(.,)", ems, spacing),
                    new Sequence ("(.,)", exs, spacing),
                    new Sequence ("(.,)", angle, spacing),
                    new Sequence ("(.,)", time, spacing),
                    new Sequence ("(.,)", freq, spacing),
                    new Sequence ("(.,)", number, spacing),
                    function,
                    hexColor,
                    new Sequence ("(.,)", cssString, spacing),
                    new Sequence ("(.,)", ident, spacing),
                    new Sequence ("(.,)", cssURI, spacing)
            )
    );

    {
        expr.add(term, new Sequence ("([],[])", OPTIONAL | REPEAT, operator, term));
    }

    // STATUS: DONE
    Sequence prio = new Sequence ("Important(,,,)", OPTIONAL, new Symbol("!"), spacing, new CaseInsensitiveSequence("important"), spacing);

    // STATUS: DONE
    Sequence property = new Sequence("(.,)", ident, spacing);

    // STATUS: DONE - tweaks by Jeff: use one "declarations" property by adding one additional parselet to wrap up the individual and list elements into one list
    Sequence declaration = new Sequence("Declaration(property,,,expr,prio)", OPTIONAL, property, colon, spacing, expr, prio);
    Sequence declarations = new Sequence("(,[],[],)", openBraceEOL, declaration,
               new Sequence("(,,[])", OPTIONAL | REPEAT, semicolonEOL, spacing, declaration), closeBraceEOL);

    // STATUS: DONE
    Sequence pseudo = new Sequence("Pseudo(,pseudo)", colon, new OrderedChoice("<pseudo.1>", ident,
            new Sequence("PseudoFunction(function,,ident,,)", functionStart, spacing, ident, spacing, closeParen)));

    // STATUS: DONE (I think)
    Sequence attrib = new Sequence("Attrib(,,ident,,optional,)",
            openSqBracket,
            spacing, ident, spacing,
            new Sequence ("AttribOptional(firstPart,,secondPart,)", OPTIONAL,
                    new OrderedChoice(equalSign, includes, dashMatch), spacing,
                    new OrderedChoice(ident, cssString), spacing),
            closeSqBracket);

    // STATUS: DONE
    // Note: Not using asterix since it seems to be slightly different.
    OrderedChoice elementName = new OrderedChoice(OPTIONAL, ident, new Symbol("*"));

    Sequence clazz = new Sequence ("('','')", new Symbol("."), ident);

    Sequence simpleSelector = new Sequence ("SimpleSelector(elementName, additional,)", elementName,
            new OrderedChoice("([],[],[],[])", OPTIONAL | REPEAT, hashName, clazz, attrib, pseudo), spacing);

    Sequence cssSelector = new Sequence ("CSSSelector(simpleSelector, additionalSelectorTerms)", simpleSelector,
            new Sequence("([],[])", OPTIONAL | REPEAT,
                    new OrderedChoice(OPTIONAL,
                            new Sequence("(.,)", new Symbol("+"), spacing),
                            new Sequence("(.,)", new Symbol(">"), spacing)),
                    simpleSelector));

    Sequence selectors = new Sequence("<otherSelectors>(,,[])", OPTIONAL | REPEAT, comma, spacing, cssSelector);

    Sequence ruleset = new Sequence("RuleSet(cssSelector, otherSelectors, declarations)",
            cssSelector, selectors, declarations);

    Sequence ruleSets = new Sequence("<rulesets>([])", OPTIONAL | REPEAT, ruleset);

    Sequence pseudoPage = new Sequence("<pseudoPage>('','')", OPTIONAL, new Symbol(":"), ident);

    Sequence medium = new Sequence ("<medium>(.,)", ident, spacing);
    Sequence mediums = new Sequence ("<otherMediums>(,,[])",
            OPTIONAL | REPEAT, comma, spacing, medium);

    Sequence fontFaceAtRule = new Sequence("FontFaceAtRule(,,,declarations,)",
            at, new CaseInsensitiveSequence("font-face"),
            spacing, declarations, spacing);

    Sequence pageAtRule = new Sequence("PageAtRule(,,,ident,pseudoPage,,declarations)",
            at, new CaseInsensitiveSequence("page"), spacing, new Sequence("(.)", OPTIONAL, ident), pseudoPage, spacing,
            declarations);

    Sequence mediaAtRule = new Sequence ("MediaAtRule(,,,medium,otherMediums,,ruleSets,,)",
            at, new CaseInsensitiveSequence("media"), spacing, medium, mediums, openBraceEOL, ruleSets, closeBrace, spacingEOL);

    Sequence namespaceAtRules = new Sequence("NamespaceAtRule(,,,identifier,value,,)", OPTIONAL | REPEAT,
            at, new CaseInsensitiveSequence("namespace"), spacing,
            new Sequence ("(.,)", OPTIONAL, ident, spacing), new OrderedChoice (cssURI, cssString), spacing, semicolonEOL);

    Sequence importAtRules = new Sequence("ImportAtRule(,,,importAt,,medium,otherMediums,)", OPTIONAL | REPEAT,
            at, new CaseInsensitiveSequence("import"), spacing,
            new OrderedChoice (cssURI, cssString), spacing,
            new Sequence ("(.)", OPTIONAL, medium),
            mediums,
            semicolonEOL);

    // ENHANCEMENT: Use the family-names defined at http://www.iana.org/assignments/character-sets?
    Sequence charsetAtRule = new Sequence("CharSetAtRule(,,,charsetFamily,,)", OPTIONAL,
            at, new CaseInsensitiveSequence("charset"), spacing, cssString, spacing, semicolonEOL);

    OrderedChoice styleStatements =
            new OrderedChoice("([],[],[],[])", OPTIONAL | REPEAT, ruleset, mediaAtRule, pageAtRule, fontFaceAtRule);

    public Sequence cssStyleSheet = new Sequence ("CSSStyleSheet(, charsetRule, importAtRules, namespaceAtRules, styleStatements)",
            spacing,
            charsetAtRule,
            importAtRules,
            namespaceAtRules,
            styleStatements);
}
