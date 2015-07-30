/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This annotation is placed on a standard Java main method (i.e. a method with signature "public static void main(String[] args)").
 * It tells StrataCode when/how to execute that main method after compiling.  StrataCode will also generate a start script if
 * you specify the produceScript option or a package jar file if you set packageJar.   You can control Java options required
 * by the main method as well, or disable auto-execution.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface MainSettings {
   String execName() default "";
   /** When true, a jar is created that contains the runtime files needed to run the application.  The name of the jar is based on the base name of the execName file (so for the command bin/sc it would be sc.jar) */
   boolean produceJar() default false;   
   /** When true sc will produce a shell script to run the command using the execName. */
   boolean produceScript() default false;
   /** When produceScript is true use execCommandTemplate to specify the type name of a template file to use for generating the exec script.  If this is not set a default template is used.  The template is passed an instance of ExecCommandParameters to retrieve the command to run and type name */
   String execCommandTemplate() default "";
   /** Set this to a string to be put into the start script */
   String defaultArgs() default "";
   /** Set this to true to turn off automatic running of a main in a subsequent layer */
   boolean disabled() default false;  
   /** Adjust java settings for the generated run script */
   int maxMemory() default 0;        
   int minMemory() default 0;
   /** Use this to mark main methods that are tests */
   boolean test() default false;          
   String[] testCommands() default {};
   /** Include Java debug arguments for the Java start definition */
   boolean debug() default false; 
}
