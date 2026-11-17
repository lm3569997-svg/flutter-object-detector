#!/bin/sh
# 最小可用 gradlew：没 wrapper jar 时用系统 gradle
if [ -f "gradle/wrapper/gradle-wrapper.jar" ]; then
  exec java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain "$@"
else
  exec gradle "$@"
fi
