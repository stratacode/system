/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

public interface LayerConstants
{
   public static final String LAYER_CLASS_NAME = "Layer";
   public static final String BUILD_DIRECTORY = "build";
   public static final String DYN_BUILD_DIRECTORY = "dynbuild";
   public static final String BUILD_INFO_FILE = "BuildInfo.sc";
   public static final String DYN_TYPE_INDEX_FILE = "dynTypeIndex.ser";
   public static final String DEFAULT_VM_PARAMETERS = "-Xmx1024m";

   public static final String DEFAULT_LAYERS_URL = "https://github.com/stratacode/layers.git";

   public static final String[] ALL_CONFIGURED_RUNTIMES = {"java", "js", "android", "gwt"};
   public static final String SC_DIR = ".stratacode";
   public static final String LAYER_PATH_FILE = "layerPath";
   public static final String SC_SOURCE_PATH = "scSourcePath";
   public static final String LAYER_COMPONENT_PACKAGE = "sys.layerCore";;
}
