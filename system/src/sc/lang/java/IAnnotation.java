/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.SemanticNodeList;

public interface IAnnotation {
   String getTypeName();

   public Object getAnnotationValue(String name);

   boolean isComplexAnnotation();

   public SemanticNodeList<AnnotationValue> getElementValueList();

   public Object getElementSingleValue();
}
