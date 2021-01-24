/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.js;

import sc.js.JSSettings;
import sc.obj.CompilerSettings;
import sc.obj.ITemplateInit;
import sc.util.LineCountStringBuilder;

/**
 * This class is used as the base class for compiled JS type templates, i.e. the templates used to define the code-generation
 * for the Java to JS mapping for a type.  These templates can also be interpreted if it's not possible to
 * compile and load a class at runtime.
 */
@JSSettings(jsLibFiles="js/javasys.js")
@CompilerSettings(outputMethodTemplate="sc.js.JSTemplateOutputMethod")
public abstract class JSTypeTemplateBase extends JSTypeParameters implements ITemplateInit {

   public JSTypeTemplateBase() {
   }

   public LineCountStringBuilder out = null;

   public LineCountStringBuilder createOutput() {
      if (out == null)
         out = new LineCountStringBuilder();
      return out;
   }

   public abstract sc.util.LineCountStringBuilder output();

   /**
    * TODO: This is awkward because our Template class extends JSTypeParameters but the client code creates a
    * second instance of JSTypeParameters to use to initialize the template (at least when it's compiled)
    * For dynamic templates we just pass in the JSTypeParameters as the 'this' when evaluating the template
    * but maybe we should expose an API for creating a JS type template instance and have the client populate
    * the template instance?
    */
   public void initTemplate(Object obj) {
      if (obj instanceof JSTypeParameters) {
         JSTypeParameters initParams = (JSTypeParameters) obj;
         if (lineIndex == null && initParams.lineIndex != null)
            lineIndex = initParams.lineIndex;
         init(initParams.type);
      }
      else
         throw new UnsupportedOperationException();
   }

   public int getGenLineCount() {
      return out.lineCount;
   }
}
