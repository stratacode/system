/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/**
 * Used on classes which are the top-level type used by a StrataCode template.  This informs any code processing
 * that template what the suffix of the underlying file should be - e.g. css, html, etc.
 * If you ask for the sc.obj.ResultSuffix annotation and no annotation is set on the type, it will default to
 * the TemplateLanguage.resultSuffix property value for the file type.
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ResultSuffix {
   String value();
}
