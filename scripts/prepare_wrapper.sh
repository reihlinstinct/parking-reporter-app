#!/bin/sh
set -eu
mkdir -p gradle/wrapper
if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
  curl --fail --location --proto '=https' --tlsv1.2 https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar -o gradle/wrapper/gradle-wrapper.jar
fi
printf '%s  %s\n' 2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046 gradle/wrapper/gradle-wrapper.jar | sha256sum -c -
