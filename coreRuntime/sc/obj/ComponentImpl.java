/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

/**
 * When the @Component annotation class is used, this class is "mixed in" in an intelligent way into your class as part of the
 * transformation from StrataCode into Java.
 * The constructor code is moved into the preInit method.  The init, start etc methods are implemented with dummy implementations
 * or you can define them in your class.   References to other objects can be recursive.  Any referenced components initialized at the same
 * time as the referencing component first all have their preInit methods called so you know all referenced preInit's have been called
 * before your init methods is called.  That means all references, even recursive ones are assigned when your init method is called.
 * Then all init methods are run before the start method of the first component, etc.
 */
public class ComponentImpl implements IComponent {
   protected byte _initState;

   public byte getInitState() {
      return _initState;
   }

   public void preInit() {
   }

   public void init() {

   }

   public void start() {

   }

   public void stop() {
   }
}
