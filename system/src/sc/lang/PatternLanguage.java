/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.layer.Layer;
import sc.parser.*;

/**
 * A language which lets you define a simple pattern that will get matched to a string, and optionally to populate properties of an
 * instance of a particular type.  First a Pattern instance is parsed from the string.  You then initialize it with
 * a class - which defines the type produced when parsing the instance.  This class must define the properties referenced in the pattern.  This produces
 * a generated Parselet which can parse that pattern and if needed, set properties in an instance.
 * Use Language.parseIntoInstance with that parselet to parse a given string and set properties on the instance.
 * <p>
 * This approach is an alternative to regular expressions for matching strings and extracting values from string formats, such as parameterized URLs.
 * Given a String in the form: "abc{propName=parseletName}def{parseletName}[optionalString/{propName=parseletName}]"
 * To include the {}[]= characters in the string, use the normal backslash escape character before them.
 * This language generates a Pattern object which you can use to generate a Sequence that can parse strings that match the
 * pattern.  From this Sequence, you can:
 *    1) Match a string to the pattern.
 *    2) Parse the string and set properties on some object based on a prop=token syntax in the pattern
 *    3) Go in the opposite direction and generate a string from an object instance (i.e. by replacing the prop=token with those found in the properties of the instance).
 * The types of tokens you can use are defined by parselets, named by parseletName.
 * <p>
 * This provides a high-level, but constrained way to translate user-supplied URLs to program expected information that has
 * passed a level of validation and transformation.
 * Unlike regular expressions, patterns work in both directions and so are 'reactive', translatable into data binding expressions
 * so the client can be responsive to changes in those properties and re-generate the string when they change (e.g. generate a parameterized
 * URL when a property changes for history management)
 * <p>
 * The parseletName in the pattern specifies a parselet in some base language you specify to convert the Pattern into a Sequence parselet.
 * For example, if you use the Java base language, you can use any parselet like integerLiteral, floatingPointLiteral, etc.
 * </p>
 */
public class PatternLanguage extends BaseLanguage {

   Parselet escapedString = new OrderedChoice("('','')", REPEAT,
                        new SymbolChoice("\\{", "\\}", "\\[", "\\]", "\\(", "\\)", "\\!", "\\*"),
                        new SymbolChoice(NOT, "\\", "{", "}", "[", "]", "(", ")", "!", "*", EOF));

   // A variable is a variableDef surrounded by braces
   Sequence variable = new Sequence("(,.,)", new Symbol("{"), new Sequence("PatternVariable(name,*)", identifier, new Sequence("(,equalsName)", OPTIONAL, new Symbol("="), identifier)), new Symbol("}"));

   SymbolChoice optionSymbols = new SymbolChoice( OPTIONAL | REPEAT, "!", "*");

   Sequence optionalPattern = new Sequence("OptionalPattern(optionSymbols,,elements,)");

   Sequence nestedPattern = new Sequence("Pattern(optionSymbols,,elements,)");

   // A pattern element is an Object - either a String or a Variable.
   Parselet patternElements = new OrderedChoice("([], [], [], [])", OPTIONAL | REPEAT, escapedString, variable, optionalPattern, nestedPattern);
   {
      optionalPattern.set(optionSymbols, new Symbol("["), patternElements, new Symbol("]"));
      nestedPattern.set(optionSymbols, new Symbol("("), patternElements, new Symbol(")"));
   }

   // A pattern is a list of elements
   public Parselet pattern = new Sequence("Pattern(elements,)", patternElements, new Symbol(EOF));

   public PatternLanguage() {
      this(null);
   }

   public PatternLanguage(Layer layer) {
      super (layer);
      setSemanticValueClassPath("sc.lang.pattern");
      setStartParselet(pattern);
   }

   public static PatternLanguage INSTANCE = new PatternLanguage();

   public static PatternLanguage getPatternLanguage() {
      return INSTANCE;
   }
}
