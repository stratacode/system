/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.js;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * For Javascript handling of annotations where JS uses a different retention policy than Java.  For example, if
 * you want to exclude the annotation from the JS runtime (the generated JS code) set this to RetentionPolicy source.
 * It's a bit confusing because for Java this means keep the annotation in the java source - i.e. don't compile it into the class files.
 * The Source policy here for Javascript means leave it in the Java source.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface JSRetention {
    RetentionPolicy value();
}
