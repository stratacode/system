/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Places on methods to control code generated using this method.
 */
@Target({ElementType.METHOD, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface HTMLSettings {
   /** Advice not to escape the return value of this method. */
   boolean returnsHTML() default false;
   // TODO: add allowedTags filter so that we can auto-validate and secure these properties from injection
   // add a default whitelist for just the formatting tags we already set
}
