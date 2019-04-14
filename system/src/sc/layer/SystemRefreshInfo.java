/*
 * Copyright (c) 2018. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.lang.java.UpdateInstanceInfo;

import java.util.List;

public class SystemRefreshInfo {
   public UpdateInstanceInfo updateInfo;
   public List<Layer.ModelUpdate> changedModels;

   public String toString() {
      if (changedModels == null)
         return "<no changes>";
      else
         return "Changed files: " + changedModels.toString();
   }
}
