/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.js;

import sc.lang.SCLanguage;
import sc.parser.*;

import java.util.Set;

/**
 * Language grammar for Javascript.
 *
 * TODO: complete the grammar. Right now this is only used for converting from Java to JS for 'grammar only' conversions.
 * For the rest of the conversion, look at the transformToJS and formatJS methods in each language node
 *
 * Extending the SCLanguage to make it easier to convert from SC to JS via the grammar.
 * Probably could go back to extending Java if we have a more flexible way of mapping names - eg.g modifiers takes an extra param in SC
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

   {
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
