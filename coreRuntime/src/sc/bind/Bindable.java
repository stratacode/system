/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import static java.lang.annotation.ElementType.*;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 
 * Use this annotation to mark properties as "bindable" - i.e. sending change events so they can be used as a source variable in a data binding expression using
 * StrataCode's reactive programming system.
 * You can @Bindable at the class level to make all properties bindable by default
 * or on a field, getX or setX method.  When you set manual=true, the code-generation treats this property as Bindable
 * without further code-generation.  Usually this means you add calls to Bind.sendEvent in your code or perhaps the property
 * never changes but you want to allow binding expressions on that property without a warning.   When you do not use manual=true, the generated code
 * includes the Bind.sendEvent call so listeners are notified.  This call is placed at the end of a generated setX method.  If there already exists a setX
 * method, it is renamed and called by the generated setX method although the details of this implementation should not be relied upon.
 */
@Target({TYPE,FIELD,METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Bindable {
   boolean manual() default false;
   boolean inactive() default false; // Set this to true to initially disable the binding.  It can be enabled via an api later - Bind.activate()
   boolean trace() default false;  // Set to enable selective tracing - each time the binding is initialized, set, retrieved, destroyed
   boolean verbose() default false; // Set to enable init, set, destroy events only
   boolean history() default false;  // Set to true to enable recording of the history of values.  APIs for diagnostics.  When combined
   boolean origin() default false;  // For trace or history include the origin - the stack trace and bindings leading up to this change.  If neither are set, both are enabled by default so origin=true by itself will provide the most diagnostics on this property.
   int delay() default -1;  // Set to 0 does the same thing as doLater=true.  Set to some number of milliseconds to run this binding with a delay.
   boolean queued() default false; // Set to true to force queued mode (default depends on BindingContext)
   boolean immediate() default false; // Set to true to force immediate mode (default depends on BindingContext)
   boolean doLater() default false; // Set to true to run this binding in a doLater
   // disabled? - to force an error
}
