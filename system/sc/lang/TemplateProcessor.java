/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.layer.Layer;

/**
 * This class uses the TemplateLanguage to create a new file format.  You register an instance of this class
 * with an extension.  You can add an optional command to filter the output and you can set an output directory.
 * These files are processed at compile time, only as the dependencies indicate they need to be rebuilt.
 */
public class TemplateProcessor extends TemplateLanguage {
   public TemplateProcessor() {
      this(null);
   }

   public TemplateProcessor(Layer layer) {
      super(layer);
   }

}
