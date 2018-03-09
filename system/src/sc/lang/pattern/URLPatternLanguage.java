/*
 * Copyright (c) 2018. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.pattern;

import sc.lang.SCLanguage;
import sc.layer.Layer;
import sc.parser.OrderedChoice;
import sc.parser.ParseUtil;
import sc.parser.Parselet;
import sc.parser.SymbolChoice;
import sc.util.URLUtil;

/** A language designed for parsing values from URLs using the pattern property of the @URL annotation */
public class URLPatternLanguage extends SCLanguage {
   public URLPatternLanguage() {
      this(null);
   }

   public URLPatternLanguage(Layer layer) {
      super (layer);
      setStartParselet(urlString);
      urlString.setLanguage(this);
      integer.setLanguage(this);
   }

   private boolean _inited = false;

   public void initialize() {
      if (_inited)
         return;
      _inited = true;

      super.initialize();
      ParseUtil.initAndStartComponent(urlString);
      ParseUtil.initAndStartComponent(integer);
   }

   public static URLPatternLanguage INSTANCE = new URLPatternLanguage();

   public static URLPatternLanguage getURLPatternLanguage() {
      INSTANCE.initialize();
      return INSTANCE;
   }

   public SymbolChoice urlSpecialChar = new SymbolChoice(URLUtil.URL_SPECIAL_CHARS);

   public Parselet urlString = new OrderedChoice("('','','')", REPEAT, alphaNumChar, digits, urlSpecialChar);
   public Parselet integer = new OrderedChoice(digits);
   {
      // We want the input/output value to be the integer itself, not an IntegerLiteral like in other languages.  This is the first time we've needed to
      // specify an Integer as a semantic value type.
      //integer.setSemanticValueClass(Integer.class);  - this actually disables the type conversion we had built in :(
   }
}
