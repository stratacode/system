/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.type;

public interface IBeanIndexMapper extends IBeanMapper {
   public boolean hasIndexedAccessorMethod();

   public boolean hasIndexedSetterMethod();

   void setIndexPropertyValue(Object parent, int index, Object value);

   Object getIndexPropertyValue(Object parent, int index);

   Object getIndexedGetSelector();

   Object getIndexedSetSelector();
}
