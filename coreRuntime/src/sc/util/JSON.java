package sc.util;

import sc.dyn.DynUtil;
import sc.sync.JSONParser;
import sc.type.CTypeUtil;
import sc.type.IBeanMapper;
import sc.type.PTypeUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Lightweight JSON formatter and parser built on the sc framework, supporting dynamic properties and types. */
public class JSON {

   public static Object parseJSON(String jsonStr) {
      JSONParser parser = new JSONParser(jsonStr, null);
      return parser.parseJSONValue();
   }

   public static Object toObject(Object propertyType, String jsonStr) {
      Object value = parseJSON(jsonStr);
      if (propertyType == null)
         return value;
      return convertTo(propertyType, value);
   }

   public static Object convertTo(Object propertyType, Object value) {
      if (value instanceof List) {
         List listVal = (List) value;
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
            DynUtil.setProperty(inst, propName, propVal);
         }
         return inst;
      }
      else if (value instanceof CharSequence) {
         return value.toString();
      }
      else if (value instanceof Number)
         return value;
      else if (value instanceof Boolean)
         return value;
      else
         throw new UnsupportedOperationException("Unrecognized JSON value type");
   }

   private static class JSONContext {
      List<Object> addedObjs = new ArrayList<Object>();
   }
   public static StringBuilder toJSON(Object o) {
      StringBuilder sb = new StringBuilder();
      JSONContext ctx = new JSONContext();
      appendValue(ctx, sb, o);
      return sb;
   }

   private static void appendObject(JSONContext ctx, StringBuilder sb, Object o) {
      ctx.addedObjs.add(o);
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
         if (val != null && !ctx.addedObjs.contains(val)) {
            if (any)
               sb.append(", ");
            appendString(sb, prop.getPropertyName());
            sb.append(":");
            appendValue(ctx, sb, val);
            any = true;
         }
      }
      sb.append("}");
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
            if (entVal != null && !ctx.addedObjs.contains(entVal)) {
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
