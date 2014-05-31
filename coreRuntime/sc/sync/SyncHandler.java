/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.sync;

import sc.bind.Bind;
import sc.dyn.DynUtil;
import sc.obj.Sync;
import sc.obj.SyncMode;
import sc.type.CTypeUtil;
import sc.type.PTypeUtil;
import sc.type.Type;

import java.util.*;

@sc.js.JSSettings(jsModuleFile="js/sync.js", prefixAlias="sc_")
@Sync(syncMode= SyncMode.Disabled)
public class SyncHandler {
   public Object changedObj;
   public SyncManager.SyncContext syncContext;

   public SyncHandler(Object inst, SyncManager.SyncContext ctx) {
      changedObj = replaceInstance(inst);
      syncContext = ctx;
   }

   /** Hook for subclasses to substitute a new instance for the one in the model.  Given the original instance, return
    the instance that is to be used to synchronize against the client. */
   protected Object replaceInstance(Object inst) {
      return inst;
   }

   /**
    * Hook for subclasses to restore an instance which was replaced with replaceInstance, after that instance from the remote side has been
    * deserialized and before it's used in the application, for a property set or method call.
    */
   protected Object restoreInstance(Object inst) {
      return inst;
   }

   public String getObjectBaseName(List<SyncLayer.SyncChange> depChanges, SyncLayer syncLayer) {
      return syncContext.getObjectBaseName(changedObj, depChanges, syncLayer);
   }

   public String getObjectName() {
      return syncContext.getObjectName(changedObj, null, null);
   }

   public boolean isNewInstance() {
      return syncContext.isNewInstance(changedObj);
   }

   public String getPackageName() {
      String objName = getObjectName();

      int numLevels = DynUtil.getNumInnerTypeLevels(DynUtil.getType(changedObj));
      int numObjLevels = DynUtil.getNumInnerObjectLevels(changedObj);
      numLevels = Math.max(numLevels, numObjLevels); // Sometimes there are more levels in the object hierarchy than the type hierarchy because we optimized out the runtime class.
      String root = CTypeUtil.getPackageName(objName);
      while (numLevels > 0) {
         numLevels -= 1;
         if (root == null) {
            return null;
         }
         root = CTypeUtil.getPackageName(root);
         if (root == null)
            break;
      }
      return root;
   }

   public CharSequence getPropertyUpdateCode(Object changedObj, String propName, Object propValue, Object previousValue, ArrayList<String> currentObjNames, String currentPackageName, StringBuilder preBlockCode, StringBuilder postBlockCode, List<SyncLayer.SyncChange> depChanges, SyncLayer syncLayer) {
      try {
         StringBuilder sb = new StringBuilder();
         sb.append(propName);
         sb.append(" = ");
         sb.append(syncContext.expressionToString(propValue, currentObjNames, currentPackageName, preBlockCode, postBlockCode, propName, false, "", depChanges, syncLayer));
         return sb;
      }
      catch (UnsupportedOperationException exc) {
         System.err.println("*** Error serializing property: " + propName);
         exc.printStackTrace();
         return "// Error serializing value of property: " + propName;
      }
      catch (RuntimeException exc) {
         System.err.println("*** Runtime error in expressionToString for: " + propName);
         exc.printStackTrace();
         throw exc;
      }
   }

