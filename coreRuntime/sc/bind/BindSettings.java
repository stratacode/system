/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import static java.lang.annotation.ElementType.*;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({TYPE,FIELD,METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface BindSettings {
   String reverseMethod() default "";
   /**
    * Set to the 0-based index of the parameter we treat as the "reverse value" in a bi-directional method binding.
    * When calling the parameter index of the reverseMethod which is populated by the current value of the property just before we call the reverse method.
    * If modifyParam is true, after the reverse binding fires, we also set the value of any non-constant binding for this value.  This use case is
    * useful for say a static convert function where the method binding is getting the parameter value and then later setting it.
    */
   int reverseSlot() default -1;
   /** Set to the 0-based index of the parameter we treat as the forward value in a bi-directional method binding. */
   int forwardSlot() default -1;
   /** When true, when the reverse binding fires, propagates the value through to the parameter's property */
   boolean modifyParam() default false;
}
