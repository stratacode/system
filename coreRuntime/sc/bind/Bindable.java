/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import static java.lang.annotation.ElementType.*;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 
 * Use this annotation to mark properties as bindable.  You can use it at the class level to make all properties bindable by default
 * or set it on a field or getX or setX method.   When you use manual=true, the code-generation treats this property as Bindable 
 * without further code-generation.  When you do not use manual=true, the property is made bindable by injecting a sendEvent 
 * call into the setX method.
 */
@Target({TYPE,FIELD,METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Bindable {
   boolean manual() default false;
   // TODO: add these
   //boolean activated() default true; // Set this to false to initially disable the binding.  It can be enabled via Bind.activateBinding(obj, prop)
   //boolean trace() default false;  // Set to enable selective tracing - each time the value is set or retrieved
   //boolean history() default false;  // Set to true to enable recording of the history of values.  APIs for diagnostics
   //boolean origin() default false;  // For trace or history include the origin - the stack trace and bindings leading up to this change.  If neither are set, both are enabled by default so origin=true by itself will provide the most diagnostics on this property.
   //int delay default -1;  // Set to 0 for running this binding in a do-later.  Set to some number of milliseconds to run this binding later.
   //boolean queued;
}
