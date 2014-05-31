/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.js;

import sc.js.JSSettings;
import sc.obj.ITemplateInit;

/**
 * This class is used as the base class for compiled JS type templates, i.e. the templates used to define the code-generation
 * for the Java to JS mapping for a type.  These templates can also be interpreted if it's not possible to
 * compile and load a class at runtime.
 *
 * Created by jvroom on 3/23/14.
 */
@JSSettings(jsLibFiles="js/javasys.js")
public abstract class JSTypeTemplateBase extends JSTypeParameters implements ITemplateInit {

   public JSTypeTemplateBase() {
   }

   public abstract StringBuilder output();

   public void initTemplate(Object obj) {
      if (obj instanceof JSTypeParameters)
         init(((JSTypeParameters) obj).type);
      else
         throw new UnsupportedOperationException();
   }
}
