/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.lang.java.*;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.obj.Constant;
import sc.type.TypeUtil;
import sc.parser.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** This is the main implementation class for the Java parser and grammar.  Like all languages 
 * built with the parselets framework, it exposes fields for each Parselet.  Some of these are
 * marked "public" so that you can parse and manipulate individual language elements like a
* statement or an initializer
 */
public class JavaLanguage extends BaseLanguage implements IParserConstants {
   protected static final String[] JAVA_KEYWORDS = {
           "abstract",  "continue",  "for",         "new",        "switch",
           "assert",    "default",   "if",          "package",    "synchronized",
           "boolean",   "do",        "goto",        "private",    "this",
           "break",     "double",    "implements",  "protected",  "throw",
           "byte",      "else",      "import",      "public",     "throws",
           "case",      "instanceof",  "return",     "transient", "catch",
           "extends",   "int",         "short",      "try",       "char",
           "final",     "interface",   "static",     "void",      "class",
           "finally",   "long",        "strictfp",   "volatile",  "const",
           "float",     "native",      "super",      "while",     "enum"};

   protected static final List<String> CLASS_LEVEL_KEYWORDS = Arrays.asList("class", "enum", "const", "final", "abstract", "native", "volatile", "public", "private", "interface", "transient", "synchronized");

   protected static Set<IString> JAVA_KEYWORD_SET = new HashSet<IString>(Arrays.asList(PString.toPString(JAVA_KEYWORDS)));

   protected static Set<IString> JAVA_VARNAME_KEYWORD_SET = new HashSet<IString>(JAVA_KEYWORD_SET);
   static {
      JAVA_VARNAME_KEYWORD_SET.remove(new PString("this"));
      JAVA_VARNAME_KEYWORD_SET.remove(new PString("super"));
   }

   public static boolean fastGenExpressions = true;
   public static boolean fastGenMethods = true;

   public final static String SCJ_SUFFIX = "scj";

   public Set getKeywords() {
      return JAVA_KEYWORD_SET;
   }

   public Set getVarNameKeywords() {
      return JAVA_VARNAME_KEYWORD_SET;
   }

   KeywordSpace newKeyword = new KeywordSpace("new");
   KeywordSpace finalKeyword = new KeywordSpace("final");

   Sequence openCloseSqBrackets = new Sequence("('','')", OPTIONAL | REPEAT, openSqBracket, closeSqBracket);
   {
      openCloseSqBrackets.minContentSlot = 1;
   }
   Sequence dotStarTail = new Sequence("('','')", OPTIONAL, periodSpace, asterix);

   Sequence importDeclaration = new Sequence("ImportDeclaration(,staticImport,identifier,)",
                            new KeywordSpace("import"),
                            new KeywordSpace("static", OPTIONAL),
                            new Sequence("('','')", qualifiedIdentifier, dotStarTail),
                            semicolonEOL);

   Sequence imports = new Sequence("([])", REPEAT | OPTIONAL, importDeclaration);

   KeywordChoice primitiveTypeName = new KeywordChoice("boolean", "byte", "short", "char", "int", "float", "long", "double", "void");

   Sequence primitiveType = new Sequence("PrimitiveType(typeName,)", primitiveTypeName, spacing);

   // TODO: is there a data type mismatch here?
   OrderedChoice typeName = new OrderedChoice("<typeName>", primitiveType, qualifiedIdentifier);

   public Sequence type = new Sequence("<type>(.,arrayDimensions)");

   Sequence argType = new Sequence("(.)", SKIP_ON_ERROR, type) {
      protected String acceptSemanticValue(Object value) {
         if (value instanceof ClassType)
             ((ClassType) value).typeArgument = true;
         return null;
      }
   };

   OrderedChoice typeArgument = new OrderedChoice("<typeArgument>", argType,
                // The questionMark mapping here is only needed so the generation matches this node properly
                new Sequence("ExtendsType(questionMark,*)",OPTIONAL, questionMark,
                             new Sequence("(operator,typeArgument)", OPTIONAL, new SemanticTokenChoice("extends", "super"), argType)));

   Sequence typeArgumentList = new Sequence("([],[])", OPTIONAL, typeArgument, new Sequence("(,[])", OPTIONAL | REPEAT, comma, typeArgument));
   // Note: not using greaterThanSkipOnError here and other type arg lists because it confuses the parsing of partial binary expressions
   Sequence typeArguments = new Sequence("<typeArguments>(,.,)", lessThan, typeArgumentList, greaterThan);
   {
      typeArguments.ignoreEmptyList = false;
   }
   public Sequence optTypeArguments = new Sequence("(.)", OPTIONAL, typeArguments);

   Sequence classTypeChainedTypes = new Sequence("ClassType(, typeName, typeArguments)", OPTIONAL | REPEAT, periodSpace, identifier, optTypeArguments);

   public Sequence classOrInterfaceType =
           new Sequence("ClassType(typeName, typeArguments, chainedTypes)", identifier, optTypeArguments, classTypeChainedTypes);
   {
      type.set(new OrderedChoice(classOrInterfaceType, primitiveType), openCloseSqBrackets);
   }

   Sequence typeBound = new Sequence("BoundType(baseType, boundTypes)", SKIP_ON_ERROR, type, new Sequence("(,[])", OPTIONAL, new SymbolSpace("&"), type));
   Sequence typeList = new Sequence("([],[])", type, new Sequence("(,[])", OPTIONAL | REPEAT, comma, type));

   // Forward declarations see below for definition
   public Sequence assignmentExpression = new ChainedResultSequence("(lhs,.)");
   // Java8 Only
   public Sequence lambdaExpression = new Sequence("LambdaExpression(lambdaParams,,lambdaBody)");

   public OrderedChoice expression = new OrderedChoice(lambdaExpression, assignmentExpression) {
      public Object generate(GenerateContext ctx, Object value) {
         if (!fastGenExpressions)
            return super.generate(ctx, value);

         //if (!ctx.finalGeneration)
         //   return super.generate(ctx, value);
         if (!(value instanceof Expression))
            return ctx.error(this, NO_MATCH_ERROR, value, 0);

         Expression expr = ((Expression) value);
         if (expr.isLeafStatement())
             return expr.toGenerateString();
         return super.generate(ctx, value);
      }
   };

   {
      expression.disableTagMode = true;
   }

   public Sequence optExpression = new Sequence("(.)", OPTIONAL, expression);

   public Sequence qualifiedType =
           new Sequence("ClassType(typeName, chainedTypes)", identifier,
                   new Sequence("ClassType(,typeName)", OPTIONAL | REPEAT, period, identifier));
   Sequence qualifiedTypeList =
           new Sequence("([],[])", qualifiedType, new Sequence("(,[])", OPTIONAL | REPEAT, comma, qualifiedType));

   Parselet skipBodyError = createSkipOnErrorParselet("<skipBodyError>", "}", Symbol.EOF);

