#!/bin/sh
set -eu
mkdir -p app/src/main/assets/tessdata
file=app/src/main/assets/tessdata/eng.traineddata
if [ ! -f "$file" ]; then
  curl --fail --location --proto '=https' --tlsv1.2 https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/87416418657359cb625c412a48b6e1d6d41c29bd/eng.traineddata -o "$file"
fi
printf '%s  %s\n' 7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2 "$file" | sha256sum -c -
