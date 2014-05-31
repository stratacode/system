/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.js;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@JSRetention(RetentionPolicy.SOURCE)
public @interface JSSettings {
   /** Specifies alternate javascript files to include in place of generating code from this class.  For correctness, this script would have to follow the conventions used by the framework for JS code generation */
   String jsLibFiles() default "";
   /** Specify a js file to store the generated JS files in.  If you use this setting, you must ensure there's a linear dependency order for all JS modules (including the default).  In other words, if JS types in this file extend those in another module file, types in that file can't extend types in this one.*/
   String jsModuleFile() default "";
   /** Specify a pattern to apply to this type and all sub-types for the jsModuleFile. */
   String jsModulePattern() default "";
   /** Override the frameworks type template used to generate code for this type and subtypes */
   String typeTemplate() default "";
   /** Override the frameworks merge template used to generate an update for this type and subtypes */
   String mergeTemplate() default "";
   /** Registers an alias for this types package to use as the prefix instead of the default convention of pkgA_pkgB_ */
   String prefixAlias() default "";
   /** Replace references to this type in the generated JS code with the JS type name specified (e.g. jv_Object to just eliminate this type from Javascript land) */
   String replaceWith() default "";
   /** Comma separated list of jsLibFiles which this lib file depends on being included before it */
   String dependentJSFiles() default "";
   /** When jsModuleFile/Pattern is used and js.options.disableModules is enabled if this is true, this module is still used.  Set this to true for core moduls which have dependencies to/from native js code.  Those that can be split out at compile time for smaller downloads. */
   boolean requiredModule() default false;
}
