/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 
 * This annotation is set on classes or objects in StrataCode when it's possible for their children
 * or properties to have cyclic references.  For example, if you have two objects which have properties
 * which refer to each other.  When you set @Component on an instance, it will do a few things to
 * the Java code of that instance or any instance which extends that type.  First, it takes code out of the constructors for this type and places it into a preInit method.  
 * Any non-trivial constant property initializers are also moved into this method right before the constructor code.  
 * Before the preInit method is called, the object's reference variable is set so any references to this object can be resolved.  Components also have init and start methods.  
 * You are guaranteed that all child objects and dependent types are created and preInited when the init method is called.  
 * You are guaranteed that all dependent objects have been init'd before your start method is called. 
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Component {
   boolean disabled() default false;
}
