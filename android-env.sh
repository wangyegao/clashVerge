#!/usr/bin/env sh

export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

case ":$PATH:" in
    *":$JAVA_HOME/bin:"*) ;;
    *) PATH="$JAVA_HOME/bin:$PATH" ;;
esac

case ":$PATH:" in
    *":$ANDROID_HOME/platform-tools:"*) ;;
    *) PATH="$ANDROID_HOME/platform-tools:$PATH" ;;
esac

export PATH