   // When parsing for errors with type declarations, we need to consume even the close } because we are looking for EOF as an exit parselet
   Parselet skipTypeDeclError = createSkipOnErrorParselet("<skipTypeDeclError>", Symbol.EOF);

   public Sequence classBody = new Sequence("<classBody>(,[],)");
   public OrderedChoice classDeclarationWithoutModifiers = new OrderedChoice();
   public Sequence classDeclaration = new Sequence("<classDeclaration>(modifiers,.)");
   public Sequence block = new Sequence("BlockStatement(,statements,)");
   public OrderedChoice blockStatements = new OrderedChoice("([],[],[])", REPEAT | OPTIONAL);
   {
      blockStatements.disableTagMode = true;
   }
   Sequence annotationTypeDeclaration = new Sequence("AnnotationTypeDeclaration(,,typeName,body)");
   public Sequence arrayInitializer = new Sequence("ArrayInitializer(,initializers,)");
   Sequence optArrayInitializer = new Sequence("(.)", OPTIONAL, arrayInitializer);

   public Sequence expressionList = new Sequence("([],[])", expression,
                                          new Sequence("(,[])", OPTIONAL | REPEAT, comma, expression));
   Sequence optExpressionList = (Sequence) expressionList.copyWithOptions(OPTIONAL);
   Sequence arguments = new SemanticSequence("<arguments>(,[],)", openParen, optExpressionList, closeParenSkipOnError);
   // TODO: this should work and be a little faster but causes an error when generating the LayeredSystem.java file
   //Sequence optArguments = (Sequence) arguments.copyWithOptions(OPTIONAL);
   Sequence optArguments = new Sequence("(.)", OPTIONAL, arguments);
   {
      // If there are no arguments here, we still need to generate output for the "arguments" sequence so we do not
      // skip generation if there is an empty list.
      optArguments.ignoreEmptyList = false;
   }
   Sequence optClassBody = (Sequence) classBody.copyWithOptions(OPTIONAL);
   Sequence classCreatorRest = new Sequence("(arguments, classBody)", arguments, optClassBody);
   OrderedChoice typeOrQuestion = new OrderedChoice(argType, new Sequence("ExtendsType(questionMark)", questionMark));
   Sequence simpleTypeArguments = new Sequence("(,[],)", OPTIONAL, lessThan,
           new Sequence("([],[])", OPTIONAL, typeOrQuestion, new Sequence("(,[])",OPTIONAL | REPEAT, comma, typeOrQuestion))
           , greaterThan);
   Sequence innerCreator =
           new Sequence("NewExpression(typeArguments, typeIdentifier, *)", simpleTypeArguments, identifier, classCreatorRest);

   public Sequence arrayElementExpression = new Sequence("ArrayElementExpression(arrayDimensions)", new Sequence("(,[],)", REPEAT, openSqBracket, expression, closeSqBracket));

   OrderedChoice identifierSuffix = new OrderedChoice(OPTIONAL,
         arrayElementExpression,
         new Sequence("IdentifierExpression(arguments)", arguments),
         new Sequence("TypedMethodExpression(,typeArguments,typedIdentifier,arguments)", periodSpace, simpleTypeArguments, identifier, arguments),
         new Sequence("(,,.)", periodSpace, newKeyword, innerCreator));

   Sequence parenExpression = new Sequence("ParenExpression(,expression,)", openParen, expression, closeParenSkipOnError);
   Sequence parenExpressionEOL = new Sequence("ParenExpression(,expression,)", openParen, expression, closeParenEOLIndent);

   KeywordChoice booleanLiteral = new KeywordChoice("BooleanLiteral(value,,)", 0, true, "true", "false");
   KeywordSpace nullLiteral = new KeywordSpace("NullLiteral(value,,)", 0, "null");

   SymbolChoice integerTypeSuffix = new SymbolChoice(OPTIONAL, "l","L");
   public SymbolChoice floatTypeSuffix = new SymbolChoice("f","F","d","D");
   public Symbol notUnderscore = new Symbol(LOOKAHEAD | NOT, "_");
   Sequence exponent = new Sequence("('', '',,'')", new SymbolChoice("e","E"), new SymbolChoice(OPTIONAL, "+", "-"), notUnderscore, digits);
   Sequence optExponent = (Sequence) exponent.copyWithOptions(OPTIONAL);
   Sequence optFloatTypeSuffix = new Sequence("('')", OPTIONAL, floatTypeSuffix);

   SymbolChoice hexPrefix = new SymbolChoice("0x","0X");

   //
   // To define an Integer, we have different representations.  Each of these lower-level parselets maps to a '*' production of integerLiteral.  The
   // grammar will call setHexValue, or setBinaryValue, etc. so we can convert from String to integer value and keep track of what type of IntegerLiteral we
   // have to go in the opposite direction for formatting when the model object changes.
   //

   Sequence hexLiteral = new Sequence("(hexPrefix,,hexValue)", hexPrefix, notUnderscore, hexDigits);
   SymbolChoice binaryPrefix = new SymbolChoice("0b","0B");
   // Aded in Java7
   Sequence binaryLiteral = new Sequence("(binaryPrefix,,binaryValue)", binaryPrefix, notUnderscore, binaryDigits);
   Sequence octalLiteral = new Sequence("(octal,,octalValue)", new Symbol("0"), notUnderscore, octalDigits);

   {
      // Java7 let's you put an _ inside of the literal.
      addDigitChar("_", 0);
   }
   Sequence decimalLiteral = new Sequence("(decimalValue)", new OrderedChoice(new Symbol("0"), new Sequence("('','')", nonZeroDigit, optDigits)));

   public OrderedChoice floatingPointLiteral = new OrderedChoice("<floatingPointLiteral>",
         new Sequence("(,'','','','','')", notUnderscore, digits, period, new Sequence("(,'')", OPTIONAL, notUnderscore, digits), optExponent, optFloatTypeSuffix),
         new Sequence("('',,'','','')", period, notUnderscore, digits, optExponent, optFloatTypeSuffix),
         new Sequence("(,'','','')", notUnderscore, digits, exponent, optFloatTypeSuffix),
         new Sequence("(,'','','')", notUnderscore, digits, optExponent, floatTypeSuffix),
         new Sequence("('',,'','','')", hexPrefix, notUnderscore, hexDigits, new Sequence("('','')", OPTIONAL, period, new Sequence("('')", OPTIONAL, hexDigits)),
                      new Sequence("('','',,'','')", new SymbolChoice("p","P"), new Symbol(OPTIONAL, "-"), notUnderscore, digits, optFloatTypeSuffix)));

   // We put a lookahead check for a digit or . all of the first chars in a floating point literal.  
   Sequence fasterFloatingPointLiteral = new Sequence("FloatLiteral(,value)", fpChar, floatingPointLiteral);

