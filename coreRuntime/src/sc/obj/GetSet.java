/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import static java.lang.annotation.ElementType.*;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 
 * Set on fields to force the get set conversion.  Use @Bindable if you additionally want to ensure the property is bindable
 * (i.e. to make it bindable even if the property is not used in the set of layers when the source is generated)
 */
@Target({FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface GetSet {
   boolean value() default true;
}
