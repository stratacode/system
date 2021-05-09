# StrataCode 

StrataCode is an open source build-tool, code-processor, Java to JS converter, and extensions to Java for configuration, components and data binding. It merges directory trees and source code use type-safe merging. Layers in the stack can add new separate services, and include constraints to include/exclude themselves from one or more services. In this way, StrataCode can build and run a complete, coordinated multi-process system. It's a new more complete solution for building pluggable, service oriented systems, reducing code copies, and increasing the flexibility for how software components are defined and managed. 

Some layers are dynamic configuration layers, where all files are loaded when each service starts, others are merged at compile time.

There are a number of frameworks support for a very declarative and flexible experience for developing out of the box.

Data binding, events, properties, and components are built as extensions to Java using very little new syntax.

There's an IntelliJ plugin to tie everything together.

Read [more](https://www.stratacode.com)

This is a long time side project from one developer, but has been tested and debugged with lots of code over many years. There's no commercial support at this time.

### System git repository

Contains the source for the scc command, used to build and run programs with StrataCode. It includes the code-processor, data binding, language extensions, and the Java to JS converter wrapped up in a build-run command, or usable as libraries.

This distribution defines three layers (and three corresponding IntelliJ modules) used to build the coreRuntime and StrataCode distributions for the 'scc' command.

The downloadable scc distribution is built from scc itself, but it's possible to compile and run scc using IntelliJ or build the jar files using Gradle and create a run script by hand.

### Getting started

Download the [compiled scc] or build it here.

See [getting started](https://www.stratacode.com/doc/gettingStarted.html) from stratacode.com.

### Building

It's best to point the 'scc' command at the stratacode source. That allows it to use the version of the coreRuntime libraries in the source tree rather than the one bundled with scc and helps the build system resolve the built-in types. 

If the directory that stores the bundles is called /home/myProj and the
path to this directory is /home/system: create the file 'scSourcePath' in the
'conf' directory with one line that contains the path to the /home/system directory like this: 

      ---- /home/myProj/conf/scSourcePath:
      /home/system

### coreRuntime 

A standalone library implementing data binding. Includes versions of some type utilities that do not use reflection. This library was originally split out for the gwt integration where reflection is not available. It's also used as the source bundle for the JS framework where those type utilities are overridden by native JS implementations. Annotations on classes here determine whether this class is converted to JS or whether a native stub is used to replace the type in the JS version.

### fullRuntime 

Depends on coreRuntime, but not on system. It replaces the type utilities to provide implementations that do use reflection. Full runtime should be in the classpath before core runtime so that it's classes are picked up first.
Compiled StrataCode applications that do not use any code-processing features, but do need data binding, properties or components need only depend on fullRuntime.

### system 

Depends on fullRuntime and contains the LayeredSystem, parsing and language framework - the 'main' for scc.

* To build StrataCode with Stratacode, make sure 'scc' is in your path and from this directory run bin/makeSCC

* To build the Javadoc run bin/makeJavaDoc

* To build the Documentation, make sure 'scc' is in your path and run bin/makeDoc

StrataCode is written in plain old Java 6 syntax and can be built without itself.  See the [ide-config](http://www.stratacode.com/doc/ide/config.html) doc for more details on how to setup IntelliJ to build and run scc as well as for info on the StrataCode IntelliJ plugin.
