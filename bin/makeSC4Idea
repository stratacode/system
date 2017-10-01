#!/bin/sh

if [ -d sc4idea ] ; then
   git clone https://github.com/stratacode/sc4idea.git
fi

# Run with an argument to specify the target build dir.  If no argument is supplied a default is chosen in /tmp

set -e

if [ "$#" -eq 0 ]; then
   BUILDDIR=/tmp/sc4ideabuild
else
   BUILDDIR="$1"
fi

echo "Building sc4idea into: $BUILDDIR"


# For a clean build you should run this from the source directly, or first build StrataCode, then use it to build itself.
mkdir -p $BUILDDIR
sc -c -a sc4idea -da $BUILDDIR

echo "Result file is: $RESDIR/StrataCode.zip"
