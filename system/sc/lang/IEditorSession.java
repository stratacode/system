/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.lang.java.JavaModel;

public interface IEditorSession {
   public void setCurrentModel(JavaModel currentModel);

   void refreshModel(JavaModel model);
}
