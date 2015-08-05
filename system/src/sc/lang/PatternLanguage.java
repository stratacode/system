/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.layer.Layer;
import sc.parser.*;

/**
 * A language which lets you define a simple pattern that will get matched to a string.  An alternative to
 * regular expressions for matching strings and extracting values from string formats, such as parameterized URLs.
 * Given a String in the form: "string{propName=parseletName}string{parseletName}"  where the 'string' can be any character except { and }
 * This language generates a Pattern object which you can use to generate a Sequence that can parse strings that match the
 * pattern.  From this Sequence, you can:
 *    1) Match a string to the pattern.
 *    2) Parse the string and set properties on some object based on a prop=token syntax in the pattern
 *    3) Generate a string by replacing the prop=token with those found in the properties of the object.
 * The 'tokens' are defined by parselets.  This moves the pattern logic into Java code, instead of being part of each pattern that helps to keep
 * the pattern readable and robust.
 * <p>
 * The parseletName in the pattern specifies a parselet in some base language you specify to convert the Pattern into a Sequence parselet.
 * For example, if you use the Java base language, you can use any parselet like integerLiteral, floatingPointLiteral, etc.
 * <p>
 * Regular expressions may be suitable for programmers and simple patterns.  The goal of the pattern language is to specify robustly parsed entities
 * to declarative programmers, separating form from function in a bi-directional way that a declarative programmer can maintain.
 * </p>
 */
public class PatternLanguage extends BaseLanguage {

   Parselet escapedString = new OrderedChoice("('','')", OPTIONAL | REPEAT, new SymbolChoice("\\{", "\\}"), new SymbolChoice(NOT, "\\", "{", "}", EOF));

   // A variable is a variableDef surrounded by braces
   Sequence variable = new Sequence("(,.,)", new Symbol("{"), new Sequence("VariableDef(name,*)", identifier, new Sequence("(,equalsName)", OPTIONAL, new Symbol("="), identifier)), new Symbol("}"));

   // A pattern element is an Object - either a String or a Variable.
   Parselet patternElements = new OrderedChoice("([], [])", OPTIONAL | REPEAT, escapedString, variable);

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
