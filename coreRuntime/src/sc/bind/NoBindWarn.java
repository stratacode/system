/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.bind;

import java.lang.annotation.Retention;
import static java.lang.annotation.ElementType.*;

import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({TYPE,FIELD,METHOD})
@Retention(RetentionPolicy.RUNTIME)
/** For a data binding expression, to indicate it's ok to hide the warnings on fields we can't make bindable */
public @interface NoBindWarn {
}
