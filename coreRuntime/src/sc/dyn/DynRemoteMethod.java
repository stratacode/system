/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.dyn;

/** A descriptor used as a placeholder for a remote method.  Returned by DynUtil.resolveMethod and used to feed metadata into the call to the SyncManager.invokeRemote */
public class DynRemoteMethod {
   public Object type;
   public String methodName;
   public Object returnType;
   public String paramSig;
   public boolean isStatic;
}
