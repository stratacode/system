# StrataCode system 

Defines three layers (and corresponding IntelliJ modules) used to build the coreRuntime and StrataCode distributions for the 'scc' command.

## Installing

It's best to point the 'scc' command at the stratacode source. That allows it to use the version of the coreRuntime libraries in the source tree rather than the one bundled with scc and helps the build system resolve the built-in types. 

If the directory that stores the bundles is called /home/myProj and the
path to this directory is /home/system: create the file 'scSourcePath' in the
'conf' directory with one line that contains the path to the /home/system directory like this: 

      ---- /home/myProj/conf/scSourcePath:
      /home/system

## coreRuntime 

A standalone set of libraries that implement data binding etc. without dependencies on reflection.  It's used by the GIT framework and exposed as a source bundle for the JS framework.

## fullRuntime 

Depends on coreRuntime and overrides a few classes, replacing the coreRuntime versions with versions that use Java's reflection to find properties.  

## system 

Depends on fullRuntime and contains the LayeredSystem, parsing and language framework - the 'main' for scc.

* To build StrataCode with Stratacode, make sure 'scc' is in your path and from this directory run bin/makeSCC

* To build the Javadoc run bin/makeJavaDoc

* To build the Documentation, make sure 'scc' is in your path and run bin/makeDoc

StrataCode is written in java so you can also build it without itself, or build it from the IDE in a Java project.  See http://www.stratacode.com/doc/ide/config.html
