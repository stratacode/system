/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;

/** After an object is converted to a class, this annotation is used on that class at runtime so we can identify the class or getX method as an object.  */
@Target({TYPE, METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TypeSettings {
   boolean objectType() default false;
}
