/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.type;

import sc.dyn.DynUtil;
import sc.util.CoalescedHashMap;

import java.util.ArrayList;
import java.util.Map;

public class DynType {
   public DynType superType = null;
   public String typeName;
   public CoalescedHashMap<String,IBeanMapper> properties;
   public CompMethodMapper[] methods;
   public CoalescedHashMap<String,CompMethodMapper[]> methodIndex;

   public int propertyCount = PTypeUtil.MIN_PROPERTY;
   public int staticPropertyCount = PTypeUtil.MIN_PROPERTY;

   int dynLookupCount = 0; // Number of properties using dynamic lookup - stored at the end of the list so as not to conflict with properties and their instance position

   IBeanMapper[] propertyList = null;
   IBeanMapper[] staticPropertyList = null;

   IBeanMapper[] semanticProps = null;

   static Map<Class,DynType> dynamicTypes = PTypeUtil.getWeakHashMap();

   public static DynType addDynType(Class theClass, DynType type) {
      return dynamicTypes.put(theClass, type);
   }

   public static DynType getDynType(Class cl) {
      return dynamicTypes.get(cl);
   }

   public DynType(DynType superType, int propSize, int methSize) {
      this.superType = superType;

      if (superType != null) {
         propertyCount = superType.propertyCount;
         staticPropertyCount = superType.staticPropertyCount;
         dynLookupCount = superType.dynLookupCount;
      }

      if (propSize > 0)
         properties = new CoalescedHashMap<String, IBeanMapper>(propSize);
      if (methSize > 0)
         methods = new CompMethodMapper[methSize];
   }

   public DynType(String typeName, DynType superType, int propSize, int methSize) {
      this(superType, propSize, methSize);
      this.typeName = typeName;
   }

   public IBeanMapper addProperty(IBeanMapper mapper) {
      return addProperty(mapper.getPropertyName(), mapper);
   }

   public IBeanMapper addProperty(String name, IBeanMapper mapper) {
      if (properties == null)
         throw new IllegalArgumentException("Zero sized properties array with addProperty");
      int staticPos, pos;
      if ((staticPos = mapper.getStaticPropertyPosition()) != -1 && staticPropertyCount <= staticPos)
         staticPropertyCount = staticPos + 1;
      else if ((pos = mapper.getPropertyPosition()) != -1 && propertyCount <= pos)
         propertyCount = pos + 1;
      else if (pos == IBeanMapper.DYNAMIC_LOOKUP_POSITION) {
         if (properties.get(name) == null)
            dynLookupCount++;
      }
      return properties.put(name, mapper);
   }

   public IBeanMapper getPropertyMapper(String name) {
     IBeanMapper ret = properties == null ? null : properties.get(name);
      if (ret == null && superType != null)
         return superType.getPropertyMapper(name);
      return ret;
   }

   public int getPropertyIndex(String name) {
      IBeanMapper p = getPropertyMapper(name);
      if (p == null)
         return -1;
      return p.getPropertyPosition();
   }

   private void addPropertiesToList(IBeanMapper[] list) {
      // We store all properties here now right?
      //if (superType != null) {
      //   superType.addPropertiesToList(list);
      //}

      int dynIx = 0;
      for (int i = 0; i < properties.keyTable.length; i++) {
         if (properties.keyTable[i] != null) {
            IBeanMapper mapper = (IBeanMapper) properties.valueTable[i];
            int instPos = mapper.getPropertyPosition();
            if (instPos != -1) {
               if (instPos != IBeanMapper.DYNAMIC_LOOKUP_POSITION) {
                  list[instPos] = mapper;
               }
               else {
                  list[propertyCount + dynIx++] = mapper;
               }
            }
         }
      }
      if (dynIx != dynLookupCount)
         System.out.println("*** did not find all dyn lookup properties");
   }

   /** Returns just the instance properties for the type */
   public IBeanMapper[] getPropertyList() {
      if (propertyList != null)
         return propertyList;

      propertyList = new IBeanMapper[propertyCount+dynLookupCount];
      if (properties == null)
         return propertyList;

      addPropertiesToList(propertyList);

      // Diagnostics
      //for (int i = 1; i < propertyList.length; i++)
      //   if (propertyList[i] == null)
      //      System.out.println("**** missing property for slot problem!");

      return propertyList;
   }

   /** This includes non-transient properties for the parselets framework.  It's here because we need a fast/efficient place to store the list of semantic properties for iteration. */
   public IBeanMapper[] getSemanticPropertyList() {
      if (semanticProps != null)
         return semanticProps;

      ArrayList<IBeanMapper> l = new ArrayList<IBeanMapper>(propertyCount+dynLookupCount);

      int dynIx = 0;
      for (int i = 0; i < properties.keyTable.length; i++) {
         if (properties.keyTable[i] != null) {
            IBeanMapper mapper = (IBeanMapper) properties.valueTable[i];
            int instPos = mapper.getPropertyPosition();
            Object field = mapper.getField();
            // Only including instance fields without transient that are not parseNode and parentNode
            if (instPos != -1 && field != null && !DynUtil.hasModifier(field, "transient") && !DynUtil.hasModifier(field, "private") && !excludedProperty(mapper.getPropertyName())) {
               l.add(mapper);
            }
         }
      }
      semanticProps = l.toArray(new IBeanMapper[l.size()]);
      return semanticProps;
   }

