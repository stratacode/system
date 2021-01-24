/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.js;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface JSMethodSettings {
   String replaceWith() default "";  /** Replace references to this method in the generated JS code with the method name specified */
   boolean omit() default false;     /** Do not include this method in the JS representation */
   String parameterTypes() default ""; /** Specify comma separated list of complete type names for parameter types to use for the JS conversion */
}