   OrderedChoice escapeSequence = new OrderedChoice("<escape>",
         new SymbolChoice("\\b","\\t","\\n","\\f","\\r","\\\"","\\\\","\\'"),
         new Sequence("('','','','','')", new Symbol("\\u"), hexDigit, hexDigit, hexDigit, hexDigit),
         new Sequence("('','','','')", new Symbol("\\"), octalDigit, optOctalDigit, optOctalDigit));

   Symbol singleQuote = new Symbol("'");
   Symbol doubleQuote = new Symbol("\"");

   public SymbolChoice escapedStringBody = new SymbolChoice(NOT, "\\", "\"", "\n", EOF);
   public Parselet escapedString = new OrderedChoice("('','')", OPTIONAL | REPEAT, escapeSequence, escapedStringBody);
   public Parselet escapedSingleQuoteString = new OrderedChoice("('','')", OPTIONAL | REPEAT, escapeSequence, new SymbolChoice(NOT, "\\", "\'", "\n", EOF));
   {
      escapedString.styleName = escapedSingleQuoteString.styleName = "string";
   }

   public Sequence stringLiteral = new Sequence("StringLiteral(,value,)", doubleQuote, escapedString, doubleQuote);

   public Sequence characterLiteral =
         new Sequence("CharacterLiteral(,value,)", singleQuote,
                      new OrderedChoice(escapeSequence,
                                        new SymbolChoice(NOT, "\\", "'", EOF)),
                      singleQuote);

   public Sequence integerLiteral = new Sequence("IntegerLiteral(*,typeSuffix)", new OrderedChoice(hexLiteral, binaryLiteral, octalLiteral, decimalLiteral),
                                          integerTypeSuffix);

   // One up front lookahead check to be sure the first char is for an integer before going into the choice
   Sequence fasterIntegerLiteral = new Sequence("(,.)", intChar, integerLiteral);

   IndexedChoice rawLiteral = new IndexedChoice("<rawLiteral>");
   {
      rawLiteral.put("'", characterLiteral);
      rawLiteral.put("\"", stringLiteral);
      rawLiteral.put("true", booleanLiteral);
      rawLiteral.put("false", booleanLiteral);
      rawLiteral.put("null", nullLiteral);
      rawLiteral.addDefault(fasterFloatingPointLiteral, fasterIntegerLiteral);
   }

   Sequence literal = new Sequence("<literal>(.,)", rawLiteral, spacing);

   OrderedChoice typeIdentifier = new OrderedChoice(primitiveType, qualifiedIdentifier);

   Sequence varIdentifier = new Sequence("('','',)",startIdentifierChar, nextIdentChars, spacing) {
      /** Assumes we have validated the start and other chars already */
      protected String accept(SemanticContext ctx, Object value, int startIx, int endIx) {
         if (value instanceof IParseNode)
            value = ((IParseNode) value).getSemanticValue();

         if (value == null)
            return "Identifiers must be non empty";

         if (!(value instanceof IString))
            value = PString.toIString(value.toString());

         if (!(value instanceof NonKeywordString) && getVarNameKeywords().contains((IString) value))
            return "Variable identifiers cannot be keywords";

         return null;
      }
   };

   public Sequence arrayDims = new Sequence("(,[],)", REPEAT, openSqBracket, optExpression, closeSqBracket);
   {
      arrayDims.allowNullElements = true; // Here we want to count [] as [1] = {null}.  For params () though that's [0]
   }
   Sequence arrayCreatorRest = new Sequence("(arrayDimensions, arrayInitializer)",
         arrayDims,
         optArrayInitializer);

   public Sequence newExpression =  new Sequence("NewExpression(,typeIdentifier, typeArguments,*)",
             newKeyword, typeIdentifier, simpleTypeArguments, new OrderedChoice(classCreatorRest, arrayCreatorRest));

   // varIdentifier includes "this" and "super"
   // Note on this definition - we define a result class here and also propagate one.  If identifierSuffix returns
   // null we'll use the result class on this parent node and create a default IdentifierExpression.   If not, we
   // just set the identifiers property on the identifierSuffix object.
   public Sequence remainingIdentifiers = new Sequence("(,[])", REPEAT | OPTIONAL, periodSpace, identifier);
   {
      // When matching "a." with enablePartialValues we need an empty string in there to tell the difference between "a" and "a."
      remainingIdentifiers.allowEmptyPartialElements = true;
   }
   public Sequence identifierExpression = new Sequence("IdentifierExpression(identifiers, .)",
        new Sequence("([],[])", varIdentifier, remainingIdentifiers),
        identifierSuffix);

   Sequence classValueExpression = new Sequence("ClassValueExpression(typeIdentifier, arrayBrackets, )", typeIdentifier, openCloseSqBrackets, new KeywordSpace(".class"));
   {
      // Don't allow just the type to parse as a class value expression in partial values mode - it should have .class at the end
      classValueExpression.minContentSlot = 2;
   }

   // Java8 Only
   Sequence methodReference = new Sequence("MethodReference(reference,,typeArguments, methodName)");


   public IndexedChoice primary = new IndexedChoice("<primary>");

   {
      primary.put("(", parenExpression);
      primary.put("new", newExpression);
      primary.put("'", literal);
      primary.put("\"", literal);
      primary.put("true", literal);
      primary.put("false", literal);
      primary.put("null", literal);
      primary.addDefault(classValueExpression, identifierExpression, literal);
      // Caching because MethodReference may well match a primary then reject it - removing this one slows down nested expressions exponentially
      primary.cacheResults = true;
   }

   // Forward
   IndexedChoice unaryExpressionNotPlusMinus = new IndexedChoice();
   //{ unaryExpressionNotPlusMinus.recordTime = true; }
   IndexedChoice unaryExpression = new IndexedChoice("<unaryExpression>");

   // TODO: combine these for a speedup
   OrderedChoice castExpression = new OrderedChoice(
         new Sequence("CastExpression(,type,,expression)", openParen, primitiveType, closeParen, unaryExpression),
         new Sequence("CastExpression(,type,,expression)", openParen, type, closeParen, new OrderedChoice(lambdaExpression, unaryExpressionNotPlusMinus)));

   IndexedChoice selector = new IndexedChoice("<selector>");
   {
      selector.put(".", new Sequence("VariableSelector(,identifier,arguments)", periodSpace, varIdentifier, optArguments));
      selector.put(".", new Sequence("NewSelector(,,innerCreator)", periodSpace, newKeyword, innerCreator));
      selector.put(".", new Sequence("TypedMethodSelector(,typeArguments,identifier,arguments)", periodSpace, simpleTypeArguments, identifier, arguments));
      selector.put("[", new Sequence("ArraySelector(,expression,)", openSqBracket, expression, closeSqBracket));
   }

   SemanticTokenChoice unaryPrefix = new SemanticTokenChoice("+", "-", "++", "--");

