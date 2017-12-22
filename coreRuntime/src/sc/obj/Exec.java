/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})

public @interface Exec {
   /**
    * Set of the command separated list of runtimes in which this class, method, or field should be accessed.  For example, you could set it to 'java' to restrict it to
    * only run in the java runtime, or js to only run in the JS runtime.  You can use the runtime 'default' to run in the main runtime only.  That's useful for something you
    * want to run on the server in a client/server configuration and in a client-only runtime like when you run a JS-only application
    */
   String runtimes() default "";
   /** Set to true for this method to always run on the client in a client/server configuration */
   boolean clientOnly() default false;
   /** Set to true for this method to always run on the server in a client/server configuration */
   boolean serverOnly() default false;
}
