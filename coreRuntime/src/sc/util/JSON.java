/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;

import sc.db.IDBObject;
import sc.dyn.DynUtil;
import sc.sync.JSONParser;
import sc.type.CTypeUtil;
import sc.type.IBeanMapper;
import sc.type.PTypeUtil;

import java.lang.reflect.Array;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.util.*;

/** Lightweight JSON formatter and parser built on the sc framework, supporting dynamic properties and types. */
public class JSON {

   public static Object parseJSON(Object propertyType, String jsonStr, JSONResolver resolver) {
      JSONParser parser = new JSONParser(jsonStr, resolver);
      return parser.parseJSONValue(propertyType);
   }

   public static Object toObject(Object propertyType, String jsonStr, JSONResolver resolver) {
      Object value = parseJSON(propertyType, jsonStr, resolver);
      if (propertyType == null)
         return value;
      return convertTo(propertyType, value, resolver);
   }

   public static Object convertTo(Object propertyType, Object value, JSONResolver resolver) {
      if (value instanceof List) {
         List listVal = (List) value;
         Object compType = DynUtil.getComponentType(propertyType);
         if (compType instanceof Class && !Map.class.isAssignableFrom((Class) compType)) {
            int listSz = listVal.size();
            ArrayList<Object> newListVal = new BArrayList<Object>(listSz);
            for (int i = 0; i < listSz; i++) {
               Object listElem = listVal.get(i);
               if (listElem instanceof Map || listElem instanceof CharSequence) {
                  Object newListElem = convertTo(compType, listElem, resolver);
                  listElem = newListElem;
               }
               newListVal.add(listElem);
            }
            listVal = newListVal;
         }
         if (propertyType instanceof ParameterizedType)
            propertyType = ((ParameterizedType) propertyType).getRawType();
         if (propertyType instanceof Class) {
            Class propCl = (Class) propertyType;
            if (propCl.isArray()) {
               Class compCl = propCl.getComponentType();
               int sz = listVal.size();
               Object arr = PTypeUtil.newArray(compCl, sz);
               for (int i = 0; i < sz; i++) {
                  Array.set(arr, i, listVal.get(i));
               }
               return arr;
            }
            else if (propCl == List.class)
               return listVal;
            else if (propCl == ArrayList.class || propCl == BArrayList.class)
               return new BArrayList(listVal);
         }
         // TODO: look for List or Collection constructor and use that with createInstance
         throw new UnsupportedOperationException("Unsupported array property type: " + propertyType);
      }
      else if (value instanceof Map) {
         Map<String,Object> map = (Map<String,Object>) value;
         Object instType = propertyType;

         String className = (String) map.get("class");
         if (className != null) {
            if (resolver != null) {
               instType = resolver.resolveClass(className);

               if (propertyType != null && propertyType != instType && !DynUtil.isAssignableFrom(propertyType, instType)) {
                  instType = null;
                  System.err.println("*** Resolved class name: " + className + " for JSON does not match property type: " + DynUtil.getTypeName(propertyType, false));
               }
            }
         }
         if (instType != null) {
            Object inst = DynUtil.newInnerInstance(instType, null, null);
            for (Map.Entry<String,Object> ent:map.entrySet()) {
               String propName = ent.getKey();
               Object propVal = ent.getValue();
               if (propName.equals("class"))
                  continue;
               IBeanMapper mapper = DynUtil.getPropertyMapping(instType, propName);
               if (mapper == null) {
                  System.err.println("*** No property: " + propName + " in propertyType: " + DynUtil.getTypeName(instType, false) + " for JSON deserialization");
                  continue;
               }
               Object newPropVal = convertTo(mapper.getGenericType(), propVal, resolver);
               DynUtil.setProperty(inst, propName, newPropVal);
            }
            return inst;
         }
         return value; // TODO: is this right - returning a map here - should we throw an exception instead?
      }
      else if (value instanceof CharSequence) {
         if (propertyType == Date.class) {
            Date res = DynUtil.parseDate(((CharSequence) value).toString());
            return res;
         }
         else if (DynUtil.isEnumType(propertyType)) {
            String enumConstName = ((CharSequence) value).toString();
            return DynUtil.getEnumConstant(propertyType, enumConstName);
         }
         else {
            String res = value.toString();
            int len = res.length();
            if (len > 4 && res.charAt(3) == ':' && res.charAt(0) == 'r' && res.charAt(1) == 'e' && res.charAt(2) == 'f') {
               String refName = res.substring(4);
               if (propertyType == null) {
                  System.err.println("*** Expected property type for JSON reference");
                  res = null;
               }
               else if (resolver == null) {
                  System.err.println("*** No resolver for JSON reference");
                  res = null;
               }
               else
                  return resolver.resolveRef(refName, propertyType);
            }
            else if (len > 5 && res.charAt(0) == '\\' && res.charAt(4) == ':' && res.charAt(1) == 'r' && res.charAt(2) == 'e' & res.charAt(3) == 'f') {
               res = res.substring(1);
            }
            return res;
         }
      }
      else if (value instanceof Number) {
         if (propertyType == Byte.class || propertyType == Byte.TYPE)
            value = ((Number) value).byteValue();
         else if (propertyType == Short.class || propertyType == Short.TYPE)
            value = ((Number) value).shortValue();
         return value;
      }
      else if (value instanceof Boolean)
         return value;
      else
         throw new UnsupportedOperationException("Unrecognized JSON value type");
   }