   Sequence prefixUnaryExpression = new Sequence("PrefixUnaryExpression(operator,expression)", unaryPrefix, unaryExpression);

   Sequence selectorExpression = new Sequence("SelectorExpression(selectors)", OPTIONAL, new Sequence("([])", REPEAT | OPTIONAL, selector));
   Sequence postfixUnaryExpression = new Sequence("PostfixUnaryExpression(operator)", OPTIONAL, new SemanticTokenChoice("++", "--"));

   // This is a hook for other languages (like JS) to add to the endStatement symbol
   /*
   public SymbolChoiceSpace endStatement = new SymbolChoiceSpace(SKIP_ON_ERROR, ";");
   {
      endStatement.generateParseNode = new NewlineParseNode(";");
   }
   */
   public SymbolSpace endStatement = semicolonEOL;
   ChainedResultSequence primaryExpression =
                                // If the optional second part matches, we get either a SelectorExpression or PostFixUnaryExpression.  Set that's chainedExpression
                                // property to the primary part of the expression.
      new ChainedResultSequence("<primaryExpression>(chainedExpression,.)", primary,
                                // When selectorExpression and postfixUnaryExpression match, set the expression property of the postFixUnary and return that
                                // When there is no postfixUnaryExpression return the selectorExpression.
                                new ChainedResultSequence("(expression,.)", OPTIONAL, selectorExpression, postfixUnaryExpression));

   {
      unaryPrefix.suppressSpacing = true;
      primaryExpression.suppressSpacing = true;
      unaryExpressionNotPlusMinus.put("~", new Sequence("UnaryExpression(operator,expression)", new SemanticTokenChoice("~"), unaryExpression));
      unaryExpressionNotPlusMinus.put("!", new Sequence("UnaryExpression(operator,expression)", new SemanticTokenChoice("!"), unaryExpression));
      unaryExpressionNotPlusMinus.put("(",  castExpression);
      unaryExpressionNotPlusMinus.addDefault(methodReference, primaryExpression);

      // TODO: do these symbols need to be broken out and ordered in separate sequences to get the right precedence rules?
      unaryExpression.put("+", prefixUnaryExpression);
      unaryExpression.put("-", prefixUnaryExpression);
      unaryExpression.addDefault(unaryExpressionNotPlusMinus);
   }

   Symbol methRefSepLookahead = new Symbol(LOOKAHEAD, "::");

   //OrderedChoice methodRefPrefix = new OrderedChoice(classValueExpression, new Sequence("TypeExpression(typeIdentifier,)", typeIdentifier, new Symbol(LOOKAHEAD, "::")), type, primaryExpression);
   // Here for Boolean.class::cast we need to match classValueExpression first
   // Using the methRefSepLookahead to avoid false partial parses... it's easy to match a type which is really part of a primary or vice versa.
   OrderedChoice methodRefPrefix = new OrderedChoice(new Sequence("TypeExpression(typeIdentifier,)", typeIdentifier, methRefSepLookahead),
                                                     new Sequence("(.,)", type, methRefSepLookahead),
                                                     new Sequence("(.,)", primaryExpression, methRefSepLookahead));
   {
      // Matching typeIdentifier - which can be either a type or an identifier expression first.  That type picks the right one at runtime.  After that we match type,
      // then primary since a type name can match more than a primary expression in some cases.
      methodReference.set(methodRefPrefix, new SymbolSpace("::"), optTypeArguments, new OrderedChoice(identifier, newKeyword));
      // Since the first slot is an identifier, type, or expression make sure we at least fill in the :: before we consider this a method reference.
      methodReference.minContentSlot = 1;
   }

   public SemanticTokenChoice binaryOperators = new SemanticTokenChoice(TypeUtil.binaryOperators);
   {
      binaryOperators.addExcludedValues("+=", "-=", "*=", "/=", "%=", "^=", "|=", "&=");
   }

   public SymbolChoiceSpace assignmentOperator = new SemanticTokenChoice("=", "+=", "-=", "*=", "/=", "%=", "^=", "|=", "&=",
                                                           "<<=", ">>=", ">>>=");

   public SemanticTokenChoice variableInitializerOperators = new SemanticTokenChoice("=");

   Sequence binaryOperand = new Sequence("BinaryOperand(operator,rhs)", binaryOperators, unaryExpression);

   Sequence binaryExpression = new ChainedResultSequence("BinaryExpression(firstExpr, operands)", unaryExpression,
           new OrderedChoice("([],[])", OPTIONAL | REPEAT,
              binaryOperand, new Sequence("InstanceOfOperand(operator,rhs)", new KeywordSpace("instanceof"), type)));

   Sequence questionMarkExpression = new Sequence("QuestionMarkExpression(,trueChoice,,falseChoice)", OPTIONAL, questionMark, expression, colon, expression);

   Sequence conditionalExpression = new ChainedResultSequence("<conditionalExpression>(condition,.)");
   {
      conditionalExpression.set(binaryExpression, questionMarkExpression);
   }

   public Sequence annotation = new Sequence("Annotation(,typeName,elementValue)"); // Forward
   {
      // The Annotation is composed entirely of Strings but in the IntelliJ's plugin we want to treat it as a 'composite' element, not collapse it into a String which is the default for
      // parselet types which are composed of all strings.
      annotation.complexStringType = true;
   }
   Sequence elementValueArrayInitializer = new Sequence("(,[],[],,)");

   OrderedChoice elementValue =
           new OrderedChoice("<elementValue>", expression, annotation, elementValueArrayInitializer);

   Sequence inferredParameters = new Sequence("(,[],)", openParen, new Sequence("([],[])", OPTIONAL, identifier, new Sequence("(,[])", OPTIONAL | REPEAT, comma, identifier)), closeParenSkipOnError);

   // Forward reference
   Sequence formalParameters = new Sequence("<parameters>(,.,)");

   public OrderedChoice lambdaParameters = new OrderedChoice(identifier, formalParameters, inferredParameters);
   public OrderedChoice lambdaBody = new OrderedChoice(expression, block);

   {
      lambdaParameters.ignoreEmptyList = false;
      lambdaExpression.set(lambdaParameters, new SymbolSpace("->"), lambdaBody);
      // Don't parse () as a partial lambda expression - we need it to match at least the -> in slot 1 before we consider that a partial value
      lambdaExpression.minContentSlot = 1;
   }

   public Sequence assignment = new Sequence("AssignmentExpression(operator, rhs)", OPTIONAL, assignmentOperator, expression);

   {
      assignmentExpression.set(conditionalExpression, assignment);
   }

   {
      elementValueArrayInitializer.set(
            openBrace, new Sequence("(.)", OPTIONAL, elementValue),
            new Sequence("(,[])", OPTIONAL | REPEAT, comma, elementValue),
            new Sequence(OPTIONAL, comma), closeBrace);
   }

