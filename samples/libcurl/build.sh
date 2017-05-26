#!/usr/bin/env bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}" )" && pwd )"
PATH=$DIR/../../dist/bin:$DIR/../../bin:$PATH

CFLAGS_macbook=-I/opt/local/include
CFLAGS_linux=-I/usr/include/x86_64-linux-gnu
LINKER_ARGS_macbook="-L/opt/local/lib -lcurl"
LINKER_ARGS_linux="-L/usr/lib/x86_64-linux-gnu -lcurl"

if [ x$TARGET == x ]; then
case "$OSTYPE" in
  darwin*)  TARGET=macbook ;;
  linux*)   TARGET=linux ;;
  *)        echo "unknown: $OSTYPE" && exit 1;;
esac
fi

var=CFLAGS_${TARGET}
CFLAGS=${!var}
var=LINKER_ARGS_${TARGET}
LINKER_ARGS=${!var}
var=COMPILER_ARGS_${TARGET}
COMPILER_ARGS=${!var} # add -opt for an optimized build.

rm -rf $DIR/build/
mkdir $DIR/build/
mkdir $DIR/build/c_interop/
mkdir $DIR/build/bin/

cinterop -copt "$CFLAGS" -copt -I$DIR -copt -I/usr/include -def $DIR/src/c_interop/libcurl.def -target $TARGET \
         -o $DIR/build/c_interop/libcurl || exit 1

konanc -target $TARGET $DIR/src/kotlin-native -library $DIR/build/c_interop/libcurl -linkerArgs "$LINKER_ARGS" \
       -o $DIR/build/bin/Curl || exit 1

echo "Artifact path is $DIR/build/bin/Curl.kexe"
