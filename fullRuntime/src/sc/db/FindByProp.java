package sc.db;

public @interface FindByProp {
   String and() default "";
   String options() default "";
}
