/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import java.io.Serializable;
import java.util.HashMap;

/**
* Created by jvroom on 10/6/14.
*/
public class LayerTypeIndex implements Serializable {
   String layerPathName; // Path to layer directory
   String[] topLevelSrcDirs; // Top-level srcDirs as registered after starting the layer
   HashMap<String,TypeIndex> layerTypeIndex = new HashMap<String,TypeIndex>();
   HashMap<String,TypeIndex> fileIndex = new HashMap<String,TypeIndex>();
   String[] langExtensions; // Any languages registered by this layer - need to know these are source
}