   public CharSequence expressionToString(ArrayList<String> currentObjNames, String currentPackageName, StringBuilder preBlockCode, StringBuilder postBlockCode, String varName, boolean inBlock, String uniqueId, List<SyncLayer.SyncChange> depChanges, SyncLayer syncLayer) {
      if (changedObj == null)
         return "null";

      Class cl = changedObj.getClass();
      int numLevels = currentObjNames.size();
      if (PTypeUtil.isArray(cl) || changedObj instanceof Collection) {
         StringBuilder sb = new StringBuilder();
         int sz = DynUtil.getArrayLength(changedObj);
         if (varName == null) {
            // We already have registered a global id for this object so we can just use that id.
            String objName = syncContext.getObjectName(changedObj, null, false, false, null, syncLayer);
            if (objName != null) {
               if (currentPackageName != null && objName.startsWith(currentPackageName)) {
                  sb.append(objName.substring(currentPackageName.length() + 1));
               }
               else
                  sb.append(objName);
            }
            else {
               preBlockCode.append(Bind.indent(numLevels));
               String typeName = DynUtil.getTypeName(cl, true);
               preBlockCode.append(typeName);
               preBlockCode.append(" ");
               preBlockCode.append("_lt");
               preBlockCode.append(uniqueId);
               preBlockCode.append(" = ");
               preBlockCode.append("new ");
               preBlockCode.append(typeName);
               preBlockCode.append("();\n");
               if (!inBlock) {
                  preBlockCode.append(Bind.indent(numLevels));
                  preBlockCode.append("{\n");
               }
               int numCodeLevels = numLevels + 1;

               for (int i = 0; i < sz; i++) {
                  Object val = DynUtil.getArrayElement(changedObj, i);
                  CharSequence valueExpr = syncContext.expressionToString(val, currentObjNames, currentPackageName, preBlockCode, postBlockCode, null, true, uniqueId + "_" + i, depChanges, syncLayer);
                  preBlockCode.append(Bind.indent(numCodeLevels));
                  preBlockCode.append("_lt");
                  preBlockCode.append(uniqueId);
                  preBlockCode.append(".add(");
                  preBlockCode.append(valueExpr);
                  preBlockCode.append(");\n");
               }
               if (!inBlock) {
                  preBlockCode.append(Bind.indent(numLevels));
                  preBlockCode.append("}\n");
               }

               sb.append("_lt");
               sb.append(uniqueId);
            }
         }
         // In this case it is a simple variable definition so we can use array initializer syntax
         else {
            sb.append("{");
            for (int i = 0; i < sz; i++) {
               Object val = DynUtil.getArrayElement(changedObj, i);
               if (i != 0)
                  sb.append(", ");
               sb.append(syncContext.expressionToString(val, currentObjNames, currentPackageName, preBlockCode, postBlockCode, null, inBlock, uniqueId + "_" + i, depChanges, syncLayer));
            }
            sb.append("}");
         }
         return sb;
      }
      else if (changedObj instanceof Map) {
         StringBuilder sb = new StringBuilder();
         sb.append("new ");
         sb.append(DynUtil.getTypeName(cl, true));
            sb.append("();\n");
         Map changedMap = (Map) changedObj;
         if (changedMap.size() > 0) {
            if (!inBlock) {
               postBlockCode.append(Bind.indent(numLevels));
               postBlockCode.append("{\n");
            }
            int ct = 0;

            StringBuilder mb = new StringBuilder();
            for (Object mapEntObj: changedMap.entrySet()) {
               StringBuilder mapPreBlockCode = new StringBuilder();
               StringBuilder mapPostBlockCode = new StringBuilder();
               Map.Entry mapEnt = (Map.Entry) mapEntObj;
               StringBuilder newExpr = new StringBuilder();
               newExpr.append(Bind.indent(numLevels + 1));
               // If we are the initializer for a variable, just use that name.
               if (varName != null)
                  newExpr.append(varName);
               // Otherwise, this object needs to have a global name we can use to refer to it with.
               else {
                  String objName = syncContext.getObjectName(changedMap, varName, true, true, depChanges, syncLayer);
                  if (objName != null)
                     newExpr.append(objName);
               }
               String subUniqueId = uniqueId + "_" + ct;
               newExpr.append(".put(");
               newExpr.append(syncContext.expressionToString(mapEnt.getKey(), currentObjNames, currentPackageName, mapPreBlockCode, mapPostBlockCode, null, true, subUniqueId, depChanges, syncLayer));
               newExpr.append(", ");
               newExpr.append(syncContext.expressionToString(mapEnt.getValue(), currentObjNames, currentPackageName, mapPreBlockCode, mapPostBlockCode, null, true, subUniqueId, depChanges, syncLayer));
               newExpr.append(");\n");

               mb.append(mapPreBlockCode);
               mb.append(newExpr);
               mb.append(mapPostBlockCode);
               ct++;
            }

            postBlockCode.append(mb);
            if (!inBlock) {
               postBlockCode.append(Bind.indent(numLevels));
               postBlockCode.append("}\n");
            }
         }
         return sb.toString();
      }
      else {
         Class literalClass = changedObj.getClass();
         Type literalType = Type.get(literalClass);
         switch (literalType) {
            case Boolean:
               return ((Boolean) changedObj) ? "true" : "false";
            case Byte:
            case Short:
            case Integer:
               return changedObj.toString();
            case Long:
               return changedObj.toString() +  "l";
            case Float:
               return changedObj.toString() +  "f";
            case Double:
               return changedObj.toString() +  "d";
            case Character:
               return "'" + CTypeUtil.escapeJavaString(changedObj.toString(), true) + "'";
            case String:
               return "\"" + CTypeUtil.escapeJavaString(changedObj.toString(), false) + "\"";
            case Object:
               String val = syncContext.getObjectName(changedObj, varName, false, false, null, syncLayer);
               if (val != null) {
                  if (currentPackageName != null && val.startsWith(currentPackageName) && currentPackageName.length() > 0)
                     val = val.substring(currentPackageName.length() + 1);
                  return val;
               }
               return syncContext.createOnDemandInst(changedObj, depChanges, varName, syncLayer);
            default: // TODO: Number on the JS side falls into this case
               return changedObj.toString();
         }
      }
   }
}
