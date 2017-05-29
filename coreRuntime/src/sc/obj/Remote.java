/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import static java.lang.annotation.ElementType.*;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Provides manual control over the detection of remote methods.
 * <p>
 * Place this on a method so that it is treated as a remote method, not a local one, even if it's resolved in the same runtime.  You may want the call to be local from one runtime but remote from another.  Set the remoteRuntimes property to a comma separated list of runtimes that should treat this as a remote call.  Or you can set it be excluding the runtimes that should treat this as local by setting the localRuntimes.  Do not set both of these properties at the same time.
 * </p>
 */
@Target({METHOD}) // TODO: should we support this at the layer, type, or field level?
@Retention(RetentionPolicy.RUNTIME)
public @interface Remote {
   /** The comma separated list of runtimes in which this method should be treated as a remote call when used in a data binding expression. */
   String remoteRuntimes() default "";
   /** The comma separated list of runtimes in which this method should be treated as a local call */
   String localRuntimes() default "";
}
