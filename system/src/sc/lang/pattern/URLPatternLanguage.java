/*
 * Copyright (c) 2018. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.pattern;

import sc.lang.SCLanguage;
import sc.layer.Layer;
import sc.parser.*;
import sc.util.URLUtil;

/** A small language that's not parsed itself but supplies parselets used for parsing values from URLs using the pattern property of the @URL annotation */
public class URLPatternLanguage extends SCLanguage {
   public URLPatternLanguage() {
      this(null);
   }

   public URLPatternLanguage(Layer layer) {
      super (layer);
      addToSemanticValueClassPath("sc.lang.html");
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
   public Parselet urlPath = new OrderedChoice("('','','','')", REPEAT, alphaNumChar, digits, urlSpecialChar, new Symbol("/"));
   public Parselet integer = new OrderedChoice(digits);
   {
      // We want the input/output value to be the integer itself, not an IntegerLiteral like in other languages.  This is the first time we've needed to
      // specify an Integer as a semantic value type.
      //integer.setSemanticValueClass(Integer.class);  - this actually disables the type conversion we had built in :(
   }

   SymbolChoiceSpace openUserAgentComment = new SymbolChoiceSpace("(", "[");
   SymbolChoiceSpace closeUserAgentComment = new SymbolChoiceSpace(")", "]");

   //public SymbolChoice uaNameSpecialChar = new SymbolChoice('+', '.', ':');
   public SymbolChoice uaCommentSpecialChar = new SymbolChoice('+', '.', ':', '/', ';', ' ', ',', '-', '&');
   public Parselet userAgentName = new OrderedChoice("('','','','')", REPEAT, alphaNumChar, digits, new Symbol(" "), period /*, uaNameSpecialChar*/);
   public Parselet userAgentCommentBody = new OrderedChoice("('','','')", REPEAT, alphaNumChar, digits, uaCommentSpecialChar);
   public Parselet userAgentComment = new Sequence("(,.,)", OPTIONAL, openUserAgentComment, userAgentCommentBody, closeUserAgentComment);

   public Parselet versionString = new OrderedChoice("('','','')", REPEAT, digits, alphaNumChar, new SymbolChoice("-", "."));

   // In the browser user-agent string, the list of extensions name/version (...)
   public Parselet userAgentExts = new Sequence("([])", OPTIONAL | REPEAT,
           new Sequence("UserAgentExtension(name,*,,comment)", userAgentName, new Sequence("(,version)", OPTIONAL, new Symbol("/"), versionString), whiteSpace, userAgentComment));
}
