/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.dyn.RemoteResult;
import sc.obj.ScopeContext;
import sc.obj.ScopeDefinition;

public interface IPageDispatcher {
   public IPageEntry lookupPageType(String url);

   public RemoteResult invokeRemote(Object obj, Object type, String methName, Object retType, String paramSig, Object...args);

   public void destroyPage(IPage page, IPageEntry pageEnt);
}
