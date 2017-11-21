/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.sync;

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
      if (inst instanceof Class) {
         ClassSyncWrapper classWrap = new ClassSyncWrapper(((Class) inst).getName());
         SyncManager.addSyncInst(classWrap, true, false, null, classWrap.className);
         return classWrap;
      }
      return inst;
   }

   /**
    * Hook for subclasses to restore an instance which was replaced with replaceInstance, after that instance from the remote side has been
    * deserialized and before it's used in the application, for a property set or method call.
    */
   protected Object restoreInstance(Object inst) {
      if (inst instanceof ClassSyncWrapper) {
         String remoteClassName = ((ClassSyncWrapper) inst).className;
         Object remoteClass = DynUtil.findType(remoteClassName);
         if (remoteClass == null)
            System.err.println("*** Unable to restore reference to class: " + remoteClassName);
         return remoteClass;
      }
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

   public void appendPropertyUpdateCode(SyncSerializer ser, Object changedObj, String propName, Object propValue, Object previousValue, ArrayList<String> currentObjNames, String currentPackageName, SyncSerializer preBlockCode, SyncSerializer postBlockCode,
                                        List<SyncLayer.SyncChange> depChanges, SyncLayer syncLayer) {
      ser.appendPropertyAssignment(syncContext, changedObj, propName, propValue, previousValue, currentObjNames, currentPackageName, preBlockCode, postBlockCode, depChanges, syncLayer);
   }

   public void formatExpression(SyncSerializer ser, StringBuilder out, ArrayList<String> currentObjNames, String currentPackageName, SyncSerializer preBlockCode,
                                SyncSerializer postBlockCode, String varName, boolean inBlock, String uniqueId, List<SyncLayer.SyncChange> depChanges, SyncLayer syncLayer) {
      if (changedObj == null) {
         ser.formatNullValue(out);
         return;
      }

      Class cl = changedObj.getClass();
      if (PTypeUtil.isArray(cl) || changedObj instanceof Collection) {
         StringBuilder sb = new StringBuilder();
         // We already have registered a global id for this object so we can just use that id.
         String objName = syncContext.getObjectName(changedObj, null, false, false, null, syncLayer);
         if (objName != null) {
            // TODO: for some reason if varName == null we used to not check for an existing reference but that broke the JSON for the 'converters' object
            ser.formatReference(out, objName, currentPackageName);
         }
         else {
            if (varName == null) {
               // We have to create the array - i.e. new Type[] { val1, val2, ...} or x = new ArrayList(); x.add(val1), ...
               String typeName = DynUtil.getTypeName(cl, true);
               ser.formatNewArrayDef(out, syncContext, changedObj, typeName, currentObjNames, currentPackageName, preBlockCode, postBlockCode, inBlock, uniqueId, depChanges, syncLayer);
            }
            // In this case it is a simple variable definition so we can use array initializer syntax
            else {
               ser.formatArrayExpression(out, syncContext, changedObj, currentObjNames, currentPackageName, preBlockCode, postBlockCode, inBlock, uniqueId, depChanges, syncLayer);
            }
         }
      }
      else if (changedObj instanceof Map) {
         Map changedMap = (Map) changedObj;

         ser.formatMap(out, syncContext, changedMap, DynUtil.getTypeName(cl, true), currentObjNames, currentPackageName, preBlockCode, postBlockCode, varName, inBlock, uniqueId, depChanges, syncLayer);
      }
      else {
         Type literalType;
         if (changedObj instanceof String || changedObj instanceof StringBuilder) {
            literalType = Type.String;
         }
         else {
            Class literalClass;
            literalClass = changedObj.getClass();
            literalType = Type.get(literalClass);
         }
         switch (literalType) {
            case Boolean:
               ser.formatBoolean(out, (Boolean) changedObj);
               break;
            case Byte:
               ser.formatByte(out, (Byte) changedObj);
               break;
            case Short:
               ser.formatShort(out, (Short) changedObj);
               break;
            case Integer:
               ser.formatInt(out, (Integer) changedObj);
               break;
            case Long:
               ser.formatLong(out, (Long) changedObj);
               break;
            case Float:
               ser.formatFloat(out, (Float) changedObj);
               break;
            case Double:
               ser.formatDouble(out, (Double) changedObj);
               break;
            case Character:
               ser.formatChar(out, changedObj.toString());
               break;
            case String:
               ser.formatString(out, changedObj.toString());
               break;
            case Number: // Here for JS
               ser.formatNumber(out, (Number) changedObj);
               break;
            case Object:
               String val = syncContext.getObjectName(changedObj, varName, false, false, null, syncLayer);
               if (val == null)
                  val = syncContext.createOnDemandInst(changedObj, depChanges, varName, syncLayer);
               ser.formatReference(out, val, currentPackageName);
               break;
            default:
               ser.formatDefault(out, changedObj);
               break;
         }
      }
   }

   /** A hook so you can replace the base type used for recreating the type on the other side.  It can return a TypeDeclaration or Class */
   public Object getObjectType(Object changedObj) {
      return DynUtil.isType(changedObj) ? changedObj.getClass() : DynUtil.getType(changedObj);
   }
}
