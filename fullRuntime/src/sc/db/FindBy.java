package sc.db;

import java.lang.annotation.Repeatable;


/** 
  * A repeatable annotation, set on either a property or a class. When set on a class a name must be provided but
  * when set on a property, the default name is the propertyName.
  *
  * Each FindBy annotation generates a findByName(..) method that
  * executes a query and returns one or more DBObjects 
  */
@Repeatable(FindByRepeatAnnotation.class)
public @interface FindBy {
   /** 
     * A comma separated list of property names that are required arguments
     * to the query. When @FindBy is set on a property, by default with
     * is set to include the property itself.
     * 
     * For each property in the with list, an argument of that property's
     * type is added to the findBy method.
     *
     * If any of the 'with' properties have the DBPropertySettings 'unique'
     * flag set to true, the findBy method returns a single item, or null. 
     * Otherwise, it returns a java.util.List of matching items.
     */
   String with() default "";
   /** 
    * A list of property names that are optionally included into the query.
    * For each option property listed below, two arguments are added to the
    * findBy method - a boolean to include if this property should be
    * included in the query and another argument providing the value in
    * that case.
    */
   String options() default "";
   /** 
    * Specifies the suffix of the name of the findBy method - 
    * e.g. if the name is email the method is named findByEmail()
    */
   String name() default "";

   /** Specifies the name of the group of properties to fetch when running the query */
   String fetchGroup() default "";

   /**
    * Specifies a comma separated list of properties to use to sort the results of this query.
    * When set to "?" an option to provide the list of property names to use in the sort is added to the findByX parameters
    * after any options. When set to a comma separated list of property names, those properties are always used for the
    * sort. When a property name is prefixed with a '-' character, it's sort occurs in the descending direction. The default
    * is to use ascending order.
    */
   String orderBy() default "";

   /** When true, startIndex and maxResults options for the query are added */
   boolean paged() default true;
}
