/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.parser.*;

import java.io.StringReader;

public class Calc extends Language implements IParserConstants
{
   SymbolChoice lineTerminator = new SymbolChoice("\r\n", "\r", "\n");

   OrderedChoice whiteSpace = new OrderedChoice("whitespace", SKIP);
   { whiteSpace.add(new Symbol(" "), new Symbol("\t"), new Symbol("\f"), lineTerminator); }

   Sequence EOLComment = new Sequence("eolcomment",
         new Symbol(SKIP, "//"),
         new Sequence(OPTIONAL | REPEAT | SKIP,
                 // replace this with a NOT sequence pointing to lineTerminator
                 new SymbolChoice(NOT | SKIP, "\r\n", "\r", "\n"), new Symbol(SKIP, Symbol.ANYCHAR)),
                 new OrderedChoice(SKIP, lineTerminator, new Symbol(SKIP, Symbol.EOF)));

   OrderedChoice commentBody = new OrderedChoice("commentBody", SKIP | OPTIONAL | REPEAT);
   { 
      commentBody.add(new Sequence(new Symbol("*"), new Symbol(NOT, "/")),
                      new Sequence(new Symbol(NOT, "*"), new Symbol(Symbol.ANYCHAR))); 
   }

   Sequence blockComment = new Sequence("blockComment");
   { blockComment.add(new Symbol("/*"), commentBody, new Symbol("*/")); }

   OrderedChoice spacing = new OrderedChoice("spacing", REPEAT | OPTIONAL, whiteSpace, blockComment, EOLComment, lineTerminator); 
   Sequence plus = new Sequence("plus", new Symbol("+"), spacing);
   Sequence minus = new Sequence("minus", new Symbol("-"), spacing);
   Sequence times = new Sequence("times", new Symbol("*"), spacing);
   Sequence slash = new Sequence("slash", 
                new Symbol("/"), new Symbol("notstar", LOOKAHEAD | NOT, "*"), spacing);
   Sequence open = new Sequence("open", new Symbol("("), spacing);
   Sequence close = new Sequence("close", new Symbol(")"), spacing);
   
   SymbolChoice digits = new SymbolChoice(REPEAT);
   {
      for (int i = 0; i < 10; i++)
         digits.addExpectedValue(String.valueOf(i));
   }

   // First define the operators
   Sequence number = new Sequence("number");
   OrderedChoice expression2 = new OrderedChoice("expression2", SKIP);
   Sequence addition = new Sequence("addition");
   Sequence subtraction = new Sequence("subtraction");
   Sequence multiplication = new Sequence("multiplication");
   Sequence division = new Sequence("division");
   Sequence parenExpression = new Sequence("parenExpression", SKIP);
   OrderedChoice primary = new OrderedChoice("primary", SKIP);
   OrderedChoice expression = new OrderedChoice("expression");

   // Then set up the relationships
   { 
      number.add(digits, spacing); 
      addition.add(expression2, plus, expression); 
      subtraction.add(expression2, minus, expression);
      multiplication.add(primary, times, expression2);
      division.add(primary, slash, expression2);
      parenExpression.add(open, expression, close);
      primary.add(number, parenExpression);
      expression2.add(multiplication, division, primary);
      expression.add(addition, subtraction, expression2); 
   }

   public Calc()
   {
      setStartParselet(expression);
   }

   static String [] inputs = {
     "5 + 4 * 6",
     "5 * 4 + 6",
     "5+ 4 + 3 *5 / 125 - 72 /* foobar */",
     "(10 - 5) + 11 / 3 // EOL comment",
     "10 // EOL comment"
   };

   public static void main(String[] args)
   {
      Calc c = new Calc();
      c.debug = true;
      for (int i = 0; i < inputs.length; i++)
      {
         Object result = c.parse(new StringReader(inputs[i]));
         System.out.println(inputs[i] + " => " + result);
      }
   }
}
