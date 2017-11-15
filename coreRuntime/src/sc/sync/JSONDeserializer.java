/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.sync;

import sc.dyn.DynUtil;
import sc.obj.Sync;
import sc.obj.SyncMode;
import sc.sync.JSONFormat.Commands;
import sc.type.CTypeUtil;
import sc.type.PTypeUtil;

import java.util.*;

import static sc.sync.JSONParser.eqs;

@sc.js.JSSettings(jsModuleFile="js/sync.js", prefixAlias="sc_")
@Sync(syncMode= SyncMode.Disabled)
public class JSONDeserializer {
   JSONParser parser;
   String destName;
   String scopeName;
   boolean isReset;
   boolean allowCodeEval;

   public String currentPackage;
   public ArrayList<Object> curObjs = new ArrayList<Object>();
   public ArrayList<String> curTypeNames = new ArrayList<String>();
   SyncManager.SyncContext syncCtx;

   public JSONDeserializer(String destName, String scopeName, String layerDef, boolean isReset, boolean allowCodeEval) {
      syncCtx = SyncManager.getSyncContext(destName, scopeName, true);
      parser = new JSONParser(layerDef, this);
      this.destName = destName;
      this.scopeName = scopeName;
      this.isReset = isReset;
      this.allowCodeEval = allowCodeEval;

   }

   public void apply() {
      if (parser.len == 0) // empty documents are ok
         return;
      // TODO: remove this and replace it with just a top-level object?
      if (parser.parseCharToken('{') && expectName("sync") && parser.parseCharToken('[')) {
         do {
            if (!parser.parseCharToken('{'))
               throw new IllegalArgumentException("Sync array value not an object in: " + parser);
            CharSequence cmdName = parser.parseName();
            if (cmdName == null || cmdName.length() == 0) {
               throw new IllegalArgumentException("Incomplete JSON file: " + parser);
            }
            if (cmdName.charAt(0) == '$') {
               Commands cmd = Commands.get(cmdName);
               if (cmd != null) {
                  cmd.apply(this, true); // Parses the reset of the command
               }
            }
            // The default is an object definition - simply "objectName": { "prop": val }
            else {
               setCurrentObjByName(cmdName.toString());
               parseSubs();
               popCurrentObj();
            }
            parseCmdClose();
            if (!parser.parseCharToken(',')) {
               if (!parser.parseCharToken(']'))
                  throw new IllegalArgumentException("Invalid json - missing ',' for: " + parser);
               else
                  break;
            }
         } while (true);
      }
      else {
         throw new IllegalArgumentException("JSON format error - expected { \"sync\": [ ... ] - found: " + parser);
      }
   }

   public CharSequence parseMethName() {
      CharSequence methName = parser.parseString(false);
      if (methName == null)
         throw new IllegalArgumentException(("JSON document missing meth name at : " + parser));
      return methName;
   }

   public CharSequence parseObjName() {
      CharSequence objName = parser.parseString(false);
      if (objName == null)
         throw new IllegalArgumentException(("JSON document missing object name at : " + parser));
      return objName;
   }

   public boolean parseCmdClose() {
      if (!parser.parseCharToken('}'))
         throw new IllegalArgumentException("Missing close brace for command: " + parser);
      return true;
   }

   public boolean expectName(String expectedName) {
      CharSequence nextName = parser.parseName();
      return eqs(nextName, expectedName);
   }

   public String acceptString(Object val, boolean allowNull) {
      if (val instanceof CharSequence) {
         return val.toString();
      }
      else if (allowNull && val == null)
         return null;
      else
         throw new IllegalArgumentException("Expecting string at: " + parser);
   }

   public Object[] acceptArray(Object val, boolean allowNull) {
      if (val instanceof List) {
         return ((List) val).toArray();
      }
      else if (allowNull && val == null)
         return null;
      else
         throw new IllegalArgumentException("Expecting string at: " + parser);
   }

   // parses: , "name":
   public CharSequence parseNextName() {
      if (!parser.parseCharToken(','))
         throw new IllegalArgumentException("Missing comma in JSON: " + parser);
      return parser.parseName();
   }

