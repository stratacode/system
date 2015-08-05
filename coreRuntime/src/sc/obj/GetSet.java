/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import static java.lang.annotation.ElementType.*;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 
 * Set on fields to force the get set conversion.  Binding will only be injected if required but a get set method will
 * be generated.
 */
@Target({FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface GetSet {
   boolean value() default true;
}
