/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.type;

@sc.js.JSSettings(jsModuleFile="js/scgen.js", prefixAlias="sc_")
public interface IResponseListener {
   void response(Object response);
   void error(int errorCode, Object error);
}
