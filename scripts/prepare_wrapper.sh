#!/bin/sh
set -eu
mkdir -p gradle/wrapper
# The verified wrapper is tracked for automatic build discovery. The fallback
# supports clean source archives that omit binary files.
if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
  curl --fail --location --proto '=https' --tlsv1.2 https://raw.githubusercontent.com/gradle/gradle/v9.9.0/gradle/wrapper/gradle-wrapper.jar -o gradle/wrapper/gradle-wrapper.jar
fi
printf '%s  %s\n' 621008afc4f5d59357fcf06ffa644dd5aade5e091bdc363742710445f73cdaf8 gradle/wrapper/gradle-wrapper.jar | sha256sum -c -

sh scripts/prepare_ocr_model.sh
