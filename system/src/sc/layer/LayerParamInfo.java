/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import java.util.ArrayList;
import java.util.List;

/**
* Created by jvroom on 12/29/14.
*/
class LayerParamInfo {
   List<String> explicitDynLayers;
   List<String> recursiveDynLayers;
   // All layers created in this operation
   ArrayList<Layer> createdLayers = new ArrayList<Layer>();
   boolean markExtendsDynamic;
   boolean activate = true;
   boolean enabled = true;
   boolean explicitLayers = false;  // When set to true, only process the explicitly specified layers, not the base layers.  This option is used when we have already determine just the layers we need.
}
