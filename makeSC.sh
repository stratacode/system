#!/bin/sh

# Run with an argument to specify the target build dir.  If no argument is supplied a default is chosen in /tmp

set -e

STAGEDIR=/tmp/

if [ "$#" -eq 0 ]; then
   BUILDDIR=/tmp/scbuild
   RESDIR=$STAGEDIR
else
   BUILDDIR="$1"
   RESDIR="$1"
fi

echo "Building StrataCode into: $BUILDDIR"


# For a clean build you should run this from the source directly, or first build StrataCode, then use it to build itself.
mkdir -p $BUILDDIR
sc -c -a coreRuntime -da $BUILDDIR
sc -c -a system -da $BUILDDIR

# Now we package up the STAGEDIR/StrataCode directory.  
rm -rf STAGEDIR/StrataCode

# old - top level build dir
echo "cp -rf $BUILDDIR $STAGEDIR/StrataCode"
cp -rf $BUILDDIR $STAGEDIR/StrataCode

# new - sub-dirs
#echo "cp -rf $BUILDDIR/coreRuntime/build/ $STAGEDIR/StrataCode"
#cp -rf $BUILDDIR/coreRuntime/build/ $STAGEDIR/StrataCode
#echo "cp -rf $BUILDDIR/system/build/ $STAGEDIR/StrataCode"
#cp -rf $BUILDDIR/system/build/ $STAGEDIR/StrataCode

echo "cd $STAGEDIR"
cd $STAGEDIR
echo "zip StrataCode.zip StrataCode/bin/* StrataCode/README.txt"
zip StrataCode.zip StrataCode/bin/* StrataCode/README.txt

if [ "$RESDIR" != "$STAGEDIR" ]; then
   mv StrataCode.zip $RESDIR/
fi

echo "Result file is: $RESDIR/StrataCode.zip"
