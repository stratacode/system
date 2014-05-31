/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import static java.lang.annotation.ElementType.*;


/**
 * You can use this annotation on a class to give it enum-like behavior.  In the class, if you
 * refer to an object using a modify tag, (i.e. NewObject { )  an object instance is defined of
 * the outer class.  This lets you get around the limitation enums have that they cannot extend anything.
 * It gives you a nice way to create an enumerated collection of subobjects using the parent type.
 * <pre><code>
 * e.g.  
 * &amp;Enumerated
 * class Foo {
 *    FooInstance1 {
 *    }
 *    FooInstance2 {
 *    }
 * }
 *
 * Is equivalent to:
 *
 * class Foo {
 *    object FooInstance1 extends Foo {
 *    }
 *    object FooInstance2 extends Foo {
 *    }
 * }
 * </code></pre>
 */
@Target({TYPE})
@Retention(RetentionPolicy.SOURCE)
public @interface Enumerated {
}
