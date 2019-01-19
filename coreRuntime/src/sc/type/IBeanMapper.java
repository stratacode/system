/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.type;

import sc.bind.IBinding;
import sc.js.JSSettings;

/**
 * Represents a mapping for a specific property on a specific type.  Provides all of the meta-data for that mapping
 * and access to the field, getMethod, setMethod, etc. as well as fast, position based get/set's so that we do not have
 * to do hash-table lookups for each getProperty and setProperty, and avoid runtime use of reflection apis which do not
 * have caching.
 * <p>You can globally turn off the use of bean mappers for data bindings and they are turned off in the JS runtime.</p>
 */
@JSSettings(replaceWith="jv_Object")
public interface IBeanMapper extends IBinding, Comparable<IBeanMapper> {
   /** Properties defined on an interface need to do a dynamic lookup to retrieve the position.  This is the sentinel value for those properties */
   public final static int DYNAMIC_LOOKUP_POSITION = -2;

   /** Sets a specific property on the specified object */
   void setPropertyValue(Object object, Object value);

   Object getPropertyValue(Object parent);

   String getPropertyName();

   Object getPropertyType();

   Object getField();

   boolean hasAccessorMethod();

   boolean hasSetterMethod();

   boolean isPropertyIs();

   Object getPropertyMember();

   int getPropertyPosition();

   int getPropertyPosition(Object obj);

   int getStaticPropertyPosition();

   Object getGenericType();

   String getGenericTypeName(Object resultType, boolean includeDims);

   Object getGetSelector();

   Object getSetSelector();

   /** Forces a property mapping to be treated as constant, i.e. attempts to change it will result in an error, no need to listen for changes */
   void setConstant(boolean val);

   Object getOwnerType();

   boolean isWritable();
}
