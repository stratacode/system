/*
 * Copyright (c) 2018. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

/**
 * Set this on a field or property so that it's value is initialized from an expression evaluated in the context
 * of the current layer during the code-generation process.  So the generated code will be initialized with
 * the value once the provided expression is evaluated.  Use this for version numbers, build-time-stamps, or other
 * info you'd like to inject into the binaries during the build process.
 */
public @interface BuildInit {
   public String value() default "";
}
