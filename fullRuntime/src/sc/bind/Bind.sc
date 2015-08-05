/** Modify the jarPackages required for the full runtime.  Modifies the Bind class in the coreRuntime and adds a few packages that go into the full runtime jar. */
@CompilerSettings(jarFileName="bin/scrt.jar", srcJarFileName="bin/scrt-src.jar", jarPackages={"sc.type", "sc.bind", "sc.obj", "sc.dyn", "sc.js", "sc.util", "sc"})
Bind {}
