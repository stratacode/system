/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import static java.lang.annotation.ElementType.*;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 
 * Set on properties or getX methods to signal a constant property.  
 * Useful for eliminating binding warnings and for avoiding code-gen and listeners for values which do not change.
 * You may want to use the final operator in Java to do the same thing.  You use @Constant when you cannot change the
 * source but want to layer on the @Constant behavior.
 */
@Target({FIELD,METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Constant {
   boolean value() default true;
}
