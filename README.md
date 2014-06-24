Defines three layers used to build the coreRuntime and StrataCode distributions.

* coreRuntime is a standalone set of libraries that implement data binding etc. without dependencies on reflection.  It's used by the GIT framework and exposed as a source bundle for the JS framework.

* fullRuntime overrides a few files to provide better functionality of the coreRuntime interfaces adding a dependency on Java's reflection.

* system contains the LayeredSystem, parsing and language framework.

Build instructions.  Using StrataCode itself:

mkdir /tmp/scbuild
sc -c -a coreRuntime -d /tmp/scbuild
sc -c -a system -d /tmp/scbuild
zip /tmp/sc.zip /tmp/scbuild

Find the 'sc' command in yourBuildDir.

StrataCode is written in java so you can build it without itself. From your IDE, run the main method of the sc.layer.LayeredSystem with those same arguments.

