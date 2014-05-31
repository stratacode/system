/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer.deps;

import sc.lang.SemanticNode;
import sc.parser.IString;

import java.util.List;

public class LayerDependencies extends SemanticNode {
   public String layerName;
   public List<IString> fileList;

   public transient int position;

   public int getChildNestingDepth() {
      if (parentNode != null)
         return parentNode.getChildNestingDepth() + 1;
      return 0;
   }
}
