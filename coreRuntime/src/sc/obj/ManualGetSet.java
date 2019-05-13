/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.CONSTRUCTOR;

/**
 * Attach to a method to disable automatic get/set conversion inside of that method
 */
@Target({METHOD,CONSTRUCTOR})
@Retention(RetentionPolicy.SOURCE)
public @interface ManualGetSet {
   boolean value() default true;
}
