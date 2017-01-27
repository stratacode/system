/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

/**
 * Typically you do not implement this interface directly - instead you annotation a class with the @Component
 * annotation.  This is the interface components will implement at runtime.  When you use the @Component annotation
 * you can define initialize and start methods used in this interface.
 */
@sc.js.JSSettings(jsLibFiles="js/scdyn.js", prefixAlias="sc_")
public interface IComponent extends IStoppable {
   final static String COMPONENT_ANNOTATION = "sc.obj.Component";

   public byte getInitState();

   // Generated by the system: contains initialization for any variables which reference other objects, called
   // after the constructor.
   public void preInit();

   /** You can optionally implement this method to receive a initialization hook */
   public void init();

   /** You can optionally implement this method to receive a start hook */
   public void start();
}
