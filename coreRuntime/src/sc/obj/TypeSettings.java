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
   /** Annotation that designates a class or getX method which is an object that's been through the code-generation phase. */
   boolean objectType() default false;
   /** Set to true for a class which should not be instantiated by itself, but does not have the abstract keyword because it's used in as a base-class for a dynamic type (e.g. Element) */
   boolean dynAbstract() default false;
   /** List of properties that are bindable but there's no code that reflects that binding in this class.  The common case is to mark the field, getX or setX method
    * as @Bindable but in some cases we do not have an artifact in the generated class to inject this annotation.  This annotation works for those cases.  It may be set
    * on the class generated, the getX method or (TODO: in the ExternalDynType generated for a class when this behavior is added in an anotationLayer and we do not generate
    * a class at all for that type). */
   String[] bindableProps() default {};
}