   Sequence annotationElementValuePair = new Sequence("AnnotationValue(identifier,,elementValue)", identifier, equalSign, elementValue);

   Sequence annotationElementValuePairs = new Sequence("([],[])", annotationElementValuePair,
                new Sequence("(,[])", OPTIONAL | REPEAT, comma, annotationElementValuePair));

   // Simplify? Seems like we really just need Sequence("{", Sequence(OPTIONAL | REPEAT, annotationElementValue, comma), "}"
   //public OrderedChoice annotationValue = new OrderedChoice("<annotationValue>", OPTIONAL,
   //   new Sequence("(,.,)", openParen, new Sequence("(.)", OPTIONAL, annotationElementValuePairs), closeParenEOL),
   //   new Sequence("(,.,)", openParen, elementValue, closeParenEOL));

   public OrderedChoice annotationValue = new OrderedChoice("<annotationValue>", OPTIONAL,
                  new Sequence("(,.,)", openParen, new OrderedChoice("(.,.)", OPTIONAL, annotationElementValuePairs, elementValue), closeParenEOL));

   {
      annotation.set(new SymbolSpace("@"), typeName, annotationValue);
   }

   Sequence annotations = new Sequence("<annotations>([])", REPEAT | OPTIONAL, annotation);

   Sequence voidType = new Sequence("PrimitiveType(typeName,)", new SemanticToken("void"), spacing);

   OrderedChoice typeOrVoid = new OrderedChoice(type, voidType);

   // Java8 - 'default' added for default methods
   KeywordChoice modifierKeywords = new KeywordChoice(true, "public", "protected", "private", "abstract",
                   "static", "final", "strictfp", "native", "synchronized", "transient", "volatile", "default");
   public OrderedChoice modifiers = new OrderedChoice("<modifiers>([],[])", REPEAT | OPTIONAL, modifierKeywords, annotation);

   public Sequence variableDeclaratorId = new Sequence("(variableName,arrayDimensions)", identifier, openCloseSqBrackets);

   Sequence throwsNames = new Sequence("(,.)", OPTIONAL, new KeywordSpace("throws"), qualifiedTypeList);
   OrderedChoice variableModifiers = new OrderedChoice("<variableModifiers>([],[])", REPEAT | OPTIONAL, finalKeyword, annotation);

   public Sequence formalParameterDecls = new Sequence("Parameter(variableModifiers, type, *):(.,.,.)", OPTIONAL);
   public OrderedChoice formalParameterDeclRest =
        new OrderedChoice(new Sequence("(*,nextParameter)", variableDeclaratorId,
                                       new Sequence("(,.)", OPTIONAL, comma, formalParameterDecls)),
                          new Sequence("(repeatingParameter,*)", new SemanticToken("..."), variableDeclaratorId));
   {
      formalParameterDecls.add(variableModifiers, type, formalParameterDeclRest);
   }
   {
      formalParameters.set(openParen, formalParameterDecls, closeParenSkipOnError);
      formalParameters.ignoreEmptyList = false;
      // TODO: this handles the case of swallowing the '.' and '..' leading up to a repeating parameter definition
      // but probably should be generalized to handle other errors
      formalParameterDecls.skipOnErrorParselet = new SymbolChoice("..", ".");
      formalParameterDecls.skipOnErrorSlot = 2;
   }

   // Java8 Only
   Sequence catchParameterExtraTypes = new Sequence("(,[])", OPTIONAL | REPEAT, new SymbolSpace("|"), type);
   public Sequence catchParameter = new Sequence("CatchParameter(,variableModifiers, type, extraTypes, *,)", openParen,
            variableModifiers, type, catchParameterExtraTypes, variableDeclaratorId, closeParenSkipOnError);

   // Exposed as part of the language api using this name
   public Sequence parameters = formalParameters;

   // Forward declarations
   public OrderedChoice variableInitializer = new OrderedChoice("<variableInitializer>");
   public Sequence localVariableDeclaration = new Sequence("VariableStatement(variableModifiers, type, definitions):(.,.,.)");
   public Sequence localVariableDeclarationStatement = new Sequence("<localVariableDeclarationStatement>(.,)", localVariableDeclaration, endStatement);
   {
      arrayInitializer.set(
           openBrace,
           new Sequence("([],[],)", OPTIONAL, variableInitializer,
                   new Sequence("(,[])", REPEAT | OPTIONAL, comma, variableInitializer),
                   new Sequence(OPTIONAL, comma)),
           closeBrace);
      variableInitializer.set(arrayInitializer, expression);
   }

   public Sequence variableDefinition = new Sequence("(operator,initializer)", OPTIONAL, variableInitializerOperators, variableInitializer);
   {
      // If we match the initialization operators, we can't match anything else so this helps with partial parsing
      variableDefinition.skipOnErrorSlot = 1;
   }
   public Sequence variableDeclarator = new Sequence("VariableDefinition(*,*)", variableDeclaratorId, variableDefinition);
   public Sequence variableDeclarators =
       new Sequence("([],[])", variableDeclarator,
                    new Sequence("(,[])", OPTIONAL | REPEAT, comma, variableDeclarator));

   Sequence fieldDeclaration = new Sequence("FieldDefinition(variableDefinitions,)", variableDeclarators, semicolonEOL);

   // Warning: the property spec here is copied in the TemplateLanguage which adds its own statement class.
   public IndexedChoice statement =  new IndexedChoice("<statement>(.,.,.,.,.,.,.,.,.,.,.,.,.,,.,.)"); // Forward

   Sequence ifStatement = new Sequence("IfStatement(,expression,trueStatement,*):(.,.,.,.)", new KeywordSpace("if"), parenExpressionEOL, statement,
                                new Sequence("(,falseStatement)", OPTIONAL, new KeywordNewline("else"), statement));

   OrderedChoice forInit = new OrderedChoice(OPTIONAL, new Sequence("([])", localVariableDeclaration), expressionList);
   Sequence forVarControl = new Sequence("ForVarStatement(variableModifiers,type,identifier,,expression)",
                                         variableModifiers, type, identifier, colon, expression);
   {
      // Until we hit the colon, it could also be a forControl so don't match a partial until then.  It does not matter
      // much for a complete partial parse, but for reparse, it gets the model off track
      forVarControl.minContentSlot = 3;
   }
   Sequence forControlStatement = new Sequence("ForControlStatement(forInit,,condition,,repeat)", forInit, semicolon, optExpression, semicolon, optExpressionList);
   OrderedChoice forControl = new OrderedChoice(forVarControl,forControlStatement);
   Sequence forStatement =
       new Sequence("(,,.,,statement)", new KeywordSpace("for"), openParen, forControl, closeParenSkipOnError, statement);

   KeywordSpace defaultKeyword = new KeywordSpace("default");

