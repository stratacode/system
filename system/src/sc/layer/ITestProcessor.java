/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

public interface ITestProcessor {
   void initTypes();
   boolean executeTest(Object testClass);
}
