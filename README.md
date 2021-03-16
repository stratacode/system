# StrataCode system 

### What is StrataCode?

StrataCode is at its core a build-tool for building projects by merging the contents of layers of source files. It also includes extensions to Java built using a unique code-processor and contains a number of associated frameworks for more customizable and scalable systems.

Read [more](https://www.stratacode.com)

### This repository

This is the Java source repository for building the source to StrataCode's scc command, used to build and run a program. It includes the code for the code-processor, data binding, the implementation of the StrataCode extensions to Java, and the Java to JS converter.

This distribution defines three layers (and corresponding IntelliJ modules) used to build the coreRuntime and StrataCode distributions for the 'scc' command.

Normally the scc distribution is built from scc itself, but it's possible to compile and run scc using IntelliJ or build the jar files using Gradle and create a run script by hand.

## Installing

It's best to point the 'scc' command at the stratacode source. That allows it to use the version of the coreRuntime libraries in the source tree rather than the one bundled with scc and helps the build system resolve the built-in types. 

If the directory that stores the bundles is called /home/myProj and the
path to this directory is /home/system: create the file 'scSourcePath' in the
'conf' directory with one line that contains the path to the /home/system directory like this: 

      ---- /home/myProj/conf/scSourcePath:
      /home/system

## coreRuntime 

A standalone library implementing data binding and type utilities that do not use reflection.  Originally split out for the (now retired) gwt integration where reflection is not present. It's also used as the source bundle for the JS framework. Annotations on classes here determine whether this class is converted to JS or whether a native stub is used to replace the type in the JS version.

## fullRuntime 

Depends on coreRuntime, replacing the main type utilities to provide implementations that use reflection. Full runtime should be in the classpath before core runtime so that it's classes are picked up first.

## system 

Depends on fullRuntime and contains the LayeredSystem, parsing and language framework - the 'main' for scc.

* To build StrataCode with Stratacode, make sure 'scc' is in your path and from this directory run bin/makeSCC

* To build the Javadoc run bin/makeJavaDoc

* To build the Documentation, make sure 'scc' is in your path and run bin/makeDoc

StrataCode is written in java so you can also build it without itself, or build it from the IDE in a Java project.  See the [ide-config](http://www.stratacode.com/doc/ide/config.html) doc for more details.