   Sequence caseLabel = new Sequence("SwitchLabel(operator, expression,)", new KeywordSpace("case"), expression, colonEOL);
   Sequence defaultLabel = new Sequence("SwitchLabel(operator,)", defaultKeyword, colonEOL);
   OrderedChoice switchLabel = new OrderedChoice("<switchLabel>", caseLabel, defaultLabel);
   {
      caseLabel.skipOnErrorSlot = 1;
      defaultLabel.skipOnErrorSlot = 1;
   }

   Sequence switchLabels = new Sequence("<switchLabels>([])", REPEAT, switchLabel);
   Sequence switchBlockStatementGroups =
       new Sequence("<switchBlockStatementGroups>([],[])",  OPTIONAL | REPEAT, switchLabels, blockStatements);
   {
      switchLabels.skipOnErrorParselet = new Sequence("('')", OPTIONAL | REPEAT, new SymbolChoice(NOT, "case", "default", "break", "{", "}", EOF));
   }

   private KeywordSpace whileKeyword = new KeywordSpace("while");

   Sequence tryResources = new Sequence("(,[],[],,)", OPTIONAL, openParen, localVariableDeclaration, new Sequence("(,[])", OPTIONAL | REPEAT, semicolonEOL, localVariableDeclaration), optSemicolon, closeParenSkipOnError);

   public Sequence syncStatement = new Sequence("SynchronizedStatement(,expression,statement)", new KeywordSpace("synchronized"), parenExpression, block);

   Sequence doStatement =
      new Sequence("WhileStatement(operator,statement,,expression,)", new KeywordSpace("do"), statement,
      whileKeyword, parenExpression, semicolonEOL);

   Sequence whileStatement = new Sequence("WhileStatement(operator, expression, statement)", whileKeyword, parenExpression, statement);
   Sequence catchStatement = new Sequence("CatchStatement(,parameters,statements)",  new KeywordSpace("catch"), catchParameter, block);
   Sequence finallyStatement = new Sequence("FinallyStatement(,block)", OPTIONAL, new KeywordSpace("finally"), block);
   Sequence tryStatement = new Sequence("TryStatement(, resources, block,*)", new KeywordSpace("try"), tryResources, block,
                                        new Sequence("(catchStatements,finallyStatement)", OPTIONAL, new Sequence("([])", OPTIONAL | REPEAT, catchStatement), finallyStatement));
   Sequence switchStatement = new Sequence("SwitchStatement(,expression,,statements,)", new KeywordSpace("switch"), parenExpression, openBraceEOL, switchBlockStatementGroups, closeBraceEOL);
   Sequence returnStatement = new Sequence("ReturnStatement(operator,expression,)", new KeywordSpace("return"), optExpression, endStatement);
   Sequence throwStatement = new Sequence("ThrowStatement(operator,expression,)", new KeywordSpace("throw"), expression, endStatement);
   Sequence breakStatement = new Sequence("BreakContinueStatement(operator,labelName,)", new KeywordSpace("break"), optIdentifier, endStatement);
   Sequence continueStatement = new Sequence("BreakContinueStatement(operator,labelName,)", new KeywordSpace("continue"), optIdentifier, endStatement);
   Sequence assertStatement = new Sequence("AssertStatement(,expression,otherExpression,)", new KeywordSpace("assert"), expression, new Sequence("(,.)", OPTIONAL, colon, expression), endStatement);
   Sequence exprStatement = new Sequence("<exprStatement>(.,)", expression, endStatement);
   Sequence labelStatement = new Sequence("LabelStatement(labelName,,statement)", identifier, colonEOL, statement);

   {
      doStatement.suppressNewlines = true;

      // Add a set of indexed rules. We peek ahead and see if any tokens
      // match and redirect the parser to the appropriate statement if so.
      statement.put("{", block);
      statement.put("if", ifStatement);
      statement.put("for", forStatement);
      statement.put("while", whileStatement);
      statement.put("do", doStatement);
      // Note: this does not enforce the try must have one catch or finally rule.
      statement.put("try",tryStatement);
      statement.put("switch", switchStatement);
      statement.put("synchronized", syncStatement);
      statement.put("return", returnStatement);
      statement.put("throw", throwStatement);
      statement.put("break", breakStatement);
      statement.put("continue", continueStatement);
      statement.put("assert", assertStatement);
      statement.put(";", semicolonEOL);

      // Default rules in case the indexed ones do not match - labelStatement needs to be in front for the partial values mode, otherwise an identifier: will match as identifier skipping the missed ;
      statement.addDefault(labelStatement, exprStatement);

      localVariableDeclaration.set(variableModifiers, type, variableDeclarators);

      blockStatements.set(localVariableDeclarationStatement, classDeclaration, statement);
      block.set(openBraceEOL, blockStatements, closeBraceEOL);
      blockStatements.skipOnErrorParselet = skipBodyError;
   }

   /* methodBody = block */
   OrderedChoice methodBodyOrSemi = new OrderedChoice("<methodBody>(.,)", block, semicolonEOL);
   Sequence methodDeclaratorRest =
        new Sequence("MethodDefinition(parameters, arrayDimensions, throwsTypes, body)", 
                     formalParameters, openCloseSqBrackets, throwsNames, methodBodyOrSemi);

   Sequence constructorDeclaratorRest = new Sequence("ConstructorDefinition(parameters,throwsTypes,body)",
                                                     formalParameters, throwsNames, block);

   public Sequence methodDeclaration = new Sequence("(name,.)", identifier, methodDeclaratorRest);

   private KeywordSpace extendsKeyword = new KeywordSpace("extends");

   Sequence typeParameter = new Sequence("TypeParameter(name,extendsType)", identifier,
                                          new Sequence("(,.)", OPTIONAL, extendsKeyword, typeBound));
   Sequence typeParameters =
       new Sequence("<typeParameters>(,[],[],)", lessThan, typeParameter,
                    new Sequence("(,[])", OPTIONAL | REPEAT, comma, typeParameter), greaterThan);
   Sequence optTypeParameters = new Sequence("([])", OPTIONAL, typeParameters);


   // Forward
   Sequence interfaceDeclaration = new Sequence("(modifiers,.)");
   OrderedChoice interfaceDeclarationWithoutModifiers = new OrderedChoice();

   Sequence genericMethodOrConstructorDecl =
         new Sequence("(typeParameters,.)", typeParameters,
                      new OrderedChoice(new Sequence("(type, name, .)", typeOrVoid, identifier, methodDeclaratorRest),
                                        new Sequence("(name, .)", identifier, constructorDeclaratorRest))) {
            // This method eliminates the "failedProgressBytes" that show up when we try to match this and it's not
            // the right match for a method.  Not sure it improved performance though.
            public String acceptSemanticValue(Object sv) {
               if (!fastGenMethods)
                  return null;
               if (!(sv instanceof AbstractMethodDefinition))
                  return "Not a method";
               if (((AbstractMethodDefinition) sv).typeParameters == null)
                  return "Not a generic method";
               return null;
            }
         };
   Sequence typedMemberDeclaration =
         new Sequence("(type,.)", type, new OrderedChoice(methodDeclaration, fieldDeclaration));
   {
      // We don't want just 'type' to match in an error handling scenario - we should match at least something in the method or field declaration or else
      typedMemberDeclaration.minContentSlot = 1;
   }