   protected boolean excludedProperty(String name) {
      return name.charAt(0) == 'p' && (name.equals("parseNode") || name.equals("parentNode"));
   }

   /** Returns just the instance properties for the type */
   public IBeanMapper[] getStaticPropertyList() {
      if (staticPropertyList != null)
         return staticPropertyList;

      staticPropertyList = new IBeanMapper[staticPropertyCount];
      if (properties == null)
         return staticPropertyList;

      for (int i = 0; i < properties.keyTable.length; i++) {
         if (properties.keyTable[i] != null) {
            IBeanMapper mapper = (IBeanMapper) properties.valueTable[i];
            int pos = mapper.getStaticPropertyPosition();
            if (pos != -1) {
               if (staticPropertyList[pos] != null)
                  System.out.println("**** static property position conflict");
               else
                  staticPropertyList[pos] = mapper;
            }
         }
      }
      // TODO: maybe remove? for diagnostics only
      for (int i = 1; i < staticPropertyList.length; i++)
         if (staticPropertyList[i] == null)
            System.out.println("**** missing static property for slot problem!");

      return staticPropertyList;
   }

   public void addMethod(CompMethodMapper method, int pos) {
      methods[pos] = method;
   }

   public CompMethodMapper[] getMethods(String name) {
      if (methods == null)
         return null;
      if (methodIndex == null) {
         methodIndex = new CoalescedHashMap<String, CompMethodMapper[]>(methods.length);

         addMethods(methodIndex);
      }
      return methodIndex.get(name);
   }

   private void addMethods(CoalescedHashMap<String, CompMethodMapper[]> index) {
      if (superType != null)
         superType.addMethods(index);
      for (CompMethodMapper method:methods) {
         CompMethodMapper[] oldMethods = index.get(method.methodName);
         int mp = 0;

         if (oldMethods != null) {
            for (CompMethodMapper oldMeth:oldMethods) {
               // Same slot, override the method from the super type
               if (oldMeth.methodIndex == method.methodIndex) {
                  oldMethods[mp] = method;
                  break;
               }
               mp++;
            }
         }

         if (oldMethods == null || mp == oldMethods.length) {
            CompMethodMapper[] newMethods;
            int pos;
            if (oldMethods == null) {
               newMethods = new CompMethodMapper[1];
               pos = 0;
            }
            else {
               pos = oldMethods.length;
               newMethods = new CompMethodMapper[pos+1];
               System.arraycopy(oldMethods, 0, newMethods, 0, pos);
            }
            newMethods[pos] = method;

            index.put(method.methodName, newMethods);
         }
      }
   }

   public CompMethodMapper getMethod(String name, String paramSig) {
      CompMethodMapper[] meths = getMethods(name);
      for (CompMethodMapper meth:meths)
         if (meth.paramSig.equals(paramSig))
            return meth;
      return null;
   }

   public int getMethodIndex(String name, String paramSig) {
      CompMethodMapper meth = getMethod(name, paramSig);
      if (meth == null)
         throw new IllegalArgumentException("No method: " + name + paramSig);
      return meth.methodIndex;
   }

   public Object createInstance(String paramSig, Object...params) {
      CompMethodMapper meth = getMethod(typeName, paramSig);
      return meth.invoke(params);
   }

   /** This method is overridden by generated code to access static properties */
   public Object invokeStatic(int methodIndex, Object...params) {
      throw new IllegalArgumentException("No static method with index: " + methodIndex + " compiled into type: " + this);
   }

   public Object getStaticProperty(int propertyIndex) {
      throw new IllegalArgumentException("No static property with index: " + methodIndex + " compiled into type: " + this);
   }

   public void setStaticProperty(int propIndex, Object value) {
      throw new IllegalArgumentException("No static property with index: " + methodIndex + " compiled into type: " + this);
   }

   /** This method is overridden by generated code to access static properties */
   public Object invoke(Object thisObj, int methodIndex, Object...params) {
      throw new IllegalArgumentException("No method with index: " + methodIndex + " compiled into type: " + this);
   }

   public Object getProperty(Object thisObj, int propertyIndex) {
      throw new IllegalArgumentException("No property with index: " + methodIndex + " compiled into type: " + this);
   }

   public void setProperty(Object thisObj, int propIndex, Object value) {
      throw new IllegalArgumentException("No property with index: " + methodIndex + " compiled into type: " + this);
   }

   public String toString() {
      return "DynType(" + (typeName == null ? super.toString() : typeName) + ")";
   }

   public static DynType getDynTypeFromObj(Object object) {
      if (object == null)
         return null;
      return getDynType(object.getClass());
   }

   public boolean isAssignableFrom(DynType otherType) {
      return this == otherType || (superType != null && superType.isAssignableFrom(otherType));
   }
}
