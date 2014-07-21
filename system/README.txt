This is the StrataCode install directory.  

Run the command 'bin/sc' or put the 'bin' directory in your path and run 'sc'.   This command compiles and runs your
application all in one step.

You typically run the sc command with a layer that represents your application.  You can add additional layers to customize how it 
behaves.  Run "sc -help" for a list of options.

Additional contents for developers:

  - the lib directory contains:
  
    scrt.jar - the runtime files - needed by StrataCode applications which use any of the language features of StrataCode.
    scrt.jar - the StrataCode runtime required by the normal Java runtime
    scrt-core.jar - a minimal runtime that does not require reflection - used for the GWT integration
    sc.jar file - the JAR which contains all of StrataCode - it includes the scrt.jar so use this for developing SC applications.

