#!/bin/sh

set -e

BUILDDIR=/tmp/scbuild
STAGEDIR=/tmp/

# For a clean build you should run this from the source directly, or first build StrataCode, then use it to build itself.
mkdir -p $BUILDDIR
sc -c -a coreRuntime -d $BUILDDIR
sc -c -a system -d $BUILDDIR

# Now we package up the STAGEDIR/StrataCode directory.  
rm -rf STAGEDIR/StrataCode
cp -r $BUILDDIR $STAGEDIR/StrataCode
cd $STAGEDIR
zip StrataCode.zip StrataCode/bin/* StrataCode/README.txt

echo "Result file is: $STAGEDIR/StrataCode.zip"
