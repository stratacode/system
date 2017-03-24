/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.template;

import java.util.List;

/**
 * Implemented by classes which contain template declarations
 */
public interface ITemplateDeclWrapper {
   public List<Object> getTemplateDeclarations();

   public String getTemplateDeclStartString();
   public String getTemplateDeclEndString();
}