   public void setCurrentObjByName(String name) {
      Object newObj = resolveObject(name, true);
      pushCurrentObj(newObj, name);
      if (newObj == null) {
         System.err.println("*** No object for deserialize: " + name + " in: " + parser);
      }
   }

   Object resolveObject(String name, boolean prefixParent) {
      int sz = curObjs.size();
      if (sz > 0 && prefixParent) {
         String prefix = null;
         for (int i = 0; i < sz; i++) {
            prefix = CTypeUtil.prefixPath(curTypeNames.get(i), prefix);
         }
         name = CTypeUtil.prefixPath(prefix, name);
      }
      return syncCtx.resolveObject(currentPackage, name, false, true);
   }

   public void createNewObj(String objName, String typeName, Object[] args) {
      // TODO: we don't have the constructor signature here but it's ignored in JS anyway and not available when JS is generating the sync. This will be a bug with overloading and constructors used in synchronization
      Object outerObj = getCurObj();
      Object newObj = syncCtx.resolveOrCreateObject(currentPackage, outerObj, objName, typeName, true, null, args);
      if (newObj == null)
         System.err.println("*** No object for deserialize: " + objName + " in: " + parser);
   }

   public void pushCurrentObj(Object newObj, String newName) {
      curObjs.add(newObj);
      curTypeNames.add(newName);
   }

   public void popCurrentObj() {
      int rem = curObjs.size() - 1;
      curObjs.remove(rem);
      curTypeNames.remove(rem);
   }

   public Object getCurObj() {
      int sz = curObjs.size();
      return sz == 0 ? null : curObjs.get(sz-1);
   }

   public void parseSubs() {
      if (!parser.parseCharToken('{')) {
         throw new IllegalArgumentException("Expecting object definition at: " + parser);
      }
      boolean first = true;

      while (!parser.parseCharToken('}')) {
         CharSequence nextName = first ? parser.parseName() : parseNextName();
         if (nextName == null || nextName.length() == 0)
            throw new IllegalArgumentException("Expecting name at: " + parser);

         first = false;

         // A command, not a property
         if (nextName.charAt(0) == '$') {
            Commands cmd = Commands.get(nextName);
            if (cmd == null)
               throw new IllegalArgumentException("No command: " + nextName + " for: " + parser);
            cmd.apply(this, false);
         }
         else {
            boolean hasObjValue = parser.peekCharToken('{');
            Object propVal = null;
            boolean isProp = true;
            Object curObj = getCurObj();
            String nextNameStr = nextName.toString();
            if (!hasObjValue) {
               propVal = parser.parseJSONValue();
            }
            else {
               Object inst = resolveObject(nextNameStr, true);
               // If there's no object name, it might be a map property
               if (inst == null) {
                  // Collect the object value as a HashMap in case it's a Map property.  We'll get an error when we try to set it if it's not a map property
                  HashMap mapVal = new HashMap();
                  pushCurrentObj(mapVal, nextNameStr);
                  parseSubs();
                  popCurrentObj();
                  propVal = mapVal;
               }
               else {
                  pushCurrentObj(inst, nextNameStr);
                  parseSubs();
                  popCurrentObj();
                  isProp = false;
               }
            }
            if (isProp) {
               if (curObj != null) {
                  try {
                     SyncManager mgr = syncCtx.getSyncManager();
                     Object objType = null;
                     boolean skipSet = false;
                     if (!mgr.syncDestination.clientDestination) {
                        objType = DynUtil.getSType(curObj);
                        if (!mgr.isSynced(objType, nextNameStr)) {
                           System.err.println("Not allowed to set unsynchronized property from json: " + DynUtil.getTypeName(objType, true) + "." + nextNameStr);
                           skipSet = true;
                        }
                     }
                     if (!skipSet) {
                        // For array or collection properties, we need to have the property metadata in order to deserialize the
                        // value and set the object property correctly.
                        if (propVal instanceof List) {
                           if (objType == null)
                              objType = DynUtil.getSType(curObj);
                           Object propType = DynUtil.getPropertyType(objType, nextNameStr);
                           if (propType != null) {
                              propVal = convertRemoteType(propVal, propType);
                           }
                        }
                        if (curObj instanceof Map) // TODO - it's possible for a map to have regular properties too... we should perhaps be keying off of whether we created a Map before calling parseSubs
                           ((Map) curObj).put(nextNameStr, propVal);
                        else
                           DynUtil.setPropertyValue(curObj, nextNameStr, propVal);
                     }
                  }
                  catch (IllegalArgumentException exc) {
                     System.err.println("No property: " + curObj + "." + nextNameStr + ": " + exc);
                  }
               }
               else {
                  System.err.println("No current object for set property: " + nextNameStr);
               }
            }
         }
      }
   }

