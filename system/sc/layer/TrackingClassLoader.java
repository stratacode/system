/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashSet;

/** 
 * Java does not have a way to tell if a class has been loaded.  We need this feature to reliably determine whether or not we can
 * generate a new version of a class, i.e. adding features or making it dynamic.  If this is not used, we fall back to assuming that 
 * any file which is in a compiled layer that's in the current classpath has been loaded.  That will cause more restarts... 
 */
public class TrackingClassLoader extends URLClassLoader {
   Layer layer;
   TrackingClassLoader parentTrackingLoader;
   HashSet<String> loaded = new HashSet<String>();
   boolean disabled = false;
   TrackingClassLoader replaceLoader;
   boolean buildLoader = false;
   ClassLoader nonTrackingParent;
   int activeCount = 0;

   public TrackingClassLoader(Layer layer, URL[] urls, ClassLoader parent, boolean buildLoader) {
      super(urls, parent);
      this.layer = layer;
      this.buildLoader = buildLoader;
      if (parent instanceof TrackingClassLoader) {
         parentTrackingLoader = (TrackingClassLoader) parent;
         // temp build layers will not include all of the URLs from previous build layers but dynamic and temp layers do not
         // Also GWT passes in null here to change the system loader.  In that case, we are just appending to the previous loader
         // Also separate layers do not include the previous layers
         // Also when we start a new stack of layers that are not related to the previous ones, we do not disable the old one.
         // TODO: Essentially we want this last test to  disable the previous loader if we included the URLs from the previous one in this one.  Maybe we should just change it to work by comparing the URLs or breaking out the logic in which we decide if we should include the URLs?
         Layer parentLayer = parentTrackingLoader.layer;
         if (layer != null && !layer.tempLayer && !layer.buildSeparate && parentLayer != null && (layer.isBuildLayer() || layer.extendsLayer(parentLayer)))
            parentTrackingLoader.disableBuildLoaders(this);
      }
      else
         nonTrackingParent = parent;
   }

   /**
    * Do not load any new classes in any TrackingClassLoaders which are marked as build loaders.  These are loaders
    * which track the built directories (as opposed to lib directories which are added in layers statically)
    * <p>
    * As we add a new tracking class loader for a build layer, it contains all of the build directories in layered order
    * from the previous layers - i.e. so we pick up the last layer's files first.  But as we build the system, we have to
    * add each build layer to the class loader scheme after we've built it so that we can load compiled classes for any
    * types we've determined are "final" i.e. can't be changed by subsequent build layers.  Otherwise, we always have to
    * load source descriptions for all files.  This puts us into a bad predicament with Java because it's basic philosophy
    * is that once you put a directory into a class loader, there's no way to add one before it.  There are good reasons for that...
    * it's easy to introduce incompatible classes but StrataCode is careful about sorting the dependencies so this does not
    * happen. The difficult strategy here then is to disable all build loaders, keeping any classes which have already
    * been loaded in them, keeping the original loader in the chain, and reordering the build-dirs once the subsqeuent
    * build dir has been compiled.
    * </p>
    */
   private void disableBuildLoaders(TrackingClassLoader parent) {
      if (buildLoader && (layer == null || !layer.buildSeparate)) {
         disabled = true;
         replaceLoader = parent;
      }
      if (parentTrackingLoader != null) {
         Layer parentLayer = parentTrackingLoader.layer;
         // If this is a completely different stack of layers, do not disable it
         if (layer != null && parentLayer != null && layer.extendsLayer(parentLayer))
            parentTrackingLoader.disableBuildLoaders(parent);
      }
   }

   protected Class loadClass (String name, boolean resolve) throws ClassNotFoundException {
      activeCount++;
      Class c = null;
      try {
         if (disabled) {
            Class sc = findLoadedClass(name);
            if (sc != null)
               return super.loadClass(name, resolve);

            // This loader has been disabled but if the replacing loader is not in the call chain, we need to use it as it
            // contains the most up-to-date way to resolve classes for this loader.
            if (replaceLoader.activeCount == 0) {
               return replaceLoader.loadClass(name, resolve);
            }
            if (parentTrackingLoader != null)
               return parentTrackingLoader.loadClass(name, resolve);
            else
               return nonTrackingParent.loadClass(name);
         }
         try {
           c = super.loadClass(name, resolve);
         }
         // If we define a class which overrides a system class we'll get this error.  We are unfortunately out of luck at this point
         // and instead need to limit what can be done or use the externalClassPath for loading JAR files using the CFClass mechanism.
         catch (SecurityException exc) {
            System.err.println("*** Attempt to override system class: " + name + ": " + exc);
            c = null;
         }
         if (c != null) {
            loaded.add(name);
         }
      }
      finally {
         activeCount--;
      }
      return c;
   }

   public boolean isLoaded(String className) {
      return loaded.contains(className) || (parentTrackingLoader != null && parentTrackingLoader.isLoaded(className));
   }

}
