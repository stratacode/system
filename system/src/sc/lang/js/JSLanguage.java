/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.js;

import sc.lang.SCLanguage;
import sc.layer.Layer;
import sc.parser.*;

import java.util.Set;

/**
 * Language grammar for Javascript.
 *
 * Extending the SCLanguage to make it easier to convert from SC to JS via the grammar.  Rather than defining a new grammar from scratch, we
 * replace and change the Java grammar so it can parse Javascript files.   Partly this is because we can then easily map parselets from one language
 * to the other by looking up in the class hierarchy, but we could have a more general way to do that (so for example that two sub-classes of Java, or two completely independent
 * languages map to each other based on mapping names or some import/export mechanism).  The most important thing is that during a conversion from one language to the
 * next, you can just change the language of a parseNode and re-generate it's code in the new language by applying the new grammar rules in reverse.  That involves finding the parselet
 * in one language that maps to the next.
 *
 * The other way of looking at this is that some language needs to be the lingua-franca and the base language - it doesn't really matter which one.  Everything else gets derived from that one.
 * It's not a bad model because it ensures consistency in the name-space.
 */
public class JSLanguage extends SCLanguage implements IParserConstants {
   public final static JSLanguage INSTANCE = new JSLanguage();

   // Switch back to using the Java keywords so we can use scope, object etc. as variable names
   // TODO: this should really be a separate set of keywords that takes into account JS specific reserved words
   public Set getKeywords() {
      return JAVA_KEYWORD_SET;
   }

   public Set getVarNameKeywords() {
      return JAVA_VARNAME_KEYWORD_SET;
   }

   public KeywordSpace optVarKeyword = new KeywordSpace("var", OPTIONAL);

   public Sequence identifierList = new Sequence("([],[])", OPTIONAL, identifier, remainingIdentifiers);

   {
      // For JS, you can end statements with a ; either a newline or a line feed
      //endStatement.add("\n", "\r");

      /*
      localVariableDeclaration.setName("JSVariableStatement(,definitions)");
      localVariableDeclaration.set(optVarKeyword, variableDeclarators);

      blockStatements.setName("([])");
      blockStatements.set(statement);

      statement.removeParselet(syncStatement);
      statement.put("var", localVariableDeclarationStatement);
      // Be careful with the name here - there's one entry that's ommitted - the for a semicolon without anything in front of it.
      statement.setName("<statement>(.,.,.,.,.,.,.,.,.,.,.,.,,.,.,.)");
      */
   }

   public Sequence parameterNameList = new Sequence("(,.,)", openParen, identifierList, closeParen);

   public KeywordSpace functionKeyword = new KeywordSpace("function");
   public Sequence functionDeclaration = new Sequence("JSFunctionDeclaration(,name,parameterNames,body)", functionKeyword, identifier, parameterNameList, block);
   public Sequence functionExpression = new Sequence("JSFunctionDeclaration(,name,parameterNames,body)", functionKeyword, optIdentifier, parameterNameList, block);

   public OrderedChoice sourceElement = new OrderedChoice("(.,.)", functionDeclaration, statement);

   Sequence sourceElements = new Sequence("([])", OPTIONAL | REPEAT, sourceElement);

   {
      languageModel = new Sequence("JSModel(,sourceElements)", spacing, sourceElements);
      setStartParselet(languageModel);

      primary.put("function", functionExpression);

      binaryOperators.add("===");
      binaryOperators.add("!==");

      // Javascript arrays use square brackets so to generate JS array expressions just replace the curly braces with sq brackets
      arrayInitializer.set(0, openSqBracket);
      arrayInitializer.set(2, closeSqBracket);

      // And JS allows you to just use arrays in normal expressions so we add the array to the set of primary expressions
      primary.put("[", arrayInitializer);

      // Javascript parameter declarations do not have types.  For methods right now we explicitly format parameters so they are
      // never generated but for the 'catch' statement which uses the same grammar all we have to do to strip off the types is
      // to re-define the grammar for this type so that it is just a comma separated list of names.
      formalParameterDecls.setName("<formalParameterDecls>Parameter(*):(.)");
      formalParameterDecls.set(formalParameterDeclRest);

      // Also need to redefine the catchParameter since it's has Type1 | TYPE2 which makes it different than formalParameters
      catchParameter.setName("CatchParameter(,*,)");
      catchParameter.set(openParen, variableDeclaratorId, closeParenSkipOnError);

      // JS does not support the 'l' or "L" suffix
      integerLiteral.setName("IntegerLiteral(*)");
      integerLiteral.remove(1);

      // No floating point type suffixes in javascript
      //floatTypeSuffix.setExpectedValues(new String[] {" "});
   }

   public static JSLanguage getJSLanguage() {
      return INSTANCE;
   }

   public JSLanguage() {
      this(null);
   }

   public JSLanguage(Layer layer) {
      super(layer);
      // TODO: need to implement the rest of the JS grammar so we can parse a complete document.  Right now, we're just
      // re-defining any lower level grammar objects for conversion from Java to JS.  Ideally we could parse a JS and generate
      // a Java model from that as well, like to synchronize Java types from code we parse from a JS framework
      //setStartParselet(jsFile);
      addToSemanticValueClassPath("sc.lang.js");
   }

   public String getOpenArray() {
      return "[";
   }

   public String getCloseArray() {
      return "]";
   }

   public boolean getSupportsLongTypeSuffix() {
      return false;
   }
}
