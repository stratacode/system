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
 * StrataCode's data binding libraries and the :=, =: and :=: operators.
 * By default, the generated code includes a Bind.sendEvent to notify listeners when this property is used in a binding
 * expression.  For fields, getX and setX methods are generated using customizable templates for defining a property.
 * If there already exists a setX method, a different template is used which by default renames the existing method calls
 * it from the generated setX method.  You can override either template to customize how events are sent.  For example,
 * the getX method could be modified to recompute the value for lazy evaluation.
 * You can set @Bindable at the class level to make all properties bindable by default
 * or on a field, getX or setX method.
 */
@Target({TYPE,FIELD,METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Bindable {
   /** Set to true for properties that do their own 'sendEvent' calls, or to just eliminate warnings about unbinding properties when another means is used to refresh the binding */
   boolean manual() default false;
   /** Set this to true to initially disable the binding.  It can be enabled via an api later - Bind.activate() */
   boolean inactive() default false;
   /** Set to enable selective tracing - each time the binding is initialized, set, retrieved, destroyed */
   boolean trace() default false;
   /** Set to enable verbose events on this binding - i.e. a message for init, set, destroy operations */
   boolean verbose() default false;
   /** Set to true for those bindings where the changeEvents might come from some other thread operating in another context.  Adds extra thread-local lookup for the init and apply calls plus any necessary context switching overhead */
   boolean crossScope() default false;
   /** Set to true to eliminate a null value being passed to a method as part of the binding */
   boolean skipNull() default false;
   /** Set to true to force queued mode (default depends on BindingContext) */
   boolean queued() default false;
   /** Set to true to force immediate mode (default depends on BindingContext) */
   boolean immediate() default false;
   /** Set to true to enable recording of the history of values.  APIs for diagnostics.  When combined (TODO - not implemented!) */
   boolean history() default false;
   /** For trace or history include the origin - the stack trace and bindings leading up to this change.  If neither are set, both are enabled by default so origin=true by itself will provide the most diagnostics on this property. TODO: not implemented */
   boolean origin() default false;
   /** Set to 0 does the same thing as doLater=true.  Set to some number of milliseconds to run this binding with a delay. */
   int delay() default -1;
   /** Set to true to run this binding in a doLater */
   boolean doLater() default false;
   /** For doLater=true, the priority to pass to the invokeLater call - 0 is the default that runs before refresh */
   int priority() default 0;
   // disabled? - to force an error
   /** Should this binding not send a change event when setX is called with the same value. */
   boolean sameValueCheck() default false;
   // TODO: add lazy=true that does a 'refreshBinding(obj,prop)' in the getX call before returning the value.
}
