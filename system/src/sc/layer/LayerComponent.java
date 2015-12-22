/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.obj.CompilerSettings;
import sc.obj.IComponent;
import sc.util.FileUtil;

/** A base class to use for components that are defined inside of layer definition files - e.g. LayerFileProcessor and Language. */
@CompilerSettings(dynObjManager="sc.layer.LayerDynChildManager", propagateConstructor="sc.layer.Layer")
public abstract class LayerComponent implements IComponent {
   /**
    * The layer which defines this component.  Layers which extend the layer which defines the component can use it.
    */
   public transient Layer definedInLayer;

   public LayerComponent() {
   }

   public LayerComponent(Layer layer) {
      definedInLayer = layer;
   }


   public byte _initState = 0;

   public byte getInitState() {
      return _initState;
   }

   public void preInit() {
      if (_initState > 0)
         return;
      _initState = 1;
   }
   public void init() {
      if (_initState > 1)
         return;
      _initState = 2;
   }
   public void start() {
      if (_initState > 2)
         return;
      _initState = 3;
   }
   public void stop() {
      if (_initState > 3)
         return;
      _initState = 4;
   }
}