   private static class JSONContext {
      IdentityHashMap<Object,Boolean> addedObjs = new IdentityHashMap<Object,Boolean>();
   }
   public static StringBuilder toJSON(Object o, Object ptype, IValueReplacer replacer) {
      StringBuilder sb = new StringBuilder();
      JSONContext ctx = new JSONContext();
      appendValue(ctx, sb, o, ptype, replacer);
      return sb;
   }

   private static void appendObject(JSONContext ctx, StringBuilder sb, Object o, Object compType, IValueReplacer replacer) {
      if (o instanceof IDBObject) {
         sb.append("\"ref:db:");
         if (replacer != null) {
            Object newValue = replacer.replaceValue(o);
            if (newValue != o) {
               appendValue(ctx, sb, newValue, null, replacer);
               return;
            }
         }
         Object dbId = ((IDBObject) o).getDBId();
         // TODO: should avoid this by inserting these automatically like
         // in TxOperation.insertTransientRefs 
         if (dbId == null || (dbId instanceof Long && ((Long) dbId) == 0))
            System.err.println("*** Saving reference to transient object in JSON");
         appendValue(ctx, sb, dbId, null, replacer);
         sb.append('"');
      }
      else if (DynUtil.isRootedObject(o)) {
         sb.append("\"ref:");
         appendValue(ctx, sb, DynUtil.getObjectName(o), null, replacer);
         sb.append('"');
      }
      else {
         ctx.addedObjs.put(o, Boolean.TRUE);
         sb.append("{");
         Object instType = DynUtil.getType(o);
         boolean any = false;
         // For array elements only right now, we pass in compType to avoid the 'class' when the array is homogeneous
         if (compType != null && instType != compType) {
            appendString(sb, "class");
            sb.append(":");
            appendValue(ctx, sb, DynUtil.getTypeName(instType, false), null, replacer);
            any = true;
         }

         IBeanMapper[] props = DynUtil.getProperties(instType);
         for (int i = 0; i < props.length; i++) {
            IBeanMapper prop = props[i];

            if (prop == null || !prop.isReadable() || !prop.isWritable()) {
               continue;
            }
            Object member = prop.getPropertyMember();
            if (member == null || DynUtil.hasModifier(member, "static") || DynUtil.hasModifier(member, "protected") || DynUtil.hasModifier(member, "private"))
               continue;
            // Only the field can be transient but it's a signal to skip the property in the serialization
            Object field = prop.getField();
            if (field != null && DynUtil.hasModifier(field, "transient"))
               continue;

            Object val = prop.getPropertyValue(o, false, false);
            if (val != null && ctx.addedObjs.get(val) == null) {
               if (any)
                  sb.append(", ");
               appendString(sb, prop.getPropertyName());
               sb.append(":");
               appendValue(ctx, sb, val, prop.getGenericType(), replacer);
               any = true;
            }
         }
         sb.append("}");
         ctx.addedObjs.remove(o);
      }
   }

   static void appendValue(JSONContext ctx, StringBuilder sb, Object val, Object pType, IValueReplacer replacer) {
      if (val == null)
         sb.append("null");
      else if (val instanceof CharSequence) {
         if (replacer != null) {
            Object newVal = replacer.replaceValue(val);
            if (newVal != val) {
               appendValue(ctx, sb, newVal, pType, replacer);
               return;
            }
         }
         appendString(sb, ((CharSequence) val));
      }
      else if (val instanceof Character)
         appendString(sb, String.valueOf((Character) val));
      else if (val instanceof Number)
         sb.append(val.toString());
      else if (val instanceof Collection || val.getClass().isArray()) {
         sb.append("[");
         int sz = DynUtil.getArrayLength(val);
         Object arrCompType = pType == null ? null : DynUtil.getComponentType(pType);
         for (int i = 0; i < sz; i++) {
            if (i != 0)
               sb.append(", ");
            appendValue(ctx, sb, DynUtil.getArrayElement(val, i), arrCompType, replacer);
         }
         sb.append("]");
      }
      else if (val instanceof Boolean) {
         sb.append(val.toString());
      }
      else if (val instanceof Map) {
         Map<Object,Object> valMap = (Map<Object,Object>) val;
         boolean any = false;
         sb.append("{");
         for (Map.Entry<Object,Object> ent:valMap.entrySet()) {
            Object key = ent.getKey();
            if (!(key instanceof CharSequence))
               throw new IllegalArgumentException("JSON map property - non-char key unsupported");
            Object entVal = ent.getValue();
            if (entVal != null && ctx.addedObjs.get(entVal) == null) {
               if (any)
                  sb.append(", ");
               appendString(sb, key.toString());
               sb.append(":");
               appendValue(ctx, sb, entVal, null, replacer);
               any = true;
            }
         }
         sb.append("}");
      }
      else if (val instanceof Date) {
         if (replacer != null) {
            Object newVal = replacer.replaceValue(val);
            if (newVal != val) {
               appendValue(ctx, sb, newVal, pType, replacer);
               return;
            }
         }
         sb.append('"');
         sb.append(DynUtil.formatDate((Date) val));
         sb.append('"');
      }
      else if (val instanceof Enum) {
         sb.append('"');
         sb.append(((Enum) val).name());
         sb.append('"');
      }
      else {
         appendObject(ctx, sb, val, pType, replacer);
      }
      // TODO: enums here?
   }

   static void appendString(StringBuilder sb, CharSequence str) {
      sb.append('"');
      sb.append(CTypeUtil.escapeJavaString(str.toString(), '"', false));
      sb.append('"');
   }
}
