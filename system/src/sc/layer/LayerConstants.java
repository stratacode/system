/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.type.CTypeUtil;
import sc.util.URLUtil;

public interface LayerConstants {
   String LAYER_CLASS_NAME = "Layer";
   String BUILD_DIRECTORY = "build";
   String DYN_BUILD_DIRECTORY = "dynbuild";
   String BUILD_INFO_FILE = "BuildInfo.sc";
   String BUILD_INFO_DATA_FILE = "BuildInfoData.ser";
   String DYN_TYPE_INDEX_FILE = "dynTypeIndex.ser";
   String DEFAULT_VM_PARAMETERS = "-Xmx1024m";

   String DEFAULT_LAYERS_URL = "https://github.com/stratacode/";
   String DEFAULT_LAYERS_PATH = "layers";

   String[] ALL_CONFIGURED_RUNTIMES = {"java", "js", "android", "gwt"};
   String SC_DIR = ".stratacode";
   String LAYER_PATH_FILE = "layerPath";
   String SC_SOURCE_PATH = "scSourcePath";
   String LAYER_COMPONENT_PACKAGE = "sys.layerCore";
   String LAYER_COMPONENT_TYPE_NAME = "Layer";
   String LAYER_COMPONENT_FULL_TYPE_NAME = CTypeUtil.prefixPath(LAYER_COMPONENT_PACKAGE, LAYER_COMPONENT_TYPE_NAME);
}
