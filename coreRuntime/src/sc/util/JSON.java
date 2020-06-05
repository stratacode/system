package sc.util;

import sc.db.IDBObject;
import sc.dyn.DynUtil;
import sc.sync.JSONParser;
import sc.type.CTypeUtil;
import sc.type.IBeanMapper;
import sc.type.PTypeUtil;

import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.util.*;

/** Lightweight JSON formatter and parser built on the sc framework, supporting dynamic properties and types. */
public class JSON {

   public static Object parseJSON(String jsonStr) {
      JSONParser parser = new JSONParser(jsonStr, null);
      return parser.parseJSONValue();
   }

   public static Object toObject(Object propertyType, String jsonStr, JSONResolver resolver) {
      Object value = parseJSON(jsonStr);
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
            ArrayList<Object> newListVal = new ArrayList<Object>(listSz);
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
               // Do this to ensure the array returned is String[] instead of just Object[]
               Object[] arr = (Object[]) PTypeUtil.newArray(propCl.getComponentType(), listVal.size());
               return listVal.toArray(arr);
            }
            else if (propCl == List.class)
               return listVal;
            else if (propCl == ArrayList.class)
               return new ArrayList(listVal);
         }
         // TODO: look for List or Collection constructor and use that with createInstance
         throw new UnsupportedOperationException("Unsupported array property type: " + propertyType);
      }
      else if (value instanceof Map) {
         Object inst = DynUtil.createInstance(propertyType, null);
         Map<String,Object> map = (Map<String,Object>) value;
         for (Map.Entry<String,Object> ent:map.entrySet()) {
            String propName = ent.getKey();
            Object propVal = ent.getValue();
            IBeanMapper mapper = DynUtil.getPropertyMapping(propertyType, propName);
            if (mapper == null) {
               System.err.println("*** No property: " + propName + " in propertyType: " + DynUtil.getTypeName(propertyType, false) + " for JSON deserialization");
               continue;
            }
            Object newPropVal = convertTo(mapper.getGenericType(), propVal, resolver);
            DynUtil.setProperty(inst, propName, newPropVal);
         }
         return inst;
      }
      else if (value instanceof CharSequence) {
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
      else if (value instanceof Number)
         return value;
      else if (value instanceof Boolean)
         return value;
      else
         throw new UnsupportedOperationException("Unrecognized JSON value type");
   }

   private static class JSONContext {
      IdentityHashMap<Object,Boolean> addedObjs = new IdentityHashMap<Object,Boolean>();
   }
   public static StringBuilder toJSON(Object o) {
      StringBuilder sb = new StringBuilder();
      JSONContext ctx = new JSONContext();
      appendValue(ctx, sb, o);
      return sb;
   }

   private static void appendObject(JSONContext ctx, StringBuilder sb, Object o) {
      if (o instanceof IDBObject) {
         sb.append("\"ref:db:");
         appendValue(ctx, sb, ((IDBObject) o).getDBId());
         sb.append('"');
      }
      else if (DynUtil.isRootedObject(o)) {
         sb.append("\"ref:");
         appendValue(ctx, sb, DynUtil.getObjectName(o));
         sb.append('"');
      }
      else {
         ctx.addedObjs.put(o, Boolean.TRUE);
         sb.append("{");
         IBeanMapper[] props = DynUtil.getProperties(DynUtil.getType(o));
         boolean any = false;
         for (int i = 0; i < props.length; i++) {
            IBeanMapper prop = props[i];

            if (prop == null || !prop.isReadable() || !prop.isWritable()) {
               continue;
            }
            Object member = prop.getPropertyMember();
            if (member == null || DynUtil.hasModifier(member, "transient") || DynUtil.hasModifier(member, "static"))
               continue;

            Object val = prop.getPropertyValue(o, false, false);
            if (val != null && ctx.addedObjs.get(val) == null) {
               if (any)
                  sb.append(", ");
               appendString(sb, prop.getPropertyName());
               sb.append(":");
               appendValue(ctx, sb, val);
               any = true;
            }
         }
         sb.append("}");
         ctx.addedObjs.remove(o);
      }
   }

   static void appendValue(JSONContext ctx, StringBuilder sb, Object val) {
      if (val == null)
         sb.append("null");
      else if (val instanceof CharSequence)
         appendString(sb, ((CharSequence) val));
      else if (val instanceof Character)
         appendString(sb, String.valueOf((Character) val));
      else if (val instanceof Number)
         sb.append(val.toString());
      else if (val instanceof Collection || val.getClass().isArray()) {
         sb.append("[");
         int sz = DynUtil.getArrayLength(val);
         for (int i = 0; i < sz; i++) {
            if (i != 0)
               sb.append(", ");
            appendValue(ctx, sb, DynUtil.getArrayElement(val, i));
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
               appendValue(ctx, sb, entVal);
               any = true;
            }
         }
         sb.append("}");
      }
      else {
         appendObject(ctx, sb, val);
      }
      // TODO: enums here?
   }

   static void appendString(StringBuilder sb, CharSequence str) {
      sb.append('"');
      sb.append(CTypeUtil.escapeJavaString(str.toString(), '"', false));
      sb.append('"');
   }
}
