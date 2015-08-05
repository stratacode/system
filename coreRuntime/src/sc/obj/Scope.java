/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import static java.lang.annotation.ElementType.*;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The scope operator exists in StrataCode but not in Java.  This annotation is the equivalent of the scope operator in Java, for behaviors which do not require code-generation (or operate on the runtime objects).  The scope must turn on the use of this annotation with includeScopeAnnotation. */
@Target({TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Scope {
   String name() default "";
}
