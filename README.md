Defines three layers used to build the coreRuntime and StrataCode distributions.

* coreRuntime is a standalone set of libraries that implement data binding etc. without dependencies on reflection.  It's used by the GIT framework and exposed as a source bundle for the JS framework.

* fullRuntime overrides a few files to provide better functionality of the coreRuntime interfaces adding a dependency on Java's reflection.

* system contains the LayeredSystem, parsing and language framework.

* To build StrataCode run makeSC.sh

* To build the Javadoc run makeDoc.sh

StrataCode is written in java so you can also build it without itself.  See http://www.stratacode.com/ideConfig.html