   // The constructor - essentially a method without a return type.
   Sequence constructorDeclaration = new Sequence("(name, .)", identifier, constructorDeclaratorRest);
   {
      // Don't match just the identifier when parsing errors
      constructorDeclaration.minContentSlot = 1;
   }

   // Only parsed when we are in partial values mode - to improve the fidelity of the error model
   Sequence incompleteStatement = new Sequence("IncompleteStatement(type)", PARTIAL_VALUES_ONLY, qualifiedType);
   {
      //incompleteStatement.alwaysReparse = true;
   }

   public IndexedChoice memberDeclaration = new IndexedChoice("<memberDeclaration>");
   {
      memberDeclaration.put("class", classDeclarationWithoutModifiers); // Class is first because it's the most common
      memberDeclaration.put("interface",  interfaceDeclarationWithoutModifiers);
      memberDeclaration.put("@",  interfaceDeclarationWithoutModifiers);
      memberDeclaration.put("enum", classDeclarationWithoutModifiers);
      memberDeclaration.addDefault(genericMethodOrConstructorDecl, typedMemberDeclaration, constructorDeclaration);
   }

   public Sequence memberDeclarationWithModifiers = new Sequence("<memberDeclarationWithModifiers>(modifiers,.)", modifiers, memberDeclaration);

   /** exposed as a hook point for parsing class member definitions */
   public OrderedChoice classBodyDeclarations = new OrderedChoice("<classBodyDeclarations>([],[],[],)", OPTIONAL | REPEAT,
                memberDeclarationWithModifiers,
                new Sequence("<classBodyBlock>(staticEnabled,.)", new SemanticToken("static", OPTIONAL), block), incompleteStatement, semicolonEOL);

   {
      // One of the switch points from HTML spacing context to Java
      classBodyDeclarations.disableTagMode = true;

      // If can't parse an element, try skipping "alphaNumChar " or any other non } character, until we can parse and complete the
      // class definition by parsing statements.
      classBodyDeclarations.skipOnErrorParselet = skipBodyError;

      // Turn back on spacing for inner class definitions
      optClassBody.enableSpacing = classBody.enableSpacing = true;
      classBody.set(openBraceEOL, classBodyDeclarations, closeBraceEOL);
      optClassBody.set(openBraceEOL, classBodyDeclarations, closeBraceEOL);
   }

   /**
    * If you want to parse class body declarations as a snippet, use this sequence which allows white space
    * or comments up front.
    */
   public Sequence classBodySnippet = new Sequence("(,.)", spacing, classBodyDeclarations);
   {
      // This parselet is not referenced and initialized from the startParselet so we need to set this
      classBodySnippet.setLanguage(this);
   }

   KeywordChoice classModifierKeywords = new KeywordChoice(true, "public", "protected", "private", "abstract", "static", "final", "strictfp");
   public OrderedChoice classModifiers = new OrderedChoice("([],[])", OPTIONAL | REPEAT, annotation, classModifierKeywords);

   /**
    * The list of operators for defining a new type - this is a choice because SCLanguage needs to add to this list and it's easier to add to it then to
    * replace all references to a new one.
    */
   public KeywordChoice classOperators = new KeywordChoice("class");

   Sequence extendsType = new Sequence("<extends>(,.)", OPTIONAL, extendsKeyword, type);
   {
      // If we match the 'extends' but get an error matching the type, in error checking mode this will parse but marking that slot as an error
      extendsType.skipOnErrorSlot = 1;
   }
   Sequence extendsTypes = new Sequence("(,[])", OPTIONAL, extendsKeyword, typeList);
   Sequence implementsTypes = new Sequence("<implements>(,[])", OPTIONAL, new KeywordSpace("implements"), typeList);
   {
      extendsTypes.skipOnErrorSlot = 1;
      implementsTypes.skipOnErrorSlot = 1;
   }
   public Sequence normalClassDeclaration =
       new Sequence("ClassDeclaration(operator,,typeName,typeParameters,extendsType,implementsTypes,body)",
                    classOperators, spacing, identifierSp, optTypeParameters, extendsType, implementsTypes, classBody);
   {
      // When parsing for errors, once we've seen the 'class' skip on error until we see the { so we can resume a misformed class definition
      normalClassDeclaration.skipOnErrorSlot = 1;
      normalClassDeclaration.skipOnErrorParselet = new Sequence("('')", OPTIONAL | REPEAT, new SymbolChoice(NOT, "extends", "implements", "class", "static", "public", "private", "{", "}", "\n", EOF)); // TODO: add equals for html attribute
   }

   Sequence enumConstant = new Sequence("EnumConstant(modifiers,typeName,arguments,body)", annotations, identifier,
                                        optArguments, optClassBody);

   public Sequence enumBodyDeclaration = new Sequence("([],[],,[])", OPTIONAL, new Sequence("(.)", OPTIONAL, enumConstant), new Sequence("(,[])", OPTIONAL | REPEAT, comma, enumConstant),
                   new Sequence(OPTIONAL, comma),
                   new Sequence("([],[])", OPTIONAL, new Sequence("EmptyStatement()", semicolon), classBodyDeclarations));
   Sequence enumBody = new Sequence("(,[],)", openBraceEOL, enumBodyDeclaration, closeBraceEOL);
   Sequence enumDeclaration = new Sequence("EnumDeclaration(,typeName,implementsTypes,body)",
                                           new KeywordSpace("enum"), identifier, implementsTypes, enumBody);
   {
      classDeclarationWithoutModifiers.set(normalClassDeclaration, enumDeclaration);
      classDeclaration.set(classModifiers, classDeclarationWithoutModifiers);
   }

   KeywordSpace interfaceKeyword = new KeywordSpace("interface");

   Sequence normalInterfaceDeclaration = new Sequence("InterfaceDeclaration(,typeName,typeParameters,extendsTypes,body)",
         interfaceKeyword, identifier, optTypeParameters, extendsTypes, classBody);

   Sequence annotationDefault = new Sequence("(,.)", OPTIONAL, defaultKeyword, elementValue);
   {
      annotationDefault.ignoreEmptyList = false;
   }

   OrderedChoice annotationMethodOrConstantRest = new OrderedChoice(
         new Sequence("AnnotationMethodDefinition(name,,,defaultValue)", identifier, openParen, closeParenSkipOnError,
                      annotationDefault),
         new Sequence("AnnotationConstantDefinition(variableDefinitions)", variableDeclarators));

