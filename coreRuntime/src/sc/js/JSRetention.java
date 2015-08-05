/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.js;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Use this annotation to mark an annotation that uses a differerent retention policy than Java.  For example, if
 * you want to not include the annotation in Javascript but you do in Java you can se this annotation to RetentionPolicy.SOURCE */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface JSRetention {
    RetentionPolicy value();
}
