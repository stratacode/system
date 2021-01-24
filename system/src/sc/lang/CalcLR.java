/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.layer.Layer;
import sc.parser.IParserConstants;
import sc.parser.OrderedChoice;

import java.io.StringReader;

public class CalcLR extends Calc implements IParserConstants
{
   OrderedChoice LRExpression = new OrderedChoice("expression");

   {
      number.set(digits, spacing); 
      addition.set(LRExpression, plus, LRExpression);
      subtraction.set(LRExpression, minus, LRExpression);
      multiplication.set(LRExpression, times, LRExpression);
      division.set(LRExpression, slash, LRExpression);
      parenExpression.set(open, LRExpression, close);
      LRExpression.set(multiplication, division, addition, subtraction, number, parenExpression);
   }

   public CalcLR() {
      this(null);
   }

   public CalcLR(Layer layer) {
      super(layer);
      setStartParselet(LRExpression);
   }

   public static void main(String[] args) {
      CalcLR c = new CalcLR();
      //c.debug = true;
      //Object result = c.parse(new StringReader("5+4"));
      //System.out.println("5+4" + " => " + result);
      //c.debug = true;
      for (int i = 0; i < inputs.length; i++) {
         Object result = c.parse(new StringReader(inputs[i]));
         System.out.println(inputs[i] + " => " + result);
      }
   }
}
