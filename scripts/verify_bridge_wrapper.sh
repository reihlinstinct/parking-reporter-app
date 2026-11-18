#!/bin/sh
# Existing tracked wrapper, same digest as prepare_wrapper.sh, without OCR fetch.
set -eu
printf '%s  %s\n' 621008afc4f5d59357fcf06ffa644dd5aade5e091bdc363742710445f73cdaf8 gradle/wrapper/gradle-wrapper.jar | sha256sum -c -
