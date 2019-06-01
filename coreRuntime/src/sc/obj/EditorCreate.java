package sc.obj;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import static java.lang.annotation.ElementType.*;

/**
 * Set on a type, constructor or method to mark it as available to the management UI for creating
 * instances of this type.
 */
@Target({TYPE,METHOD,CONSTRUCTOR})
@Retention(RetentionPolicy.RUNTIME)

public @interface EditorCreate {
   /**
    * Comma separated list of parameter names to use to invoking the constructor. It's valid to use
    * a property name here for a parameter which provides the initial value for a property. The editor
    * uses these names for the form fields used for these values before creating an instance of this type.
    * When setting on a constructor, the number of names should match the number of parameters. When set on a type,
    * there must be only one constructor with this number of parameters so the editor knows which one to call.
    */
   // TODO: should we generate this from the actual parameter names when set on a specific constructor during code-gen?
   // We need it in the runtime for the management UI and param names are not in the .class file
   String constructorParamNames() default "";
}