   OrderedChoice annotationTypeElementRest = new OrderedChoice(
         new Sequence("(type,.,)", type, annotationMethodOrConstantRest, semicolonEOL),
         new Sequence("(.,)",normalClassDeclaration, optSemicolon),
         new Sequence("(.,)",enumDeclaration, optSemicolon),
         new Sequence("(.,)",annotationTypeDeclaration, optSemicolon));

   Sequence annotationTypeBody = new Sequence("(,.,)", openBraceEOL,
         new Sequence("([])", OPTIONAL | REPEAT, new Sequence("(modifiers,.)", modifiers, annotationTypeElementRest)), closeBrace);

   {
      annotationTypeDeclaration.set(
         new SymbolSpace("@"), interfaceKeyword, identifier, annotationTypeBody);
   }


   {
      interfaceDeclarationWithoutModifiers.set(normalInterfaceDeclaration, annotationTypeDeclaration);
      interfaceDeclaration.set(classModifiers, interfaceDeclarationWithoutModifiers);
   }

   /**
    * The semicolon in a typeDeclaration is part of the language spec - a concession to C programmers who put semis at the end of class declarations.
    * Do not use semicolonEOL here because it has skip-on-error and we'd rather match a partial error in class or interface declaration
    */
   public OrderedChoice typeDeclaration = new OrderedChoice("(.,.,)", classDeclaration, interfaceDeclaration, semicolon);
   {
      typeDeclaration.setLanguage(this);
   }

   // We could wrap typeDeclaration but it's more efficient to avoid the extra sequence here and we need to set skipBodyError.  Keeping typeDeclaration so we can style a TypeDeclaration without
   // wrapping it in an array
   OrderedChoice typeDeclarations = new OrderedChoice("<typeDeclarations>([],[],)", OPTIONAL | REPEAT, classDeclaration, interfaceDeclaration, semicolon);
   {
      typeDeclarations.skipOnErrorParselet = skipTypeDeclError;
   }

   Sequence packageDeclaration = new Sequence("Package(annotations,,name,)", OPTIONAL);

   public Sequence languageModel = new Sequence("JavaModel(,packageDef, imports, types)", spacing, packageDeclaration, imports, typeDeclarations);
   {
      // Should we set this?  We really need the language model to parse as best as it can
      //languageModel.skipOnErrorSlot = 1;
   }

   // TODO: refactor this to use languageModel and pass it through as the result type of compilationUnit
   public Sequence compilationUnit = new Sequence("JavaModel(,packageDef,imports,types,)");

   {
      compilationUnit.add(spacing, packageDeclaration, imports, typeDeclarations, new Symbol(EOF));
      compilationUnit.skipOnErrorSlot = 1;
      packageDeclaration.add(annotations, new KeywordSpace("package"), qualifiedIdentifier, semicolonNewline);
   }

   Sequence modelList = new Sequence("([])", OPTIONAL | REPEAT, languageModel);
   public Sequence modelStream = new Sequence("ModelStream(,modelList,)", spacing, modelList, new Symbol(EOF));
   {
      modelStream.setLanguage(this);
   }

   @Constant
   public static JavaLanguage INSTANCE = new JavaLanguage();

   public static JavaLanguage getJavaLanguage() {
      return INSTANCE;
   }

   public JavaLanguage() {
     this(null);
   }

   public JavaLanguage(Layer layer) {
      super(layer);
      setSemanticValueClassPath("sc.lang.java");
      setStartParselet(compilationUnit);
      // The name used in the IntelliJ plugin files - for now not using as a precaution to try and avoid conflicts
      languageName = "SCJava";
      // We used to use java here for java files in layer directories but this makes the IDE registration difficult
      // since intelliJ does not let us register a processor for a file in special folder - they are global per suffix.
      defaultExtension = SCJ_SUFFIX;
   }

   public String getJavaFileName(String fileName) {
      return fileName;
   }


   /** Dummy method to call to ensure this class gets loaded and registered */
   public static void register() {}

   @sc.obj.HTMLSettings(returnsHTML=true)
   public Object styleCodeSnippet(String rootType, String input) {
      return styleSnippet(rootType, true, input, true);
   }

   @sc.obj.HTMLSettings(returnsHTML=true)
   public Object styleCodeSnippetNoErrors(String input) {
      return styleSnippet(null, false, input, true);
   }

   @sc.obj.HTMLSettings(returnsHTML=true)
   public Object styleSnippet(String rootType, String input) {
      return styleSnippet(rootType, true, input, false);
   }

   @sc.obj.HTMLSettings(returnsHTML=true)
   public Object styleSnippetNoTypeErrors(String input) {
      return styleSnippet(null, false, input, false);
   }

   @sc.obj.HTMLSettings(returnsHTML=true)
   public Object styleSnippetNoTypeErrors(String rootType, String input) {
      return styleSnippet(rootType, false, input, false);
   }

   private Object styleSnippet(String rootType, boolean errors, String input, boolean codeSnippet) {
      Object result = parseString(null, input, codeSnippet ? blockStatements : classBodySnippet, true);
      if (result instanceof ParseError) {
         System.err.println("*** Syntax error: " + result + " unable to style snippet: " + input);
         return input;
      }
      SemanticNodeList<Statement> statements = (SemanticNodeList<Statement>) ParseUtil.nodeToSemanticValue(result);
      if (rootType != null) {
         Object parResult = parseString(rootType + " { }");
         JavaModel model = (JavaModel) ParseUtil.nodeToSemanticValue(parResult);
         model.setLayeredSystem(LayeredSystem.getCurrent().getMainLayeredSystem());
         model.disableTypeErrors = !errors;
         model.temporary = true;
         TypeDeclaration parent = model.getModelTypeDeclaration();
         parent.setProperty("body", statements);
         ParseUtil.initAndStartComponent(model);
      }
      else {
         ParseUtil.initAndStartComponent(statements);
      }
      return ParseUtil.styleParseResult(null, null, null, errors, false, statements, true);
   }

   public String getOpenArray() {
      return "{";
   }

   public String getCloseArray() {
      return "}";
   }

   public boolean getSupportsLongTypeSuffix() {
      return true;
   }

   public List<String> getClassLevelKeywords() {
      return CLASS_LEVEL_KEYWORDS;
   }

   /**
    * This used to override the default in BaseLanguage to create a ClassType instead of an ErrorParseNode with the matched string.  This preserved the ability to
    * annotate it and complete it without having to sniff the ErrorParseNode out of the input and build a ClassType out of it.  But that node's parent was not getting
    * set and it didn't show up in the body so caused too many changes elsewhere.  Instead, we added the incompleteStatement parselet which is only parsed in 'partial values' mode
    * and always indicates an error when it's matched.
   public Parselet createSkipOnErrorParselet(String name, String... exitSymbols) {
      return new OrderedChoice(name + "(.,.)", qualifiedType, new Sequence(new SymbolChoice(NOT, exitSymbols), spacing));
   }
    */

}
