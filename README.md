Defines three layers used to build the coreRuntime and StrataCode distributions.

* coreRuntime is a standalone set of libraries that implement data binding etc. without dependencies on reflection.  It's used by the GIT framework and exposed as a source bundle for the JS framework.

* fullRuntime depends on coreRuntime and overrides a few classes, replacing the coreRuntime versions with versions that use Java's reflection to find properties.  

* system depends on fullRuntime and contains the LayeredSystem, parsing and language framework.

* To build StrataCode, from this directory run bin/makeSC

* To build the Javadoc run bin/makeJavaDoc

* To build the Documentation run bin/makeDoc

StrataCode is written in java so you can also build it without itself.  See http://www.stratacode.com/ideConfig.html
