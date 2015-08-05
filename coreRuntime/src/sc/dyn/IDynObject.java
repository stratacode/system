/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.dyn;

/** Interface implemented by dynamic typed objects. */
public interface IDynObject {
   public final static String GET_PROPERTY_NAME = "getProperty";
   public final static String GET_TYPED_PROPERTY_NAME = "getTypedProperty";

   public final static String SET_PROPERTY_NAME = "setProperty";

   Object getProperty(String propName);
   Object getProperty(int propIndex); // Faster way to get properties
   <T> T getTypedProperty(String propName, Class<T> propType); // Get properties without casting at the access site

   void setProperty(String propName, Object value, boolean setField);
   void setProperty(int propIndex, Object value, boolean setField);

   Object invoke(String methodName, String paramSig, Object... args);

   Object invoke(int methodIndex, Object... args);

   Object getDynType();

   void setDynType(Object type);
   
   void addProperty(Object propType, String propName, Object initValue);

   boolean hasDynObject();
}
