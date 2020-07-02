/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import static java.lang.annotation.ElementType.*;

/**
 * Set on types, get/set methods, or fields to store meta-data indicating general properties about how an editor should
 * operate on the properties, or objects in a model.
 */
@Target({TYPE,METHOD,FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface EditorSettings {
   /** Is this class/property visible in the editor */
   boolean visible() default true;
   /** A substitute for the object/property name */
   String displayName() default "";
   /** The name of a type to specialize the selection of the editor */  // TODO: should this be a list of classes or maybe we need a more flexible way to associate name/value pairs with a type used in selecting the editor?
   String editorType() default "";
   /** Include static properties and types in the editor view of the type. */
   boolean includeStatic() default false;
   /** Comma separated list of method names to use for the 'find' screen. If not set, includes the @FindBy methods */
   //String findMethods() default "";
   /** Name of the property to use for the display name for an instance of this type */
   String displayNameProperty() default "";
   /** Name of the property to use for the url of the thumbnail for this property */
   String thumbProperty() default "";
}