   public Object convertRemoteType(Object value, Object type) {
      if (type == Object.class)
         return value;
      if (value instanceof List) {
         List propValList = (List) value;
         // Convert if necessary to get the correct array type - e.g. a String[] instead of just an Object[]
         if (DynUtil.isArray(type)) {
            value = propValList.toArray((Object[]) PTypeUtil.newArray((Class) DynUtil.getComponentType(type), propValList.size()));
         }
         // Call the constructor to create the right type of collection, i.e. Collection((Collection a))
         else {
            if (type == List.class) // TODO: need to handle more of these - use the SyncHandler interface here?
               type = ArrayList.class;
            // TODO - security: validate this type is allowed to be deserialized
            value = DynUtil.createInstance(type, null, value);
         }
      }
      return value;
   }

   public void invokeMethod(CharSequence methName, CharSequence typeSig, List args, CharSequence callIdSeq) {
      Object curObj = getCurObj();
      boolean isType = DynUtil.isSType(curObj);
      Object curType = isType ? curObj : DynUtil.getSType(curObj);
      String callId = callIdSeq.toString();
      Object meth = DynUtil.resolveMethod(curType, methName.toString(), typeSig == null ? null : typeSig.toString());
      if (meth == null) {
         System.err.println("No method: " + methName + " in type: " + DynUtil.getTypeName(curType, false) + " with param signature: " + typeSig);
      }
      else {
         SyncManager mgr = syncCtx.getSyncManager();
         if (!mgr.allowInvoke(meth)) {
            System.err.println("Remote call to method: " + methName + " not allowed - missing sc.obj.Remote annotation or remoteRuntimes is missing: " + mgr.syncDestination.remoteRuntimeName);
            return;
         }
         // TODO: security: need to validate that this method is authorized for remote access
         boolean flushQueue = SyncManager.beginSyncQueue();
         try {
            if (args.size() > 0) {
               Object[] paramTypes = DynUtil.getParameterTypes(meth);
               if (paramTypes != null) {
                  for (int i = 0; i < args.size(); i++) {
                     Object arg = args.get(i);
                     Object paramType = paramTypes.length > i ? paramTypes[i] : paramTypes[paramTypes.length-1];
                     // TODO: handle varargs here!
                     Object newArg = convertRemoteType(arg, paramType);
                     if (newArg != arg)
                        args.set(i, newArg);
                  }
               }
            }
            // See FieldDefinition for the same code using SC layers
            String exceptionStr = null;
            Object returnVal = null;
            try {
               returnVal = DynUtil.invokeMethod(curObj, meth, args.toArray());
            }
            catch (Throwable methError) {
               exceptionStr = methError + ":\n" + PTypeUtil.getStackTrace(methError);
            }

            if (flushQueue) {
               SyncManager.flushSyncQueue();
               flushQueue = false;
            }

            if (returnVal != null) {
               syncCtx.registerObjName(returnVal, callId, false, false);
            }
            syncCtx.addMethodResult(isType ? null : curObj, isType ? curType : null, callId, returnVal, exceptionStr);
         }
         finally {
            if (flushQueue)
               SyncManager.flushSyncQueue();
         }
      }
   }

   public void applyMethodResult(String callId, Object returnValue, Object retType, String exceptionStr) {
      if (retType != null)
         returnValue = convertRemoteType(returnValue, retType);
      SyncManager.processMethodReturn(syncCtx, callId, returnValue, exceptionStr);
   }

   public void fetchProperty(String propName) {
      syncCtx.updateProperty(getCurObj(), propName, false, true);
   }
}
